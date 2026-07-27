package com.xtv.app.core.purchase

import com.xtv.app.core.model.Author
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.MediaKind
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelPurchaseContractTest {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        appScope.cancel()
    }

    @Test
    fun `fifty presses dispatch exactly one paid read`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val timeline = FakeTimeline { request ->
            release.await()
            TimelineOutcome.Success(
                items = listOf(video("v1")),
                newestPostId = "101",
                resources = ResourceCounts(posts = request.requestedPosts, users = 1, media = 1),
            )
        }
        val purchase = purchase(timeline = timeline)
        val ready = purchase.awaitReady()
        val token = ready.offers.last().token

        val results = List(50) { purchase.dispatch(PurchaseCommand.Buy(token)) }

        assertEquals(1, results.count { it is DispatchResult.Accepted })
        assertEquals(49, results.count { it is DispatchResult.AlreadyAccepted })
        release.complete(Unit)
        purchase.awaitTerminal()
        assertEquals(1, timeline.calls.get())
    }

    @Test
    fun `video-empty paid success advances cursor and retains the previous reel`() = runBlocking {
        val old = PurchasedReel(
            id = "old",
            items = listOf(video("old-video")),
            status = ReelStatus.COMPLETED,
            nextIndex = 1,
        )
        val store = InMemoryPurchaseStatePort(
            PurchaseRecord(cursor = "90", currentReel = old),
        )
        val purchase = purchase(
            store = store,
            timeline = FakeTimeline {
                TimelineOutcome.Success(
                    items = emptyList(),
                    newestPostId = "110",
                    resources = ResourceCounts(posts = 7, users = 4, media = 0),
                )
            },
        )

        val token = purchase.awaitReady().offers.first().token
        purchase.dispatch(PurchaseCommand.Buy(token))
        val terminal = purchase.awaitTerminal()
        val record = store.read()

        assertTrue((terminal.operation as PurchaseOperation.Finished).outcome is PurchaseOutcome.NoPlayableVideo)
        assertEquals("110", record.cursor)
        assertEquals(old, record.currentReel)
        assertEquals(7, record.lastReceipt?.resources?.posts)
    }

    @Test
    fun `server overdelivery is fully accounted and retained`() = runBlocking {
        val store = InMemoryPurchaseStatePort(PurchaseRecord())
        val delivered = List(34) { video("v$it") }
        val purchase = purchase(
            store = store,
            timeline = FakeTimeline {
                TimelineOutcome.Success(
                    items = delivered,
                    newestPostId = "200",
                    resources = ResourceCounts(posts = 34, users = 20, media = 34),
                )
            },
        )

        val token = purchase.awaitReady().offers.first().token
        purchase.dispatch(PurchaseCommand.Buy(token))
        purchase.awaitTerminal()
        val record = store.read()

        assertEquals(34, record.lastReceipt?.resources?.posts)
        assertEquals(34, record.currentReel?.items?.size)
        assertTrue(PurchaseWarning.SERVER_OVERDELIVERY in record.lastReceipt!!.warnings)
        assertEquals(
            RateCard.current().charge(ResourceCounts(posts = 34, users = 20, media = 34)),
            record.lastReceipt?.estimatedCharge,
        )
    }

    @Test
    fun `ambiguous failure commits the full reservation and never retries`() = runBlocking {
        val store = InMemoryPurchaseStatePort(PurchaseRecord())
        val timeline = FakeTimeline {
            TimelineOutcome.Failure(
                problem = PurchaseProblem.Network,
                sentState = RequestSentState.POSSIBLY_SENT,
            )
        }
        val purchase = purchase(store = store, timeline = timeline)
        val offer = purchase.awaitReady().offers.first()

        purchase.dispatch(PurchaseCommand.Buy(offer.token))
        val terminal = purchase.awaitTerminal()
        val record = store.read()

        assertEquals(
            offer.charge.reservation,
            (record.terminal as DurableTerminal.Interrupted).conservativelyCommitted,
        )
        assertEquals(1, timeline.calls.get())
        assertTrue(terminal.operation is PurchaseOperation.Interrupted)
        assertEquals(null, record.pending)
    }

    @Test
    fun `partial paid result uses posts read even when no video was extracted`() = runBlocking {
        val previous = PurchasedReel(
            id = "prior",
            items = listOf(video("prior")),
            status = ReelStatus.IN_PROGRESS,
            nextIndex = 0,
        )
        val store = InMemoryPurchaseStatePort(
            PurchaseRecord(currentReel = previous),
        )
        val purchase = purchase(
            store = store,
            timeline = FakeTimeline {
                TimelineOutcome.Partial(
                    items = emptyList(),
                    newestPostId = "300",
                    resources = ResourceCounts(posts = 9, users = 7, media = 0),
                    problem = PurchaseProblem.RateLimited(resetAtMs = 123),
                )
            },
        )

        purchase.dispatch(PurchaseCommand.Buy(purchase.awaitReady().offers.first().token))
        purchase.awaitTerminal()
        val record = store.read()

        assertEquals(9, record.lastReceipt?.resources?.posts)
        assertEquals("300", record.cursor)
        assertEquals(previous, record.currentReel)
        assertTrue(PurchaseWarning.PARTIAL_PAGE in record.lastReceipt!!.warnings)
    }

    @Test
    fun `cold recovery settles dispatched reservation without any network`() = runBlocking {
        val reserved = UsdMicros(1_000_000)
        val pending = DurablePurchase(
            id = OperationId("crashed"),
            token = OfferToken("spent"),
            requestedPosts = 30,
            quote = RateCard.current().quote(30, identityLookupNeeded = false)
                .copy(reservation = reserved),
            stage = DurableStage.DISPATCHED,
            startedAtMs = 1,
        )
        val store = InMemoryPurchaseStatePort(
            PurchaseRecord(pending = pending),
        )
        val timeline = FakeTimeline { error("recovery must not call the network") }
        val session = FakeSession()
        val usage = FakeUsage()

        val purchase = purchase(store = store, timeline = timeline, session = session, usage = usage)
        val terminal = purchase.awaitTerminal()

        assertTrue(terminal.operation is PurchaseOperation.Interrupted)
        assertEquals(
            reserved,
            (store.read().terminal as DurableTerminal.Interrupted).conservativelyCommitted,
        )
        assertEquals(0, timeline.calls.get())
        assertEquals(0, session.calls.get())
        assertEquals(0, usage.calls.get())
    }

    @Test
    fun `cold recovery charges only identity exposure before timeline dispatch`() = runBlocking {
        val pending = DurablePurchase(
            id = OperationId("identity-crash"),
            token = OfferToken("spent"),
            requestedPosts = 30,
            quote = RateCard.current().quote(30, identityLookupNeeded = true),
            stage = DurableStage.IDENTITY_DISPATCHED,
            startedAtMs = 1,
            identityExposure = RateCard.current().userRead,
        )
        val store = InMemoryPurchaseStatePort(
            PurchaseRecord(pending = pending),
        )
        val timeline = FakeTimeline { error("recovery must not call the network") }

        val terminal = purchase(store = store, timeline = timeline).awaitTerminal()

        assertTrue(terminal.operation is PurchaseOperation.Interrupted)
        assertEquals(
            RateCard.current().userRead,
            (store.read().terminal as DurableTerminal.Interrupted).conservativelyCommitted,
        )
        assertEquals(0, timeline.calls.get())
    }

    @Test
    fun `ambiguous identity failure charges one user read not the reel reservation`() = runBlocking {
        val store = InMemoryPurchaseStatePort(PurchaseRecord())
        val session = FakeSession(
            SessionOutcome.Failure(PurchaseProblem.Network, possiblyCharged = true),
        )
        val timeline = FakeTimeline { error("timeline must not run") }
        val reelPurchase = purchase(store = store, session = session, timeline = timeline)
        val offer = reelPurchase.awaitReady().offers.first()

        reelPurchase.dispatch(PurchaseCommand.Buy(offer.token))
        val terminal = reelPurchase.awaitTerminal()

        assertTrue(terminal.operation is PurchaseOperation.Interrupted)
        val committed =
            (store.read().terminal as DurableTerminal.Interrupted).conservativelyCommitted
        assertEquals(RateCard.current().userRead, committed)
        assertTrue(committed < offer.charge.reservation)
        assertEquals(0, timeline.calls.get())
    }

    @Test
    fun `possibly sent partial page keeps media cursor and full reservation`() = runBlocking {
        val store = InMemoryPurchaseStatePort(PurchaseRecord())
        val reelPurchase = purchase(
            store = store,
            timeline = FakeTimeline {
                TimelineOutcome.Partial(
                    items = listOf(video("partial")),
                    newestPostId = "321",
                    resources = ResourceCounts(posts = 4, users = 3, media = 1),
                    problem = PurchaseProblem.Network,
                    sentState = RequestSentState.POSSIBLY_SENT,
                )
            },
        )
        val offer = reelPurchase.awaitReady().offers.first()

        reelPurchase.dispatch(PurchaseCommand.Buy(offer.token))
        reelPurchase.awaitTerminal()
        val record = store.read()

        assertEquals(offer.charge.reservation, record.lastReceipt?.estimatedCharge)
        assertEquals("321", record.cursor)
        assertEquals("partial", record.currentReel?.items?.single()?.id)
        assertEquals(true, record.currentReel?.partial)
        assertEquals(
            AccountingCertainty.REQUEST_OUTCOME_UNKNOWN,
            record.lastReceipt?.accountingCertainty,
        )
    }

    @Test
    fun `write then verification failure cannot settle the same operation twice`() = runBlocking {
        val store = WriteThenThrowPurchasePort(PurchaseRecord())
        val resources = ResourceCounts(posts = 3, users = 2, media = 1)
        val reelPurchase = purchase(
            store = store,
            timeline = FakeTimeline {
                TimelineOutcome.Success(
                    items = listOf(video("one")),
                    newestPostId = "10",
                    resources = resources,
                )
            },
        )

        reelPurchase.dispatch(PurchaseCommand.Buy(reelPurchase.awaitReady().offers.first().token))
        reelPurchase.awaitTerminal()

        assertEquals(
            RateCard.current().charge(resources),
            store.read().lastReceipt?.estimatedCharge,
        )
        assertEquals(1, store.terminalWriteFailures)
    }

    @Test
    fun `terminal can be acknowledged immediately without a busy race`() = runBlocking {
        val reelPurchase = purchase()
        reelPurchase.dispatch(PurchaseCommand.Buy(reelPurchase.awaitReady().offers.first().token))
        val terminal = reelPurchase.awaitTerminal().operation as PurchaseOperation.Finished

        val acknowledged = reelPurchase.dispatch(PurchaseCommand.Acknowledge(terminal.id))

        assertTrue(acknowledged is DispatchResult.Accepted)
        assertTrue(reelPurchase.awaitReady().operation is PurchaseOperation.Idle)
    }

    @Test
    fun `transaction rejects an offer after account scope changes`() = runBlocking {
        val store = InMemoryPurchaseStatePort(
            PurchaseRecord(
                accountId = "old",
                accountScope = "old",
                projectScope = "client",
            ),
        )
        val timeline = FakeTimeline {
            TimelineOutcome.Success(
                items = listOf(video("must-not-run")),
                newestPostId = "1",
                resources = ResourceCounts(posts = 1),
            )
        }
        val reelPurchase = purchase(store = store, timeline = timeline)
        val offer = reelPurchase.awaitReady().offers.first()
        store.update {
            it.copy(accountId = "new", accountScope = "new", projectScope = "client")
        }

        reelPurchase.dispatch(PurchaseCommand.Buy(offer.token))
        val terminal = reelPurchase.awaitTerminal().operation as PurchaseOperation.Failed

        assertEquals(PurchaseProblem.StaleOffer, terminal.problem)
        assertEquals(0, timeline.calls.get())
    }

    private fun purchase(
        store: PurchaseStatePort = InMemoryPurchaseStatePort(PurchaseRecord()),
        timeline: FakeTimeline = FakeTimeline {
            TimelineOutcome.Success(
                items = listOf(video("default")),
                newestPostId = "1",
                resources = ResourceCounts(posts = 1, users = 1, media = 1),
            )
        },
        session: FakeSession = FakeSession(),
        usage: FakeUsage = FakeUsage(),
    ): DefaultReelPurchase = DefaultReelPurchase(
        applicationScope = appScope,
        store = store,
        session = session,
        timeline = timeline,
        usage = usage,
        clock = FixedPurchaseClock(1_000),
        idFactory = { OperationId("operation-${ids.incrementAndGet()}") },
        reelIdFactory = { "reel-${ids.incrementAndGet()}" },
    )

    private suspend fun ReelPurchase.awaitReady(): PurchaseSnapshot = withTimeout(2_000) {
        state.first { it.readiness == PurchaseReadiness.READY && it.operation is PurchaseOperation.Idle }
    }

    private suspend fun ReelPurchase.awaitTerminal(): PurchaseSnapshot = withTimeout(2_000) {
        state.first {
            it.operation is PurchaseOperation.Finished ||
                it.operation is PurchaseOperation.Failed ||
                it.operation is PurchaseOperation.Interrupted
        }
    }

    private class FakeSession(
        private val result: SessionOutcome = SessionOutcome.Ready(
            accessToken = "access",
            accountId = "account",
            identityLookupCharged = false,
        ),
    ) : PurchaseSessionPort {
        val calls = AtomicInteger()
        override suspend fun session(
            beforeIdentityLookup: suspend () -> Boolean,
        ): SessionOutcome {
            calls.incrementAndGet()
            val needsIdentityJournal = when (result) {
                is SessionOutcome.Ready -> result.identityLookupCharged
                is SessionOutcome.Failure -> result.possiblyCharged
            }
            if (needsIdentityJournal && !beforeIdentityLookup()) {
                return SessionOutcome.Failure(PurchaseProblem.StorageUnavailable)
            }
            return result
        }
    }

    private class FakeTimeline(
        private val answer: suspend (TimelineRequest) -> TimelineOutcome,
    ) : TimelineReadPort {
        val calls = AtomicInteger()
        override suspend fun read(request: TimelineRequest): TimelineOutcome {
            calls.incrementAndGet()
            return answer(request)
        }
    }

    private class FakeUsage : ProjectUsagePort {
        val calls = AtomicInteger()
        override suspend fun refresh(): ProjectPostUsage? {
            calls.incrementAndGet()
            return ProjectPostUsage(posts = 12, resetDay = 26, observedAtMs = 2_000)
        }
    }

    private companion object {
        val ids = AtomicInteger()

        fun video(id: String) = MediaItem(
            id = id,
            kind = MediaKind.VIDEO,
            indexInPost = 0,
            countInPost = 1,
            displayUrl = "https://media.invalid/$id.mp4",
            width = 1920,
            height = 1080,
            author = Author("u", "user", "User"),
            text = "",
            createdAtMs = 0,
        )
    }
}

private class WriteThenThrowPurchasePort(initial: PurchaseRecord) : PurchaseStatePort {
    private val mutex = Mutex()
    private var value = initial
    var terminalWriteFailures = 0
        private set

    override suspend fun read(): PurchaseRecord = mutex.withLock { value }

    override suspend fun update(
        transform: (PurchaseRecord) -> PurchaseRecord,
    ): PurchaseRecord = mutex.withLock {
        val before = value
        val after = transform(before)
        value = after
        if (
            terminalWriteFailures == 0 &&
            before.terminal == null &&
            after.terminal != null
        ) {
            terminalWriteFailures += 1
            error("post-write verification failed")
        }
        after
    }
}
