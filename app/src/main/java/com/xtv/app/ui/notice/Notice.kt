package com.xtv.app.ui.notice

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.xtv.app.R
import com.xtv.app.core.purchase.PurchaseOutcome
import com.xtv.app.core.purchase.PurchaseProblem
import com.xtv.app.core.purchase.PurchaseReceipt
import com.xtv.app.core.purchase.Rejection

/**
 * How loudly something is said.
 *
 * Every message this app produced used to arrive through one channel: a 0.7-alpha line in the
 * bottom-left corner that never cleared itself. "You are being rate limited" and "you paid for
 * thirty posts and not one of them had a video in it" looked identical, and both were still on
 * screen twenty minutes later.
 *
 * The weight is decided by what the viewer can do about it, not by what broke.
 */
enum class NoticeWeight {
    /** It will pass on its own. Says so briefly, then clears itself. */
    BRIEF,

    /** It will not pass until someone acts somewhere else — a console, a calendar. Stays put. */
    STANDING,

    /** Money left the account and there is nothing to show for it. Interrupts, and waits. */
    LOUD,
}

/** A string plus whatever it needs to be formatted; resolved at the composable, not here. */
sealed interface NoticeText {
    data class Plain(@StringRes val id: Int) : NoticeText
    data class Quantity(@PluralsRes val id: Int, val count: Int) : NoticeText
    data class Formatted(@StringRes val id: Int, val arg: String) : NoticeText
}

data class Notice(
    val weight: NoticeWeight,
    val text: NoticeText,
    /** LOUD only: the headline above [text]. */
    @StringRes val title: Int? = null,
)

/**
 * Turns purchase results into something worth saying — or into nothing.
 *
 * Deliberately pure and free of Android types so the classification is testable on its own. The
 * rule it encodes is that a result the viewer cannot act on and has not lost money over is not a
 * message; it is noise. `SERVER_OVERDELIVERY` is the clearest case: X returned more posts than were
 * asked for and the ledger accounted for all of them. Correct, worth logging, and of no use
 * whatsoever to someone holding a remote.
 */
object Notices {

    /**
     * Failures. Returns null for the ones that are not messages at all: a missing credential or an
     * unreadable envelope changes which screen you are on, and saying it twice would be worse than
     * saying it once.
     */
    fun of(problem: PurchaseProblem): Notice? = when (problem) {
        PurchaseProblem.SetupRequired,
        PurchaseProblem.AuthenticationRequired,
        PurchaseProblem.StorageUnavailable,
        -> null

        is PurchaseProblem.RateLimited -> brief(R.string.notice_rate_limited)
        PurchaseProblem.Network -> brief(R.string.notice_network)
        PurchaseProblem.Busy -> brief(R.string.notice_busy)
        PurchaseProblem.StaleOffer -> brief(R.string.notice_state_changed)

        PurchaseProblem.PaymentRequired -> standing(R.string.notice_no_credits)
        PurchaseProblem.UpstreamContractChanged -> standing(R.string.notice_upstream_changed)
        PurchaseProblem.Unexpected -> standing(R.string.notice_unexpected)
    }

    /**
     * Results.
     *
     * `ReelReady` and `PartialReel` both open the player, so neither says anything here — the
     * player itself carries the incomplete-batch warning over the video it is playing, and telling
     * someone the same thing on two consecutive screens reads as two separate problems.
     */
    fun of(outcome: PurchaseOutcome, receipt: PurchaseReceipt): Notice? = when (outcome) {
        is PurchaseOutcome.ReelReady,
        is PurchaseOutcome.PartialReel,
        -> null

        PurchaseOutcome.NoPlayableVideo -> Notice(
            weight = NoticeWeight.LOUD,
            title = R.string.notice_no_videos_title,
            text = NoticeText.Quantity(R.plurals.notice_no_videos_body, receipt.resources.posts),
        )
    }

    /** A command the state machine would not even accept. */
    fun of(rejection: Rejection): Notice? = when (rejection) {
        Rejection.StaleOffer -> brief(R.string.notice_offer_expired)
        Rejection.Busy -> brief(R.string.notice_busy)
        Rejection.NotReady, Rejection.StorageUnavailable -> null
    }

    /**
     * A request that may or may not have been billed. The amount is the reservation the ledger
     * committed rather than a known charge, which is exactly why it has to be said out loud.
     */
    fun interrupted(committed: String) = Notice(
        weight = NoticeWeight.LOUD,
        title = R.string.notice_interrupted_title,
        text = NoticeText.Formatted(R.string.notice_interrupted_body, committed),
    )

    /** A reset refused because a paid request is still in flight. Passes on its own. */
    fun resetBlocked() = brief(R.string.settings_reset_blocked)

    private fun brief(@StringRes id: Int) =
        Notice(NoticeWeight.BRIEF, NoticeText.Plain(id))

    private fun standing(@StringRes id: Int) =
        Notice(NoticeWeight.STANDING, NoticeText.Plain(id))
}
