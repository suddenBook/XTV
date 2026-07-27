package com.xtv.app.core.purchase

import com.xtv.app.core.model.Author
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.MediaKind
import com.xtv.app.core.storage.EmptyLegacyStateSource
import com.xtv.app.core.storage.EncryptedEnvelopeBackend
import com.xtv.app.core.storage.EnvelopeRead
import com.xtv.app.core.storage.PrivateStateRead
import com.xtv.app.core.storage.PrivateStateStore
import com.xtv.app.core.storage.PrivateStateV2
import com.xtv.app.core.storage.ProvisionedCredentials
import com.xtv.app.core.storage.StateEnvelopeCodec
import com.xtv.app.core.storage.StorageFailure
import com.xtv.app.core.storage.StoredSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The purchase machine driven through the **real** envelope: real adapter, real store, real codec,
 * real semantic validation. Only the ciphertext and the network are faked.
 *
 * Every other purchase test uses [InMemoryPurchaseStatePort], which validates nothing. That gap is
 * not academic: a release build once shipped in which the ledger period key — a month stamped with
 * the rate-card version — could not satisfy the envelope's own month validator, so *every*
 * purchase-side write threw. The app persisted nothing at all, offered nothing, and never got past
 * "Setup needed", while the entire suite stayed green.
 *
 * Anything asserted here has to survive encode, validate, decrypt and decode.
 */
class PurchasePersistenceTest {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        appScope.cancel()
    }

    @Test
    fun `a fresh install becomes ready and offers all three sizes`() = runBlocking {
        val purchase = purchase(provisioned())

        val ready = purchase.awaitReady()

        assertEquals(PurchaseReadiness.READY, ready.readiness)
        assertEquals(listOf(30, 60, 100), ready.offers.map { it.requestedPosts })
    }

    @Test
    fun `an unprovisioned install still reaches ready`() = runBlocking {
        // No credentials yet. Reaching a readable, offerable state must not depend on having them,
        // or the setup screen becomes unreachable-by-way-of-storage-failure.
        val purchase = purchase(PrivateStateV2())

        assertEquals(PurchaseReadiness.READY, purchase.awaitReady().readiness)
    }

    @Test
    fun `a completed purchase round-trips through the encrypted envelope`() = runBlocking {
        val store = store(provisioned())
        val purchase = purchase(store = store)
        val token = purchase.awaitReady().offers.first().token

        purchase.dispatch(PurchaseCommand.Buy(token))
        purchase.awaitTerminal()

        // Read back through the codec, not from memory.
        val state = (store.read() as PrivateStateRead.Ready).state
        assertEquals("bought", state.reel?.reelId)
        assertEquals("101", state.cursor?.sinceId)
        assertEquals(1, state.reel?.itemCount)
        assertNotNull(state.ledger.lastReceipt)
        assertEquals(30, state.ledger.lastReceipt?.requestedPosts)
        // The slot is released, so the next launch can offer again.
        assertEquals(null, state.ledger.pendingOperation)
    }

    @Test
    fun `a crashed PREPARED purchase releases its slot on the next launch`() = runBlocking {
        // Left stuck, this is unrecoverable from the remote: no offers, every Buy refused as busy,
        // reprovisioning refused, and both reset paths throwing.
        val store = store(provisioned())
        PrivateStatePurchasePort(store).update {
            it.copy(
                pending = DurablePurchase(
                    id = OperationId("crashed"),
                    token = OfferToken("stale"),
                    requestedPosts = 30,
                    quote = RateCard.current().quote(30, identityLookupNeeded = false),
                    stage = DurableStage.PREPARED,
                    startedAtMs = 1,
                ),
            )
        }

        val ready = purchase(store = store).awaitReady()

        assertEquals(3, ready.offers.size)
        assertEquals(null, (store.read() as PrivateStateRead.Ready).state.ledger.pendingOperation)
    }

    /**
     * Visible offers must be buyable, with no gap in between.
     *
     * Routing the startup read through the reload path once made this fail: that path holds the
     * operation claim until its caller's `finally`, which runs *after* the snapshot already says
     * READY with offers on it. Pressing an offer inside that window came back busy — on screen, a
     * card that does nothing. Three contract tests caught it only on a slow CI runner, so this
     * asserts the invariant directly rather than leaving it to a race.
     */
    @Test
    fun `an offer is buyable the instant it becomes visible`() = runBlocking {
        repeat(25) {
            val purchase = purchase(provisioned())
            val token = purchase.awaitReady().offers.first().token

            val result = purchase.dispatch(PurchaseCommand.Buy(token))

            assertTrue("attempt $it was refused: $result", result is DispatchResult.Accepted)
            purchase.awaitTerminal()
        }
    }

    @Test
    fun `an unreadable envelope advances the revision so the failure is observable`() = runBlocking {
        // The revision is what observers key their reconciliation on. Published under a stale one,
        // a storage failure is a failure nobody is ever told about.
        val purchase = purchase(store = PrivateStateStore(UnreadableBackend(), EmptyLegacyStateSource))

        val snapshot = withTimeout(5_000) {
            purchase.state.first { it.readiness == PurchaseReadiness.PRIVATE_STATE_UNAVAILABLE }
        }

        assertTrue(snapshot.revision > 0)
    }

    private fun provisioned() = PrivateStateV2(
        credentials = ProvisionedCredentials("client", "bearer"),
        session = StoredSession("access", "refresh", Long.MAX_VALUE),
    )

    private fun store(initial: PrivateStateV2) =
        PrivateStateStore(MemoryBackend(StateEnvelopeCodec.encode(initial)), EmptyLegacyStateSource)

    private fun purchase(
        initial: PrivateStateV2 = PrivateStateV2(),
        store: PrivateStateStore = store(initial),
    ): ReelPurchase = DefaultReelPurchase(
        applicationScope = appScope,
        store = PrivateStatePurchasePort(store),
        session = object : PurchaseSessionPort {
            override suspend fun session(beforeIdentityLookup: suspend () -> Boolean) =
                SessionOutcome.Ready("access", "account-1", identityLookupCharged = false)
        },
        timeline = object : TimelineReadPort {
            override suspend fun read(request: TimelineRequest) = TimelineOutcome.Success(
                items = listOf(
                    MediaItem(
                        id = "one",
                        kind = MediaKind.VIDEO,
                        indexInPost = 0,
                        countInPost = 1,
                        displayUrl = "https://example.invalid/one.mp4",
                        width = 1920,
                        height = 1080,
                        author = Author("author", "author", "Author"),
                        text = "",
                        createdAtMs = 0,
                    ),
                ),
                newestPostId = "101",
                resources = ResourceCounts(posts = request.requestedPosts, media = 1),
            )
        },
        usage = object : ProjectUsagePort {
            override suspend fun refresh(): ProjectPostUsage? = null
        },
        clock = FixedPurchaseClock(1_000),
        reelIdFactory = { "bought" },
    )

    private suspend fun ReelPurchase.awaitReady(): PurchaseSnapshot = withTimeout(5_000) {
        state.first { it.readiness == PurchaseReadiness.READY && it.offers.isNotEmpty() }
    }

    private suspend fun ReelPurchase.awaitTerminal(): PurchaseSnapshot = withTimeout(5_000) {
        state.first {
            it.operation is PurchaseOperation.Finished ||
                it.operation is PurchaseOperation.Failed ||
                it.operation is PurchaseOperation.Interrupted
        }
    }
}

private class MemoryBackend(initial: ByteArray?) : EncryptedEnvelopeBackend {
    private var bytes = initial

    override suspend fun read(): EnvelopeRead =
        bytes?.let(EnvelopeRead::Present) ?: EnvelopeRead.Missing

    override suspend fun write(plaintext: ByteArray): StorageFailure? {
        bytes = plaintext
        return null
    }

    override suspend fun clear(): StorageFailure? {
        bytes = null
        return null
    }
}

/** Ciphertext is present but its key is gone — the shape key loss actually takes. */
private class UnreadableBackend : EncryptedEnvelopeBackend {
    override suspend fun read(): EnvelopeRead = EnvelopeRead.Failed(StorageFailure.KeyUnavailable)
    override suspend fun write(plaintext: ByteArray): StorageFailure? = StorageFailure.KeyUnavailable
    override suspend fun clear(): StorageFailure? = null
}
