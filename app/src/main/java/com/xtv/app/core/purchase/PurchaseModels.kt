package com.xtv.app.core.purchase

import com.xtv.app.core.model.MediaItem
import java.util.Locale

/**
 * Exact USD microdollars. The API rate card is precise to thousandths of a dollar, so floating point
 * has no place in a quoted price or in the durable record of what a request may have cost.
 */
@JvmInline
value class UsdMicros(val value: Long) : Comparable<UsdMicros> {
    init {
        require(value >= 0) { "money cannot be negative" }
    }

    operator fun plus(other: UsdMicros): UsdMicros =
        UsdMicros(Math.addExact(value, other.value))

    operator fun minus(other: UsdMicros): UsdMicros {
        require(value >= other.value) { "money subtraction cannot be negative" }
        return UsdMicros(value - other.value)
    }

    operator fun times(multiplier: Long): UsdMicros {
        require(multiplier >= 0) { "money multiplier cannot be negative" }
        return UsdMicros(Math.multiplyExact(value, multiplier))
    }

    override fun compareTo(other: UsdMicros): Int = value.compareTo(other.value)

    fun formatUsd(): String = String.format(Locale.US, "$%.2f", value / 1_000_000.0)

    companion object {
        val ZERO = UsdMicros(0)
    }
}

data class ResourceCounts(
    val posts: Int = 0,
    val users: Int = 0,
    val media: Int = 0,
    val identityLookups: Int = 0,
) {
    init {
        require(posts >= 0 && users >= 0 && media >= 0 && identityLookups >= 0)
    }

    operator fun plus(other: ResourceCounts) = ResourceCounts(
        posts = Math.addExact(posts, other.posts),
        users = Math.addExact(users, other.users),
        media = Math.addExact(media, other.media),
        identityLookups = Math.addExact(identityLookups, other.identityLookups),
    )
}

data class ChargeEstimate(
    /** Expected charge using measured media yield and the current published resource rates. */
    val knownEstimate: UsdMicros,
    /** Conservative upper bound, quoted as the high end of the offer's price range. */
    val reservation: UsdMicros,
    val expectedResources: ResourceCounts,
    val rateCardVersion: String,
)

/** Versioned resource prices checked against the Developer Console for this request shape. */
data class RateCard(
    val version: String,
    val postRead: UsdMicros,
    val userRead: UsdMicros,
) {
    /** What one Post is reserved at, including [RESERVE_PERCENT] headroom. */
    val maximumReservationPerPost: UsdMicros
        get() = UsdMicros(postRead.value * RESERVE_PERCENT / 100)

    fun quote(requestedPosts: Int, identityLookupNeeded: Boolean): ChargeEstimate {
        require(requestedPosts in 1..MAX_POSTS_PER_REQUEST)
        val identityLookups = if (identityLookupNeeded) 1 else 0
        val expected = ResourceCounts(
            posts = requestedPosts,
            // Not billed per Post — see [charge]. Left at zero so this figure cannot be mistaken
            // for a billable quantity.
            users = 0,
            // A content estimate, not a billing quantity: roughly how many playable items this many
            // Posts have been measured to yield. It drives "about N videos" on the offer card.
            media = requestedPosts * EXPECTED_MEDIA_NUMERATOR / EXPECTED_MEDIA_DENOMINATOR,
            identityLookups = identityLookups,
        )
        val postCharge = postRead * requestedPosts.toLong()
        return ChargeEstimate(
            knownEstimate = charge(expected),
            reservation = UsdMicros(postCharge.value * RESERVE_PERCENT / 100) +
                (userRead * identityLookups.toLong()),
            expectedResources = expected,
            rateCardVersion = version,
        )
    }

    /**
     * What a set of returned resources actually costs.
     *
     * **Post reads, plus the occasional identity lookup. Nothing else.**
     *
     * The previous model billed one User read per Post and up to four Media reads per Post, on the
     * reasoning that the published rate card prices those resources and the response contains them.
     * Console billing for this request shape says otherwise: 640 Posts across 21 requests billed
     * $3.22, which is `640 x $0.005` plus a cent — and the same statement reports **one** User for
     * the whole period, the `/users/me` call made during provisioning. Authors arriving in
     * `includes` are deduplicated away, and expansions are not charged as separate Media reads.
     *
     * The old model therefore overstated every price by about **3.6x** — quoting offers at nearly
     * four times what they were going to be charged.
     */
    fun charge(resources: ResourceCounts): UsdMicros =
        (postRead * resources.posts.toLong()) +
            (userRead * resources.identityLookups.toLong())

    companion object {
        const val MAX_POSTS_PER_REQUEST = 100
        private const val EXPECTED_MEDIA_NUMERATOR = 3
        private const val EXPECTED_MEDIA_DENOMINATOR = 5

        /**
         * Headroom between the expected charge and what the ledger reserves.
         *
         * Measured billing for this request shape is `posts x postRead`, exactly. The margin is not
         * a guess at unbilled resources, then — it is room for the small, real discrepancies the
         * statement shows anyway (a stray identity read, rounding at the boundary), and it is what
         * keeps the reservation an upper bound rather than a coin flip.
         */
        private const val RESERVE_PERCENT = 120L

        /**
         * Source: X API pay-per-usage table, checked 2026-07-26; the **quantity** model was
         * corrected against a real Console statement on 2026-07-27, which is why the version moves
         * without any published price changing. The version is deliberately dated: it is stamped
         * into every offer token and re-checked before dispatch, so editing this invalidates any
         * offer quoted under the old model rather than silently charging a new price for it.
         */
        fun current() = RateCard(
            version = "x-pay-per-use-2026-07-27",
            postRead = UsdMicros(5_000),
            userRead = UsdMicros(10_000),
        )
    }
}

@JvmInline
value class OfferToken(val value: String)

@JvmInline
value class OperationId(val value: String)

enum class OfferId { SHORT, STANDARD, LONG }

data class ReelOffer(
    val token: OfferToken,
    val id: OfferId,
    val requestedPosts: Int,
    val estimatedVideos: Int,
    val charge: ChargeEstimate,
    /** Durable identity/project bindings checked again in the PREPARED transaction. */
    val accountScope: String? = null,
    val projectScope: String? = null,
)

data class ProjectPostUsage(
    val posts: Int,
    val resetDay: Int?,
    val observedAtMs: Long,
    /** Scope captured before dispatch; stale results must not cross a reprovision. */
    val projectScope: String? = null,
)

enum class ReelStatus { IN_PROGRESS, COMPLETED }

data class PurchasedReel(
    val id: String,
    val items: List<MediaItem>,
    val status: ReelStatus,
    /** The next item to play. The completed sentinel is exactly [items].size. */
    val nextIndex: Int,
    val createdAtMs: Long = 0,
    val partial: Boolean = false,
) {
    init {
        require(nextIndex in 0..items.size)
    }

    fun summary() = ReelSummary(id, items.size, nextIndex, status)
}

data class ReelSummary(
    val id: String,
    val total: Int,
    val nextIndex: Int,
    val status: ReelStatus,
) {
    val remaining: Int get() = total - nextIndex
}

enum class PurchaseWarning {
    PARTIAL_PAGE,
    SERVER_OVERDELIVERY,
    SHAPE_DRIFT,
    MISSING_CURSOR,
    ACCOUNTING_QUANTITY_UNKNOWN,
}

enum class AccountingCertainty { SETTLED_RESPONSE, PARTIAL_RESPONSE, REQUEST_OUTCOME_UNKNOWN }

data class PurchaseReceipt(
    val operationId: OperationId,
    val requestedPosts: Int,
    val resources: ResourceCounts,
    val estimatedCharge: UsdMicros,
    val reservation: UsdMicros,
    val rateCardVersion: String,
    val accountingCertainty: AccountingCertainty,
    val cursorAdvanced: Boolean,
    val warnings: Set<PurchaseWarning>,
    val completedAtMs: Long,
)

sealed interface PurchaseOutcome {
    data class ReelReady(val reel: ReelSummary) : PurchaseOutcome
    data object NoPlayableVideo : PurchaseOutcome
    data class PartialReel(val reel: ReelSummary?) : PurchaseOutcome
}

sealed interface PurchaseProblem {
    data object SetupRequired : PurchaseProblem
    data object AuthenticationRequired : PurchaseProblem
    data object PaymentRequired : PurchaseProblem
    data class RateLimited(val resetAtMs: Long?) : PurchaseProblem
    data object Network : PurchaseProblem
    data object UpstreamContractChanged : PurchaseProblem
    data object StorageUnavailable : PurchaseProblem
    data object Busy : PurchaseProblem
    data object StaleOffer : PurchaseProblem
    data object Unexpected : PurchaseProblem
}

sealed interface Rejection {
    data object NotReady : Rejection
    data object Busy : Rejection
    data object StaleOffer : Rejection
    data object StorageUnavailable : Rejection
}

enum class PurchaseStage { PREPARING, PREPARED, DISPATCHED, REFRESHING_USAGE }

sealed interface PurchaseOperation {
    data object Idle : PurchaseOperation
    data class Running(
        val id: OperationId,
        val offerToken: OfferToken?,
        val requestedPosts: Int?,
        val stage: PurchaseStage,
    ) : PurchaseOperation

    data class Finished(
        val id: OperationId,
        val outcome: PurchaseOutcome,
        val receipt: PurchaseReceipt,
    ) : PurchaseOperation

    data class Failed(val id: OperationId, val problem: PurchaseProblem) : PurchaseOperation
    data class Interrupted(val id: OperationId, val conservativelyCommitted: UsdMicros) : PurchaseOperation
}

enum class PurchaseReadiness { LOADING_LOCAL_STATE, READY, PRIVATE_STATE_UNAVAILABLE }

data class PurchaseSnapshot(
    val revision: Long,
    val readiness: PurchaseReadiness,
    val offers: List<ReelOffer>,
    val currentReel: ReelSummary?,
    /** X's Post count over X's own period, and what it is worth at the current rate card. */
    val projectUsage: ProjectPostUsage?,
    val operation: PurchaseOperation,
)

sealed interface PurchaseCommand {
    data class Buy(val token: OfferToken) : PurchaseCommand
    data class Acknowledge(val operationId: OperationId) : PurchaseCommand

    /** The viewer pressed Refresh. Shows progress, and says so if it fails. */
    data object RefreshProjectUsage : PurchaseCommand

    /** The app topping the figure up on its own. Silent, and never an operation. */
    data object SyncProjectUsage : PurchaseCommand
    /** Local-only re-read after provisioning, playback checkpoints, or a Settings reset. */
    data object ReloadLocalState : PurchaseCommand
}

sealed interface DispatchResult {
    data class Accepted(val operationId: OperationId) : DispatchResult
    data class AlreadyAccepted(val operationId: OperationId) : DispatchResult
    data class Rejected(val reason: Rejection) : DispatchResult
}

interface ReelPurchase {
    val state: kotlinx.coroutines.flow.StateFlow<PurchaseSnapshot>
    fun dispatch(command: PurchaseCommand): DispatchResult
}
