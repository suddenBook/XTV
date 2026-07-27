package com.xtv.app.core.purchase

import com.xtv.app.core.model.MediaItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DurableStage {
    PREPARED,
    /** A possibly billed `/users/me` request, but no timeline request, may have left the device. */
    IDENTITY_DISPATCHED,
    /** The paid timeline request may have left the device. */
    TIMELINE_DISPATCHED,
    /** Backward-compatible legacy value; recover as [TIMELINE_DISPATCHED]. */
    DISPATCHED,
}

data class DurablePurchase(
    val id: OperationId,
    val token: OfferToken,
    val requestedPosts: Int,
    val quote: ChargeEstimate,
    val stage: DurableStage,
    val startedAtMs: Long,
    /** Exact rate-card amount exposed by an ambiguous identity lookup. */
    val identityExposure: UsdMicros = UsdMicros.ZERO,
)

sealed interface DurableTerminal {
    val id: OperationId

    data class Finished(
        override val id: OperationId,
        val outcome: PurchaseOutcome,
        val receipt: PurchaseReceipt,
    ) : DurableTerminal

    data class Failed(override val id: OperationId, val problem: PurchaseProblem) : DurableTerminal
    data class Interrupted(
        override val id: OperationId,
        val conservativelyCommitted: UsdMicros,
    ) : DurableTerminal
}

/**
 * The purchase-owned projection of the encrypted envelope. Production updates must be a single
 * DataStore transaction; [InMemoryPurchaseStatePort] gives tests the same semantics.
 */
data class PurchaseRecord(
    val pending: DurablePurchase? = null,
    val cursor: String? = null,
    val currentReel: PurchasedReel? = null,
    val lastReceipt: PurchaseReceipt? = null,
    val terminal: DurableTerminal? = null,
    val cachedUsage: ProjectPostUsage? = null,
    val accountId: String? = null,
    val accountScope: String? = null,
    val projectScope: String? = null,
    /** A durable provisioning candidate owns the network/account mutation slot. */
    val provisioningInFlight: Boolean = false,
)

interface PurchaseStatePort {
    suspend fun read(): PurchaseRecord
    suspend fun update(transform: (PurchaseRecord) -> PurchaseRecord): PurchaseRecord
}

class InMemoryPurchaseStatePort(initial: PurchaseRecord) : PurchaseStatePort {
    private val mutex = Mutex()
    private var value = initial

    override suspend fun read(): PurchaseRecord = mutex.withLock { value }

    override suspend fun update(transform: (PurchaseRecord) -> PurchaseRecord): PurchaseRecord =
        mutex.withLock {
            transform(value).also { value = it }
        }
}

sealed interface SessionOutcome {
    data class Ready(
        /** Internal-only; never copied into snapshots, receipts, or diagnostics. */
        val accessToken: String,
        val accountId: String,
        val identityLookupCharged: Boolean,
    ) : SessionOutcome

    data class Failure(
        val problem: PurchaseProblem,
        /** True when a billed identity read may have left the device. */
        val possiblyCharged: Boolean = false,
    ) : SessionOutcome
}

interface PurchaseSessionPort {
    /**
     * Refreshes single-flight, persists any rotated token before returning, then resolves account ID
     * only if it is not already cached. Construction and state observation never invoke this.
     */
    suspend fun session(beforeIdentityLookup: suspend () -> Boolean = { true }): SessionOutcome
}

data class TimelineRequest(
    val accessToken: String,
    val accountId: String,
    val requestedPosts: Int,
    val sinceId: String?,
)

enum class RequestSentState { NOT_SENT, POSSIBLY_SENT }

sealed interface TimelineOutcome {
    data class Success(
        val items: List<MediaItem>,
        val newestPostId: String?,
        val resources: ResourceCounts,
        val warnings: Set<PurchaseWarning> = emptySet(),
    ) : TimelineOutcome

    data class Partial(
        val items: List<MediaItem>,
        val newestPostId: String?,
        val resources: ResourceCounts,
        val problem: PurchaseProblem,
        /** Whether a later page may have returned billable resources whose response was lost. */
        val sentState: RequestSentState = RequestSentState.NOT_SENT,
        val warnings: Set<PurchaseWarning> = emptySet(),
    ) : TimelineOutcome

    data class Failure(
        val problem: PurchaseProblem,
        val sentState: RequestSentState,
    ) : TimelineOutcome
}

interface TimelineReadPort {
    suspend fun read(request: TimelineRequest): TimelineOutcome
}

interface ProjectUsagePort {
    suspend fun refresh(): ProjectPostUsage?
}

interface PurchaseClock {
    fun nowMs(): Long
}

class FixedPurchaseClock(private val now: Long) : PurchaseClock {
    override fun nowMs(): Long = now
}
