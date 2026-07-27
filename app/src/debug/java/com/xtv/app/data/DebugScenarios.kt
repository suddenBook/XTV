package com.xtv.app.data

import android.content.Context
import com.xtv.app.core.diag.Diagnostics
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.motionOnly
import com.xtv.app.core.purchase.OfferPolicy
import com.xtv.app.core.purchase.ProjectPostUsage
import com.xtv.app.core.purchase.PurchaseOperation
import com.xtv.app.core.purchase.PurchaseReadiness
import com.xtv.app.core.purchase.PurchaseRecord
import com.xtv.app.core.purchase.PurchaseSnapshot
import com.xtv.app.core.purchase.PurchaseStage
import com.xtv.app.core.purchase.OfferToken
import com.xtv.app.core.purchase.OperationId
import com.xtv.app.core.purchase.RateCard
import com.xtv.app.core.purchase.ReelStatus
import com.xtv.app.core.purchase.ReelSummary
import com.xtv.app.core.purchase.UsdMicros
import com.xtv.app.ui.notice.Notice
import com.xtv.app.ui.notice.Notices

/**
 * Named states, so every screen can be photographed without spending anything.
 *
 * The fixture entry that existed before this went straight into the player, which meant the three
 * screens that changed most in the redesign — home, settings, diagnostics — could not be looked at
 * without a provisioned device and a real purchase. Reviewing a layout by reading its source is how
 * layout bugs survive.
 *
 * Offers are not hand-written here. They come out of the real [OfferPolicy] driven by a synthetic
 * record, so what you photograph is what the policy actually produces.
 *
 * This file lives only in `src/debug`. [com.xtv.app.MainActivity] reaches it by reflection, so a
 * release build finds nothing and carries none of it.
 */
object DebugScenarios {

    private val policy = OfferPolicy(RateCard.current())

    /** Which screen the scenario lands on, or null when the name is not one of ours. */
    fun screen(name: String): String? = when (name) {
        "loading" -> "loading"
        "setup", "setup-bearer", "setup-session", "setup-provisioning", "setup-broken" -> "setup"
        "home", "home-reel", "home-working", "home-lost", "home-interrupted",
        "home-limited", "home-diag",
        -> "home"
        "settings" -> "settings"
        "diagnostics", "diagnostics-empty" -> "diagnostics"
        "grid" -> "grid"
        "reel", "reel-partial" -> "reel"
        else -> null
    }

    /** Which credential state a setup scenario is standing in. */
    fun missing(name: String): String? = when (name) {
        "setup" -> "CLIENT_ID"
        "setup-bearer" -> "BEARER"
        "setup-session" -> "SESSION"
        "setup-provisioning" -> "PROVISIONING"
        "setup-broken" -> "PRIVATE_STATE"
        else -> null
    }

    /** Video-only, exactly as a bought batch would be. */
    fun items(context: Context, name: String): List<MediaItem> = when (name) {
        "home-reel", "grid", "reel", "reel-partial", "home-diag", "home-limited" ->
            FixtureSource.load(context).motionOnly()
        else -> emptyList()
    }

    /** Where playback and the grid think the viewer had reached. */
    fun nextIndex(name: String, total: Int): Int = when (name) {
        "home-reel", "grid", "home-diag" -> (total / 3).coerceIn(0, total)
        else -> 0
    }

    fun partial(name: String): Boolean = name == "reel-partial"

    fun notice(name: String): Notice? = when (name) {
        "home-lost" -> Notices.of(
            com.xtv.app.core.purchase.PurchaseOutcome.NoPlayableVideo,
            receipt(posts = 30),
        )
        "home-interrupted" -> Notices.interrupted(UsdMicros(750_000).formatUsd())
        "home-limited" -> Notices.of(com.xtv.app.core.purchase.PurchaseProblem.RateLimited(null))
        else -> null
    }

    /** Fills the in-memory ring so the log screen has something to be. */
    fun seedDiagnostics(name: String) {
        if (name == "diagnostics-empty") return
        if (screen(name) != "diagnostics" && name != "home-diag") return
        Diagnostics.record("timeline", "requested=30 posts=30 users=28 media=41 parsed=30/30 dropped=0")
        Diagnostics.record("http 200", "remaining=44 limit=50 bytes=88213")
        Diagnostics.record("usage", "HTTP 503 — spend figure is an estimate")
        Diagnostics.record("http 429", "remaining=0 limit=50 bytes=142")
        Diagnostics.record("timeline", "requested=60 posts=17 users=17 media=22 parsed=17/20 dropped=3")
    }

    fun snapshot(name: String, itemCount: Int): PurchaseSnapshot? {
        if (screen(name) == null || screen(name) == "setup" || screen(name) == "loading") return null

        val record = PurchaseRecord(
            accountId = "debug-account",
            accountScope = "debug-account",
            projectScope = "debug-project",
        )
        val reel = if (itemCount > 0) {
            ReelSummary(
                id = "debug-batch",
                total = itemCount,
                nextIndex = nextIndex(name, itemCount),
                status = if (name == "home-reel" || name == "home-diag") {
                    ReelStatus.IN_PROGRESS
                } else {
                    ReelStatus.COMPLETED
                },
            )
        } else {
            null
        }

        return PurchaseSnapshot(
            revision = 1,
            readiness = PurchaseReadiness.READY,
            offers = policy.createOffers(record, revision = 1),
            currentReel = reel,
            projectUsage = ProjectPostUsage(
                posts = 12_480,
                resetDay = 3,
                observedAtMs = 0,
                projectScope = "debug-project",
            ),
            operation = if (name == "home-working") {
                PurchaseOperation.Running(
                    id = OperationId("debug-op"),
                    offerToken = OfferToken("debug-token"),
                    requestedPosts = 60,
                    stage = PurchaseStage.DISPATCHED,
                )
            } else {
                PurchaseOperation.Idle
            },
        )
    }

    private fun receipt(posts: Int) = com.xtv.app.core.purchase.PurchaseReceipt(
        operationId = OperationId("debug-op"),
        requestedPosts = posts,
        resources = com.xtv.app.core.purchase.ResourceCounts(posts = posts, users = posts),
        estimatedCharge = UsdMicros(150_000),
        reservation = UsdMicros(350_000),
        rateCardVersion = RateCard.current().version,
        accountingCertainty = com.xtv.app.core.purchase.AccountingCertainty.SETTLED_RESPONSE,
        cursorAdvanced = true,
        warnings = emptySet(),
        completedAtMs = 0,
    )
}
