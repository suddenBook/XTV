package com.xtv.app.ui.viewer

/**
 * How the reel presents itself.
 *
 * **Videos play in full.** An earlier draft capped them at 90 seconds and labelled the result a
 * preview; that was solving a problem this reel does not have. If a creator posted two minutes, two
 * minutes is the content.
 *
 * What goes *into* a reel is [com.xtv.app.core.model.motionOnly], which lives in the model because
 * the purchase path applies it too — the answer must be the same in both places.
 *
 * Playback timing belongs to the playback module; this UI policy only chooses the presentation.
 */
object ReelPolicy {

    /** Consecutive failures before telling the user something is wrong with the feed. */
    const val FAILURE_STREAK_WARNING = 3

    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}
