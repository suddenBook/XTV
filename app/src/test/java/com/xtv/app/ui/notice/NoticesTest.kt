package com.xtv.app.ui.notice

import com.xtv.app.R
import com.xtv.app.core.purchase.AccountingCertainty
import com.xtv.app.core.purchase.OperationId
import com.xtv.app.core.purchase.PurchaseOutcome
import com.xtv.app.core.purchase.PurchaseProblem
import com.xtv.app.core.purchase.PurchaseReceipt
import com.xtv.app.core.purchase.ReelStatus
import com.xtv.app.core.purchase.ReelSummary
import com.xtv.app.core.purchase.Rejection
import com.xtv.app.core.purchase.ResourceCounts
import com.xtv.app.core.purchase.UsdMicros
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The classification is the whole point of this layer, so it is tested without a screen attached.
 *
 * What is being pinned down is not the wording but the weight: which results interrupt, which
 * linger, which clear themselves, and which are not worth saying at all.
 */
class NoticesTest {

    private fun receipt(posts: Int) = PurchaseReceipt(
        operationId = OperationId("op"),
        requestedPosts = posts,
        resources = ResourceCounts(posts = posts, users = posts, media = 0),
        estimatedCharge = UsdMicros(150_000),
        reservation = UsdMicros(350_000),
        rateCardVersion = "test",
        accountingCertainty = AccountingCertainty.SETTLED_RESPONSE,
        cursorAdvanced = true,
        warnings = emptySet(),
        completedAtMs = 0,
    )

    @Test
    fun `problems that change the screen are not also announced`() {
        // These three route to setup. Saying it on the way out as well would report one condition
        // twice, in two different registers.
        assertNull(Notices.of(PurchaseProblem.SetupRequired))
        assertNull(Notices.of(PurchaseProblem.AuthenticationRequired))
        assertNull(Notices.of(PurchaseProblem.StorageUnavailable))
    }

    @Test
    fun `problems that pass on their own are brief`() {
        val brief = listOf(
            PurchaseProblem.RateLimited(resetAtMs = null),
            PurchaseProblem.Network,
            PurchaseProblem.Busy,
            PurchaseProblem.StaleOffer,
        )
        brief.forEach { problem ->
            assertEquals(
                "$problem should clear itself",
                NoticeWeight.BRIEF,
                Notices.of(problem)?.weight,
            )
        }
    }

    @Test
    fun `problems needing action elsewhere stand until they are resolved`() {
        val standing = listOf(
            PurchaseProblem.PaymentRequired,
            PurchaseProblem.UpstreamContractChanged,
            PurchaseProblem.Unexpected,
        )
        standing.forEach { problem ->
            assertEquals(
                "$problem cannot pass on its own",
                NoticeWeight.STANDING,
                Notices.of(problem)?.weight,
            )
        }
    }

    @Test
    fun `paying and getting nothing is the loudest thing this app says`() {
        val notice = Notices.of(PurchaseOutcome.NoPlayableVideo, receipt(posts = 30))

        assertEquals(NoticeWeight.LOUD, notice?.weight)
        assertEquals(R.string.notice_no_videos_title, notice?.title)
        assertEquals(NoticeText.Quantity(R.plurals.notice_no_videos_body, 30), notice?.text)
    }

    @Test
    fun `an unbillable interruption names the amount it recorded`() {
        val notice = Notices.interrupted("$0.75")

        assertEquals(NoticeWeight.LOUD, notice.weight)
        assertEquals(
            NoticeText.Formatted(R.string.notice_interrupted_body, "$0.75"),
            notice.text,
        )
    }

    @Test
    fun `a delivered batch says nothing here, including a partial one`() {
        val summary = ReelSummary(
            id = "batch",
            total = 18,
            nextIndex = 0,
            status = ReelStatus.IN_PROGRESS,
        )
        // Both open the player, and the player carries the incomplete-batch warning itself.
        assertNull(Notices.of(PurchaseOutcome.ReelReady(summary), receipt(30)))
        assertNull(Notices.of(PurchaseOutcome.PartialReel(summary), receipt(30)))
    }

    @Test
    fun `a refused command reports only what the viewer can act on`() {
        assertEquals(NoticeWeight.BRIEF, Notices.of(Rejection.StaleOffer)?.weight)
        assertEquals(NoticeWeight.BRIEF, Notices.of(Rejection.Busy)?.weight)
        assertNull(Notices.of(Rejection.NotReady))
        assertNull(Notices.of(Rejection.StorageUnavailable))
    }
}
