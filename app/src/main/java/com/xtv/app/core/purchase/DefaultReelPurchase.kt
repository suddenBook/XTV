package com.xtv.app.core.purchase

import com.xtv.app.core.model.motionOnly
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DefaultReelPurchase(
    private val applicationScope: CoroutineScope,
    private val store: PurchaseStatePort,
    private val session: PurchaseSessionPort,
    private val timeline: TimelineReadPort,
    private val usage: ProjectUsagePort,
    private val clock: PurchaseClock = SystemPurchaseClock,
    private val rateCard: RateCard = RateCard.current(),
    private val idFactory: () -> OperationId = { OperationId(UUID.randomUUID().toString()) },
    private val reelIdFactory: () -> String = { UUID.randomUUID().toString() },
) : ReelPurchase {

    private data class Active(val id: OperationId, val token: OfferToken?)

    private val offerPolicy = OfferPolicy(rateCard)
    private val revision = AtomicLong(0)
    private val active = AtomicReference<Active?>(null)
    private val reloadRequested = AtomicBoolean(false)

    /**
     * Serializes the initial read against any later reload.
     *
     * Both run on the default dispatcher, so without this an unclaimed startup read can finish
     * after a reload triggered by provisioning and publish the older state over the newer one.
     * A mutex rather than the operation claim: this must order two reads, not make the app look
     * busy to someone pressing a button.
     */
    private val loadMutex = Mutex()
    private val _state = MutableStateFlow(loadingSnapshot())

    override val state: StateFlow<PurchaseSnapshot> = _state.asStateFlow()

    init {
        // Deliberately not through dispatchLocalReload(). That takes the `active` claim, and the
        // claim outlives the publish that makes offers visible — releasing it happens in the
        // caller's `finally`, after state already says READY. Pressing an offer in that window is
        // rejected as busy, which on screen is a card that does nothing. Ordering against a
        // concurrent reload is handled by loadMutex instead, which costs no such window.
        applicationScope.launch { initializeFromLocalState() }
    }

    override fun dispatch(command: PurchaseCommand): DispatchResult = when (command) {
        is PurchaseCommand.Buy -> dispatchBuy(command.token)
        is PurchaseCommand.Acknowledge -> dispatchAcknowledge(command.operationId)
        PurchaseCommand.RefreshProjectUsage -> dispatchUsageRefresh()
        PurchaseCommand.SyncProjectUsage -> dispatchUsageSync()
        PurchaseCommand.ReloadLocalState -> dispatchLocalReload()
    }

    private fun dispatchUsageSync(): DispatchResult {
        if (state.value.readiness != PurchaseReadiness.READY) {
            return DispatchResult.Rejected(Rejection.NotReady)
        }
        val id = idFactory()
        applicationScope.launch { syncUsageQuietly() }
        return DispatchResult.Accepted(id)
    }

    private fun dispatchBuy(token: OfferToken): DispatchResult {
        active.get()?.let { running ->
            return if (running.token == token) {
                DispatchResult.AlreadyAccepted(running.id)
            } else {
                DispatchResult.Rejected(Rejection.Busy)
            }
        }
        val snapshot = state.value
        if (snapshot.readiness != PurchaseReadiness.READY) {
            return DispatchResult.Rejected(
                if (snapshot.readiness == PurchaseReadiness.PRIVATE_STATE_UNAVAILABLE) {
                    Rejection.StorageUnavailable
                } else {
                    Rejection.NotReady
                },
            )
        }
        if (snapshot.operation !is PurchaseOperation.Idle) {
            return DispatchResult.Rejected(Rejection.NotReady)
        }
        val offer = snapshot.offers.firstOrNull { it.token == token }
            ?: return DispatchResult.Rejected(Rejection.StaleOffer)

        val id = idFactory()
        val claim = Active(id, token)
        if (!active.compareAndSet(null, claim)) {
            val running = active.get()
            return if (running?.token == token) {
                DispatchResult.AlreadyAccepted(running.id)
            } else {
                DispatchResult.Rejected(Rejection.Busy)
            }
        }

        _state.value = snapshot.copy(
            offers = emptyList(),
            operation = PurchaseOperation.Running(id, token, offer.requestedPosts, PurchaseStage.PREPARING),
        )
        applicationScope.launch { runPurchase(claim, offer) }
        return DispatchResult.Accepted(id)
    }

    private fun dispatchAcknowledge(id: OperationId): DispatchResult {
        val terminal = state.value.operation
        val terminalId = when (terminal) {
            is PurchaseOperation.Finished -> terminal.id
            is PurchaseOperation.Failed -> terminal.id
            is PurchaseOperation.Interrupted -> terminal.id
            else -> return DispatchResult.Rejected(Rejection.NotReady)
        }
        if (terminalId != id) return DispatchResult.Rejected(Rejection.StaleOffer)
        val claim = Active(id, null)
        if (!active.compareAndSet(null, claim)) return DispatchResult.Rejected(Rejection.Busy)
        applicationScope.launch {
            try {
                val record = store.update {
                    if (it.terminal?.id == id) it.copy(terminal = null) else it
                }
                publish(record, PurchaseOperation.Idle)
            } catch (_: Throwable) {
                publishStorageFailure(id)
            } finally {
                completeClaim(claim)
            }
        }
        return DispatchResult.Accepted(id)
    }

    private fun dispatchUsageRefresh(): DispatchResult {
        val snapshot = state.value
        if (snapshot.readiness != PurchaseReadiness.READY || snapshot.operation !is PurchaseOperation.Idle) {
            return DispatchResult.Rejected(Rejection.NotReady)
        }
        val id = idFactory()
        val claim = Active(id, null)
        if (!active.compareAndSet(null, claim)) return DispatchResult.Rejected(Rejection.Busy)
        _state.value = snapshot.copy(
            operation = PurchaseOperation.Running(id, null, null, PurchaseStage.REFRESHING_USAGE),
        )
        applicationScope.launch {
            try {
                val refreshed = try {
                    usage.refresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    val local = try {
                        store.read()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        publishStorageFailure(id)
                        return@launch
                    }
                    publish(local, PurchaseOperation.Failed(id, PurchaseProblem.Network))
                    return@launch
                }
                val record = if (refreshed == null) {
                    store.read()
                } else {
                    store.update {
                        if (
                            refreshed.projectScope != null &&
                            refreshed.projectScope != it.projectScope
                        ) {
                            it
                        } else {
                            it.copy(cachedUsage = refreshed)
                        }
                    }
                }
                publish(record, PurchaseOperation.Idle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                publishStorageFailure(id)
            } finally {
                completeClaim(claim)
            }
        }
        return DispatchResult.Accepted(id)
    }

    private fun dispatchLocalReload(): DispatchResult {
        val id = idFactory()
        val claim = Active(id, null)
        if (!active.compareAndSet(null, claim)) {
            reloadRequested.set(true)
            return DispatchResult.AlreadyAccepted(active.get()?.id ?: id)
        }
        _state.value = _state.value.copy(
            readiness = PurchaseReadiness.LOADING_LOCAL_STATE,
            offers = emptyList(),
            operation = PurchaseOperation.Running(id, null, null, PurchaseStage.PREPARING),
        )
        applicationScope.launch {
            try {
                initializeFromLocalState()
            } finally {
                completeClaim(claim)
            }
        }
        return DispatchResult.Accepted(id)
    }

    /**
     * Reads local state, and writes only when a crashed purchase has to be resolved.
     *
     * This used to open a write transaction unconditionally, because it stamped the current ledger
     * month on every launch. Nothing needs writing on an ordinary start now, and since a playback
     * checkpoint or a provisioning commit can land between the read and any write, the recovery
     * decision is re-taken *inside* the transaction rather than carried across from the read.
     */
    private suspend fun initializeFromLocalState() = loadMutex.withLock {
        try {
            val loaded = store.read()
            if (loaded.pending == null) {
                publish(loaded, operationFrom(loaded.terminal))
                return
            }
            var recovered: DurableTerminal.Interrupted? = null
            val record = store.update { original ->
                val pending = original.pending ?: return@update original
                when (pending.stage) {
                    // Journalled, but nothing was ever dispatched. Releasing the slot is the whole
                    // recovery: leaving it would refuse every future purchase, every reprovision
                    // and both reset paths, with no way back.
                    DurableStage.PREPARED -> original.copy(pending = null)
                    DurableStage.IDENTITY_DISPATCHED,
                    DurableStage.TIMELINE_DISPATCHED,
                    DurableStage.DISPATCHED,
                    -> {
                        val exposure = when (pending.stage) {
                            DurableStage.IDENTITY_DISPATCHED -> pending.identityExposure
                            DurableStage.TIMELINE_DISPATCHED,
                            DurableStage.DISPATCHED,
                            -> pending.quote.reservation
                            DurableStage.PREPARED -> UsdMicros.ZERO
                        }
                        val interrupted = DurableTerminal.Interrupted(pending.id, exposure)
                        recovered = interrupted
                        original.copy(pending = null, terminal = interrupted)
                    }
                }
            }
            publish(record, operationFrom(record.terminal ?: recovered))
        } catch (_: Throwable) {
            publishStorageFailure(null)
        }
    }

    private suspend fun runPurchase(claim: Active, offer: ReelOffer) {
        var exposure = DurableStage.PREPARED
        try {
            val durable = DurablePurchase(
                id = claim.id,
                token = offer.token,
                requestedPosts = offer.requestedPosts,
                quote = offer.charge,
                stage = DurableStage.PREPARED,
                startedAtMs = clock.nowMs(),
                identityExposure =
                    if (offer.accountScope == null) rateCard.userRead else UsdMicros.ZERO,
            )
            var staleOffer = false
            val prepared = store.update { original ->
                val scopeMatches =
                    offer.charge.rateCardVersion == rateCard.version &&
                        original.accountScope == offer.accountScope &&
                        original.projectScope == offer.projectScope
                if (!scopeMatches) {
                    staleOffer = true
                    original
                } else if (original.pending != null) {
                    // Only a paid request already in flight can block this one. The Developer
                    // Console's hard limit is the authoritative monetary stop.
                    original
                } else {
                    original.copy(pending = durable, terminal = null)
                }
            }
            if (prepared.pending?.id != claim.id) {
                finishFailure(
                    claim.id,
                    when {
                        staleOffer -> PurchaseProblem.StaleOffer
                        prepared.provisioningInFlight -> PurchaseProblem.Busy
                        // The only remaining reason the transaction declined to claim the slot is
                        // that another paid request already holds it.
                        else -> PurchaseProblem.Busy
                    },
                    releaseReservation = true,
                )
                return
            }
            publish(
                prepared,
                PurchaseOperation.Running(
                    claim.id,
                    offer.token,
                    offer.requestedPosts,
                    PurchaseStage.PREPARED,
                ),
            )

            // Once a billable phase is journaled, caller cancellation must not interrupt token
            // rotation, network reconciliation, or the durable terminal record.
            withContext(NonCancellable) {
                executePrepared(claim.id, offer) { exposure = it }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                settleUnexpectedFailure(claim.id, offer, exposure)
            }
            throw cancelled
        } catch (_: Throwable) {
            withContext(NonCancellable) {
                settleUnexpectedFailure(claim.id, offer, exposure)
            }
        } finally {
            completeClaim(claim)
        }
    }

    private suspend fun executePrepared(
        id: OperationId,
        offer: ReelOffer,
        onExposure: (DurableStage) -> Unit,
    ) {
        when (
            val ready = session.session {
                onExposure(DurableStage.IDENTITY_DISPATCHED)
                val marked = markExposure(id, DurableStage.IDENTITY_DISPATCHED)
                val accepted =
                    marked.pending?.id == id &&
                        marked.pending.stage == DurableStage.IDENTITY_DISPATCHED
                if (accepted) publishRunning(marked, id, offer)
                accepted
            }
        ) {
            is SessionOutcome.Failure -> {
                if (ready.possiblyCharged) {
                    finishIdentityAmbiguous(id, offer)
                }
                else finishFailure(id, ready.problem, releaseReservation = true)
            }
            is SessionOutcome.Ready -> {
                val withAccount = store.update { it.copy(accountId = ready.accountId) }
                val identity = if (ready.identityLookupCharged) 1 else 0
                onExposure(DurableStage.TIMELINE_DISPATCHED)
                val marked = markExposure(id, DurableStage.TIMELINE_DISPATCHED)
                if (
                    marked.pending?.id != id ||
                    marked.pending.stage != DurableStage.TIMELINE_DISPATCHED
                ) {
                    if (identity > 0) {
                        finishKnownChargedFailure(
                            id,
                            offer,
                            PurchaseProblem.StorageUnavailable,
                            identity,
                        )
                    } else {
                        finishFailure(
                            id,
                            PurchaseProblem.StorageUnavailable,
                            releaseReservation = true,
                        )
                    }
                    return
                }
                publishRunning(marked, id, offer)
                when (
                    val result = timeline.read(
                        TimelineRequest(
                            accessToken = ready.accessToken,
                            accountId = ready.accountId,
                            requestedPosts = offer.requestedPosts,
                            sinceId = withAccount.cursor,
                        ),
                    )
                ) {
                    is TimelineOutcome.Success -> commitPaidResult(
                        id = id,
                        offer = offer,
                        items = result.items,
                        newestPostId = result.newestPostId,
                        resources = result.resources.copy(
                            identityLookups = result.resources.identityLookups + identity,
                        ),
                        warnings = result.warnings,
                        partial = false,
                    )
                    is TimelineOutcome.Partial -> commitPaidResult(
                        id = id,
                        offer = offer,
                        items = result.items,
                        newestPostId = result.newestPostId,
                        resources = result.resources.copy(
                            identityLookups = result.resources.identityLookups + identity,
                        ),
                        warnings = result.warnings + PurchaseWarning.PARTIAL_PAGE,
                        partial = true,
                        accountingUnknown =
                            result.sentState == RequestSentState.POSSIBLY_SENT,
                    )
                    is TimelineOutcome.Failure -> {
                        if (result.sentState == RequestSentState.POSSIBLY_SENT) {
                            finishAmbiguous(id, offer)
                        } else if (identity > 0) {
                            finishKnownChargedFailure(id, offer, result.problem, identity)
                        } else {
                            finishFailure(id, result.problem, releaseReservation = true)
                        }
                    }
                }
            }
        }
    }

    private suspend fun commitPaidResult(
        id: OperationId,
        offer: ReelOffer,
        items: List<com.xtv.app.core.model.MediaItem>,
        newestPostId: String?,
        resources: ResourceCounts,
        warnings: Set<PurchaseWarning>,
        partial: Boolean,
        accountingUnknown: Boolean = false,
    ) {
        val playable = items.motionOnly()
        val finalWarnings = buildSet {
            addAll(warnings)
            if (resources.posts > offer.requestedPosts) add(PurchaseWarning.SERVER_OVERDELIVERY)
            if (resources.posts > 0 && newestPostId == null) add(PurchaseWarning.MISSING_CURSOR)
            if (accountingUnknown) add(PurchaseWarning.ACCOUNTING_QUANTITY_UNKNOWN)
        }
        val knownCharge = rateCard.charge(resources)
        val committedCharge =
            if (accountingUnknown) maxOf(offer.charge.reservation, knownCharge) else knownCharge
        val receipt = PurchaseReceipt(
            operationId = id,
            requestedPosts = offer.requestedPosts,
            resources = resources,
            estimatedCharge = committedCharge,
            reservation = offer.charge.reservation,
            rateCardVersion = rateCard.version,
            accountingCertainty = if (accountingUnknown) {
                AccountingCertainty.REQUEST_OUTCOME_UNKNOWN
            } else if (partial) {
                AccountingCertainty.PARTIAL_RESPONSE
            } else {
                AccountingCertainty.SETTLED_RESPONSE
            },
            cursorAdvanced = newestPostId != null,
            warnings = finalWarnings,
            completedAtMs = clock.nowMs(),
        )
        var resolvedTerminal: DurableTerminal? = null
        var journalMismatch = false
        val committed = store.update { record ->
            val existing = record.terminal?.takeIf { it.id == id }
            when {
                existing != null -> {
                    resolvedTerminal = existing
                    record
                }
                record.pending?.id != id ||
                    !record.pending.stage.hasTimelineExposure() -> {
                    journalMismatch = true
                    record
                }
                else -> {
                    val reel = if (playable.isEmpty()) {
                        record.currentReel
                    } else {
                        PurchasedReel(
                            id = reelIdFactory(),
                            items = playable,
                            status = ReelStatus.IN_PROGRESS,
                            nextIndex = 0,
                            createdAtMs = clock.nowMs(),
                            partial = partial,
                        )
                    }
                    val outcome = when {
                        playable.isEmpty() -> PurchaseOutcome.NoPlayableVideo
                        partial -> PurchaseOutcome.PartialReel(reel?.summary())
                        else -> PurchaseOutcome.ReelReady(requireNotNull(reel).summary())
                    }
                    val terminal = DurableTerminal.Finished(id, outcome, receipt)
                    resolvedTerminal = terminal
                    record.copy(
                        pending = null,
                        cursor = newestPostId ?: record.cursor,
                        currentReel = reel,
                        lastReceipt = receipt,
                        terminal = terminal,
                    )
                }
            }
        }
        if (journalMismatch) {
            publishStorageFailure(id)
            return
        }
        publish(committed, operationFrom(requireNotNull(resolvedTerminal)))

        // This network call is causally behind the user's paid command and does not delay playback.
        applicationScope.launch { syncUsageQuietly() }
    }

    /**
     * Updates X's project figure without ever becoming an operation.
     *
     * Nobody asked for this, so nobody should be told it is happening or that it failed. Publishing
     * a `Running` state here is what used to make every cold launch show "Getting videos…" over a
     * disabled home screen for the length of two network timeouts, with any button press silently
     * doing nothing. It claims no operation slot and touches exactly one field of the snapshot, so
     * a late response cannot overwrite a newer purchase's state.
     */
    private suspend fun syncUsageQuietly() {
        val refreshed = runCatching { usage.refresh() }.getOrNull() ?: return
        runCatching {
            val updated = store.update {
                if (refreshed.projectScope != null && refreshed.projectScope != it.projectScope) {
                    it
                } else {
                    it.copy(cachedUsage = refreshed)
                }
            }
            _state.update { snapshot -> snapshot.copy(projectUsage = updated.cachedUsage) }
        }
    }

    private suspend fun finishAmbiguous(id: OperationId, offer: ReelOffer) {
        val receipt = PurchaseReceipt(
            operationId = id,
            requestedPosts = offer.requestedPosts,
            resources = ResourceCounts(),
            estimatedCharge = offer.charge.reservation,
            reservation = offer.charge.reservation,
            rateCardVersion = offer.charge.rateCardVersion,
            accountingCertainty = AccountingCertainty.REQUEST_OUTCOME_UNKNOWN,
            cursorAdvanced = false,
            warnings = setOf(
                PurchaseWarning.ACCOUNTING_QUANTITY_UNKNOWN,
            ),
            completedAtMs = clock.nowMs(),
        )
        var resolvedTerminal: DurableTerminal? = null
        var journalMismatch = false
        val committed = store.update { record ->
            val existing = record.terminal?.takeIf { it.id == id }
            when {
                existing != null -> {
                    resolvedTerminal = existing
                    record
                }
                record.pending?.id != id ||
                    !record.pending.stage.hasTimelineExposure() -> {
                    journalMismatch = true
                    record
                }
                else -> {
                    val terminal = DurableTerminal.Interrupted(id, offer.charge.reservation)
                    resolvedTerminal = terminal
                    record.copy(
                        pending = null,
                        lastReceipt = receipt,
                        terminal = terminal,
                    )
                }
            }
        }
        if (journalMismatch) {
            publishStorageFailure(id)
            return
        }
        publish(committed, operationFrom(requireNotNull(resolvedTerminal)))
    }

    private suspend fun finishIdentityAmbiguous(id: OperationId, offer: ReelOffer) {
        val charge = rateCard.userRead
        val receipt = PurchaseReceipt(
            operationId = id,
            requestedPosts = offer.requestedPosts,
            resources = ResourceCounts(identityLookups = 1),
            estimatedCharge = charge,
            reservation = offer.charge.reservation,
            rateCardVersion = offer.charge.rateCardVersion,
            accountingCertainty = AccountingCertainty.REQUEST_OUTCOME_UNKNOWN,
            cursorAdvanced = false,
            warnings = setOf(PurchaseWarning.ACCOUNTING_QUANTITY_UNKNOWN),
            completedAtMs = clock.nowMs(),
        )
        var resolvedTerminal: DurableTerminal? = null
        var journalMismatch = false
        val committed = store.update { record ->
            val existing = record.terminal?.takeIf { it.id == id }
            when {
                existing != null -> {
                    resolvedTerminal = existing
                    record
                }
                record.pending?.id != id ||
                    record.pending.stage == DurableStage.PREPARED -> {
                    journalMismatch = true
                    record
                }
                else -> {
                    val terminal = DurableTerminal.Interrupted(id, charge)
                    resolvedTerminal = terminal
                    record.copy(
                        pending = null,
                        lastReceipt = receipt,
                        terminal = terminal,
                    )
                }
            }
        }
        if (journalMismatch) {
            publishStorageFailure(id)
            return
        }
        publish(committed, operationFrom(requireNotNull(resolvedTerminal)))
    }

    private suspend fun finishKnownChargedFailure(
        id: OperationId,
        offer: ReelOffer,
        problem: PurchaseProblem,
        identityLookups: Int,
    ) {
        val resources = ResourceCounts(identityLookups = identityLookups)
        val charge = rateCard.charge(resources)
        val receipt = PurchaseReceipt(
            operationId = id,
            requestedPosts = offer.requestedPosts,
            resources = resources,
            estimatedCharge = charge,
            reservation = offer.charge.reservation,
            rateCardVersion = offer.charge.rateCardVersion,
            accountingCertainty = AccountingCertainty.SETTLED_RESPONSE,
            cursorAdvanced = false,
            warnings = emptySet(),
            completedAtMs = clock.nowMs(),
        )
        var resolvedTerminal: DurableTerminal? = null
        var journalMismatch = false
        val committed = store.update { record ->
            val existing = record.terminal?.takeIf { it.id == id }
            when {
                existing != null -> {
                    resolvedTerminal = existing
                    record
                }
                record.pending?.id != id ||
                    record.pending.stage == DurableStage.PREPARED -> {
                    journalMismatch = true
                    record
                }
                else -> {
                    val terminal = DurableTerminal.Failed(id, problem)
                    resolvedTerminal = terminal
                    record.copy(
                        pending = null,
                        lastReceipt = receipt,
                        terminal = terminal,
                    )
                }
            }
        }
        if (journalMismatch) {
            publishStorageFailure(id)
            return
        }
        publish(committed, operationFrom(requireNotNull(resolvedTerminal)))
    }

    private suspend fun rollbackPrepared(id: OperationId) {
        runCatching {
            store.update { record ->
                if (record.pending?.id == id && record.pending.stage == DurableStage.PREPARED) {
                    record.copy(pending = null)
                } else {
                    record
                }
            }
        }
    }

    private suspend fun markExposure(
        id: OperationId,
        stage: DurableStage,
    ): PurchaseRecord = store.update { original ->
        val pending = original.pending
        if (pending?.id != id) original else original.copy(pending = pending.copy(stage = stage))
    }

    private fun publishRunning(
        record: PurchaseRecord,
        id: OperationId,
        offer: ReelOffer,
    ) {
        publish(
            record,
            PurchaseOperation.Running(
                id,
                offer.token,
                offer.requestedPosts,
                PurchaseStage.DISPATCHED,
            ),
        )
    }

    private suspend fun settleUnexpectedFailure(
        id: OperationId,
        offer: ReelOffer,
        exposure: DurableStage,
    ) {
        try {
            when (exposure) {
                DurableStage.PREPARED ->
                    finishFailure(
                        id,
                        PurchaseProblem.StorageUnavailable,
                        releaseReservation = true,
                    )
                DurableStage.IDENTITY_DISPATCHED -> finishIdentityAmbiguous(id, offer)
                DurableStage.TIMELINE_DISPATCHED,
                DurableStage.DISPATCHED,
                -> finishAmbiguous(id, offer)
            }
        } catch (_: Throwable) {
            publishStorageFailure(id)
        }
    }

    private suspend fun finishFailure(
        id: OperationId,
        problem: PurchaseProblem,
        releaseReservation: Boolean,
    ) {
        var resolvedTerminal: DurableTerminal? = null
        var journalMismatch = false
        val committed = store.update { record ->
            val existing = record.terminal?.takeIf { it.id == id }
            when {
                existing != null -> {
                    resolvedTerminal = existing
                    record
                }
                record.pending != null && record.pending.id != id -> {
                    journalMismatch = true
                    record
                }
                else -> {
                    val terminal = DurableTerminal.Failed(id, problem)
                    resolvedTerminal = terminal
                    record.copy(
                        pending =
                            if (releaseReservation && record.pending?.id == id) null
                            else record.pending,
                        terminal = terminal,
                    )
                }
            }
        }
        if (journalMismatch) {
            publishStorageFailure(id)
            return
        }
        publish(committed, operationFrom(requireNotNull(resolvedTerminal)))
    }

    private fun publish(record: PurchaseRecord, operation: PurchaseOperation) {
        if (
            operation is PurchaseOperation.Finished ||
            operation is PurchaseOperation.Failed ||
            operation is PurchaseOperation.Interrupted
        ) {
            // Terminal state must never be observable while the just-finished claim still blocks
            // its acknowledgement.
            active.set(null)
        }
        val nextRevision = revision.incrementAndGet()
        _state.value = PurchaseSnapshot(
            revision = nextRevision,
            readiness = PurchaseReadiness.READY,
            offers = if (operation is PurchaseOperation.Idle) {
                offerPolicy.createOffers(record, nextRevision)
            } else {
                emptyList()
            },
            currentReel = record.currentReel?.summary(),
            projectUsage = record.cachedUsage,
            operation = operation,
        )
    }

    /**
     * Local state became unreadable. [id] is null when no operation was in flight — a cold start
     * that could not read its own envelope.
     *
     * The revision must advance. Observers key their reconciliation on it, so a storage failure
     * published under the previous revision is a failure nobody is told about: that is exactly how
     * a build that could not persist a single byte still reached a working-looking home screen.
     * Copying the current snapshot rather than a blank one also keeps the reel summary and usage
     * figures on screen through a transient failure.
     */
    private fun publishStorageFailure(id: OperationId?) {
        active.set(null)
        _state.value = _state.value.copy(
            revision = revision.incrementAndGet(),
            readiness = PurchaseReadiness.PRIVATE_STATE_UNAVAILABLE,
            offers = emptyList(),
            operation = id?.let {
                PurchaseOperation.Failed(it, PurchaseProblem.StorageUnavailable)
            } ?: PurchaseOperation.Idle,
        )
    }

    private fun completeClaim(claim: Active) {
        active.compareAndSet(claim, null)
        drainQueuedReload()
    }

    private fun drainQueuedReload() {
        if (!reloadRequested.compareAndSet(true, false)) return
        val result = dispatchLocalReload()
        if (result is DispatchResult.Rejected) reloadRequested.set(true)
    }

    private fun operationFrom(terminal: DurableTerminal?): PurchaseOperation = when (terminal) {
        null -> PurchaseOperation.Idle
        is DurableTerminal.Finished -> PurchaseOperation.Finished(
            terminal.id,
            terminal.outcome,
            terminal.receipt,
        )
        is DurableTerminal.Failed -> PurchaseOperation.Failed(terminal.id, terminal.problem)
        is DurableTerminal.Interrupted -> PurchaseOperation.Interrupted(
            terminal.id,
            terminal.conservativelyCommitted,
        )
    }

    private fun loadingSnapshot(): PurchaseSnapshot = PurchaseSnapshot(
        revision = 0,
        readiness = PurchaseReadiness.LOADING_LOCAL_STATE,
        offers = emptyList(),
        currentReel = null,
        projectUsage = null,
        operation = PurchaseOperation.Idle,
    )
}

private fun DurableStage.hasTimelineExposure(): Boolean =
    this == DurableStage.TIMELINE_DISPATCHED || this == DurableStage.DISPATCHED

private object SystemPurchaseClock : PurchaseClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
