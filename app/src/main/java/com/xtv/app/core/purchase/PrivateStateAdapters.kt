package com.xtv.app.core.purchase

import com.xtv.app.core.playback.CheckpointResult
import com.xtv.app.core.playback.PlaybackCheckpoint
import com.xtv.app.core.storage.AccountBinding
import com.xtv.app.core.storage.CachedUsageState
import com.xtv.app.core.storage.CursorState
import com.xtv.app.core.storage.LedgerState
import com.xtv.app.core.storage.OperationPhase
import com.xtv.app.core.storage.PendingOperation
import com.xtv.app.core.storage.PrivateStateRead
import com.xtv.app.core.storage.PrivateStateStore
import com.xtv.app.core.storage.PrivateStateUnavailableException
import com.xtv.app.core.storage.ReelState
import com.xtv.app.core.storage.StoredPurchaseReceipt
import com.xtv.app.core.storage.StoredResourceCounts
import com.xtv.app.core.storage.StoredTerminalOperation
import com.xtv.app.data.MediaItemCodec
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Purchase projection over the single encrypted envelope. Every reducer transform remains one
 * `PrivateStateStore.update`, so ledger/cursor/reel/session ordering cannot split across files.
 */
class PrivateStatePurchasePort(
    private val privateState: PrivateStateStore,
    private val rateCard: RateCard = RateCard.current(),
) : PurchaseStatePort {
    override suspend fun read(): PurchaseRecord = when (val loaded = privateState.read()) {
        is PrivateStateRead.Ready -> loaded.state.toPurchaseRecord(rateCard)
        is PrivateStateRead.Unavailable -> throw PrivateStateUnavailableException(loaded.failure)
    }

    override suspend fun update(transform: (PurchaseRecord) -> PurchaseRecord): PurchaseRecord {
        val updated = privateState.update { envelope ->
            val before = envelope.toPurchaseRecord(rateCard)
            val transformed = transform(before)
            val after =
                if (
                    envelope.provisionCandidate != null &&
                    before.pending == null &&
                    transformed.pending != null
                ) {
                    // Provisioning and a paid request must never overlap. Whichever durable journal
                    // entry lands first owns the slot; this adapter refuses a later PREPARED write.
                    before
                } else {
                    transformed
                }
            envelope.withPurchaseRecord(after)
        }
        return updated.toPurchaseRecord(rateCard)
    }
}

/**
 * Ordered asynchronous playback writes. A single consumer prevents a slow earlier DataStore write
 * from landing after a later transition; each write also compares the immutable reel id.
 */
class PrivateStatePlaybackCheckpoint(
    private val privateState: PrivateStateStore,
    scope: CoroutineScope,
) : PlaybackCheckpoint {
    private data class Request(
        val reelId: String,
        val nextIndex: Int,
        val complete: (CheckpointResult) -> Unit,
    )

    private val requests = Channel<Request>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (request in requests) {
                val result = runCatching {
                    var matched = false
                    privateState.update { state ->
                        val reel = state.reel
                        if (reel?.reelId != request.reelId) {
                            state
                        } else {
                            matched = true
                            val next = request.nextIndex.coerceIn(0, reel.itemCount)
                            state.copy(
                                reel = reel.copy(
                                    nextIndex = next,
                                    status = when {
                                        next == reel.itemCount ->
                                            com.xtv.app.core.storage.ReelStatus.COMPLETED
                                        reel.partial ->
                                            com.xtv.app.core.storage.ReelStatus.PARTIAL
                                        else -> com.xtv.app.core.storage.ReelStatus.READY
                                    },
                                ),
                            )
                        }
                    }
                    if (matched) CheckpointResult.Stored else CheckpointResult.StaleReel
                }.getOrElse {
                    CheckpointResult.Failed(it.message ?: it.javaClass.simpleName)
                }
                request.complete(result)
            }
        }
    }

    override fun save(
        reelId: String,
        nextIndex: Int,
        onComplete: (CheckpointResult) -> Unit,
    ) {
        if (requests.trySend(Request(reelId, nextIndex, onComplete)).isFailure) {
            onComplete(CheckpointResult.Failed("checkpoint queue is closed"))
        }
    }
}

private fun com.xtv.app.core.storage.PrivateStateV2.toPurchaseRecord(
    rateCard: RateCard,
): PurchaseRecord {
    val decodedReel = reel?.toPurchasedReel()
    val decodedReceipt = ledger.lastReceipt?.toReceipt()
    return PurchaseRecord(
        pending = ledger.pendingOperation?.toDurablePurchase(
            rateCard = rateCard,
            identityLookupNeeded = account == null,
        ),
        cursor = cursor?.sinceId,
        currentReel = decodedReel,
        lastReceipt = decodedReceipt,
        terminal = ledger.terminalOperation?.toTerminal(decodedReceipt, decodedReel),
        cachedUsage = cachedUsage?.let {
            ProjectPostUsage(
                posts = it.posts,
                resetDay = it.resetDay,
                observedAtMs = it.fetchedAtMs,
                projectScope = account?.projectKey ?: credentials?.clientId,
            )
        },
        accountId = account?.accountId,
        accountScope = account?.accountId,
        projectScope = account?.projectKey ?: credentials?.clientId,
        provisioningInFlight = provisionCandidate != null,
    )
}

private fun com.xtv.app.core.storage.PrivateStateV2.withPurchaseRecord(
    record: PurchaseRecord,
): com.xtv.app.core.storage.PrivateStateV2 {
    val projectKey = record.projectScope ?: credentials?.clientId.orEmpty()
    return copy(
        account = record.accountId?.let { AccountBinding(it, projectKey) } ?: account,
        reel = record.currentReel?.toStoredReel(),
        cursor = record.cursor?.let(::CursorState),
        ledger = LedgerState(
            pendingOperation = record.pending?.toPendingOperation(),
            lastReceipt = record.lastReceipt?.toStoredReceipt(),
            terminalOperation = record.terminal?.toStoredTerminal(),
        ),
        cachedUsage = record.cachedUsage?.let {
            CachedUsageState(it.posts, it.resetDay, it.observedAtMs)
        },
    )
}

private fun ReelState.toPurchasedReel(): PurchasedReel {
    val items = MediaItemCodec.decode(itemsJson)
    if (itemCount != 0 && itemCount != items.size) {
        error("encrypted reel item count mismatch")
    }
    if (nextIndex !in 0..items.size) error("encrypted reel index is out of bounds")
    return PurchasedReel(
        id = reelId.ifBlank { legacyReelId(items.map { it.id }) },
        items = items,
        status = if (status == com.xtv.app.core.storage.ReelStatus.COMPLETED) {
            ReelStatus.COMPLETED
        } else {
            ReelStatus.IN_PROGRESS
        },
        nextIndex = nextIndex,
        createdAtMs = savedAtMs,
        partial = partial || status == com.xtv.app.core.storage.ReelStatus.PARTIAL,
    )
}

private fun PurchasedReel.toStoredReel() = ReelState(
    reelId = id,
    itemsJson = MediaItemCodec.encode(items),
    nextIndex = nextIndex,
    savedAtMs = createdAtMs,
    itemCount = items.size,
    status = when {
        status == ReelStatus.COMPLETED -> com.xtv.app.core.storage.ReelStatus.COMPLETED
        partial -> com.xtv.app.core.storage.ReelStatus.PARTIAL
        else -> com.xtv.app.core.storage.ReelStatus.READY
    },
    partial = partial,
)

private fun PendingOperation.toDurablePurchase(
    rateCard: RateCard,
    identityLookupNeeded: Boolean,
): DurablePurchase {
    val base = rateCard.quote(requestedPosts, identityLookupNeeded)
    return DurablePurchase(
        id = OperationId(operationId),
        token = OfferToken(offerToken),
        requestedPosts = requestedPosts,
        quote = base.copy(
            knownEstimate = UsdMicros(knownEstimateUsdMicros),
            reservation = UsdMicros(reservedUsdMicros),
            rateCardVersion = rateCardVersion.ifBlank { base.rateCardVersion },
        ),
        stage = when (phase) {
            OperationPhase.PREPARED -> DurableStage.PREPARED
            OperationPhase.IDENTITY_DISPATCHED -> DurableStage.IDENTITY_DISPATCHED
            OperationPhase.TIMELINE_DISPATCHED -> DurableStage.TIMELINE_DISPATCHED
            OperationPhase.DISPATCHED -> DurableStage.DISPATCHED
        },
        startedAtMs = startedAtMs,
        identityExposure = UsdMicros(identityExposureUsdMicros),
    )
}

private fun DurablePurchase.toPendingOperation() = PendingOperation(
    operationId = id.value,
    phase = when (stage) {
        DurableStage.PREPARED -> OperationPhase.PREPARED
        DurableStage.IDENTITY_DISPATCHED -> OperationPhase.IDENTITY_DISPATCHED
        DurableStage.TIMELINE_DISPATCHED -> OperationPhase.TIMELINE_DISPATCHED
        DurableStage.DISPATCHED -> OperationPhase.DISPATCHED
    },
    reservedUsdMicros = quote.reservation.value,
    requestedPosts = requestedPosts,
    offerToken = token.value,
    knownEstimateUsdMicros = quote.knownEstimate.value,
    rateCardVersion = quote.rateCardVersion,
    startedAtMs = startedAtMs,
    identityExposureUsdMicros = identityExposure.value,
)

private fun StoredPurchaseReceipt.toReceipt() = PurchaseReceipt(
    operationId = OperationId(operationId),
    requestedPosts = requestedPosts,
    resources = resources.toDomain(),
    estimatedCharge = UsdMicros(estimatedChargeUsdMicros),
    reservation = UsdMicros(reservationUsdMicros),
    rateCardVersion = rateCardVersion,
    accountingCertainty = enumValueOrDefault(accountingCertainty, AccountingCertainty.REQUEST_OUTCOME_UNKNOWN),
    cursorAdvanced = cursorAdvanced,
    warnings = warnings.mapNotNull { runCatching { PurchaseWarning.valueOf(it) }.getOrNull() }.toSet(),
    completedAtMs = completedAtMs,
)

private fun PurchaseReceipt.toStoredReceipt() = StoredPurchaseReceipt(
    operationId = operationId.value,
    requestedPosts = requestedPosts,
    resources = resources.toStored(),
    estimatedChargeUsdMicros = estimatedCharge.value,
    reservationUsdMicros = reservation.value,
    rateCardVersion = rateCardVersion,
    accountingCertainty = accountingCertainty.name,
    cursorAdvanced = cursorAdvanced,
    warnings = warnings.map { it.name }.sorted(),
    completedAtMs = completedAtMs,
)

private fun StoredResourceCounts.toDomain() =
    ResourceCounts(posts, users, media, identityLookups)

private fun ResourceCounts.toStored() =
    StoredResourceCounts(posts, users, media, identityLookups)

private fun StoredTerminalOperation.toTerminal(
    receipt: PurchaseReceipt?,
    reel: PurchasedReel?,
): DurableTerminal = when (kind) {
    "FINISHED" -> {
        val storedReceipt = receipt ?: error("finished operation has no receipt")
        val purchaseOutcome = when (outcome) {
            "REEL_READY" -> PurchaseOutcome.ReelReady(
                reel?.summary() ?: error("ready operation has no reel"),
            )
            "NO_PLAYABLE_VIDEO" -> PurchaseOutcome.NoPlayableVideo
            "PARTIAL_REEL" -> PurchaseOutcome.PartialReel(reel?.summary())
            else -> error("unknown terminal outcome")
        }
        DurableTerminal.Finished(OperationId(operationId), purchaseOutcome, storedReceipt)
    }
    "FAILED" -> DurableTerminal.Failed(
        OperationId(operationId),
        decodeProblem(problem, resetAtMs),
    )
    "INTERRUPTED" -> DurableTerminal.Interrupted(
        OperationId(operationId),
        UsdMicros(conservativelyCommittedUsdMicros),
    )
    else -> error("unknown terminal operation")
}

private fun DurableTerminal.toStoredTerminal(): StoredTerminalOperation = when (this) {
    is DurableTerminal.Finished -> StoredTerminalOperation(
        operationId = id.value,
        kind = "FINISHED",
        outcome = when (outcome) {
            is PurchaseOutcome.ReelReady -> "REEL_READY"
            PurchaseOutcome.NoPlayableVideo -> "NO_PLAYABLE_VIDEO"
            is PurchaseOutcome.PartialReel -> "PARTIAL_REEL"
        },
        reelId = when (outcome) {
            is PurchaseOutcome.ReelReady -> outcome.reel.id
            is PurchaseOutcome.PartialReel -> outcome.reel?.id
            PurchaseOutcome.NoPlayableVideo -> null
        },
    )
    is DurableTerminal.Failed -> StoredTerminalOperation(
        operationId = id.value,
        kind = "FAILED",
        problem = encodeProblem(problem),
        resetAtMs = (problem as? PurchaseProblem.RateLimited)?.resetAtMs,
    )
    is DurableTerminal.Interrupted -> StoredTerminalOperation(
        operationId = id.value,
        kind = "INTERRUPTED",
        conservativelyCommittedUsdMicros = conservativelyCommitted.value,
    )
}

private fun encodeProblem(problem: PurchaseProblem): String = when (problem) {
    PurchaseProblem.SetupRequired -> "SETUP_REQUIRED"
    PurchaseProblem.AuthenticationRequired -> "AUTHENTICATION_REQUIRED"
    PurchaseProblem.PaymentRequired -> "PAYMENT_REQUIRED"
    is PurchaseProblem.RateLimited -> "RATE_LIMITED"
    PurchaseProblem.Network -> "NETWORK"
    PurchaseProblem.UpstreamContractChanged -> "UPSTREAM_CONTRACT_CHANGED"
    PurchaseProblem.StorageUnavailable -> "STORAGE_UNAVAILABLE"
    PurchaseProblem.Busy -> "BUSY"
    PurchaseProblem.StaleOffer -> "STALE_OFFER"
    PurchaseProblem.Unexpected -> "UNEXPECTED"
}

private fun decodeProblem(value: String?, resetAtMs: Long?): PurchaseProblem = when (value) {
    "SETUP_REQUIRED" -> PurchaseProblem.SetupRequired
    "AUTHENTICATION_REQUIRED" -> PurchaseProblem.AuthenticationRequired
    "PAYMENT_REQUIRED" -> PurchaseProblem.PaymentRequired
    "RATE_LIMITED" -> PurchaseProblem.RateLimited(resetAtMs)
    "NETWORK" -> PurchaseProblem.Network
    "UPSTREAM_CONTRACT_CHANGED" -> PurchaseProblem.UpstreamContractChanged
    "STORAGE_UNAVAILABLE" -> PurchaseProblem.StorageUnavailable
    "BUSY" -> PurchaseProblem.Busy
    "STALE_OFFER" -> PurchaseProblem.StaleOffer
    else -> PurchaseProblem.Unexpected
}

private fun legacyReelId(ids: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(ids.joinToString("|").encodeToByteArray())
    return "legacy-" + digest.take(8).joinToString("") { "%02x".format(it) }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
