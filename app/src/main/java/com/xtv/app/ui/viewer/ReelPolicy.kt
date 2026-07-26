package com.xtv.app.ui.viewer

import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.MediaKind

/**
 * What goes in a reel and how long each item holds the screen.
 *
 * **Video only.** Stills are excluded: this is a watch-it-through reel, not a slideshow, and mixing
 * an 8-second photo between two videos breaks the rhythm and the audio. Photos still parse (the
 * Normalizer stays faithful to the API) — they are simply not reel material.
 *
 * **Videos play in full.** An earlier draft capped them at 90 seconds and labelled the result a
 * preview; that was solving a problem this reel does not have. If a creator posted two minutes, two
 * minutes is the content.
 *
 * Timing constants are fixed rather than derived from the content. Dwell scaled by "interestingness"
 * is fake intelligence: unpredictable timing reads as the app being erratic.
 */
object ReelPolicy {

    /** GIFs are short silent loops; ~3 passes, bounded so a 200ms loop can't hold the screen. */
    const val GIF_TARGET_LOOPS = 3
    const val GIF_MIN_MS = 6_000L
    const val GIF_MAX_MS = 15_000L

    /** No first frame within this → skip. A stalled item must never end the evening. */
    const val FIRST_FRAME_TIMEOUT_MS = 8_000L

    /** Continuous rebuffering for this long mid-video → skip. */
    const val REBUFFER_TIMEOUT_MS = 10_000L

    /** Consecutive failures before telling the user something is wrong with the feed. */
    const val FAILURE_STREAK_WARNING = 3

    /**
     * The reel is exactly the motion content, in timeline order.
     *
     * Note the cost consequence: billing is per *post read*, not per usable item, so dropping photos
     * does not make anything cheaper — it slightly raises the effective price of what remains
     * (measured: ~60% of media in this timeline is video, so ~$0.0084 per video vs ~$0.0071 per
     * media item). Still single-digit dollars a month; worth it for a coherent reel.
     */
    fun buildReel(items: List<MediaItem>): List<MediaItem> =
        items.filter { it.kind == MediaKind.VIDEO || it.kind == MediaKind.GIF }

    /**
     * Hard stop for an item, in milliseconds. Videos return null — they end when the player says
     * they ended, not on a timer.
     */
    fun holdCapMs(item: MediaItem): Long? = when (item.kind) {
        MediaKind.VIDEO -> null
        MediaKind.GIF -> {
            // duration_ms is routinely absent on GIFs; fall back to the floor rather than to zero.
            val loop = item.durationMs ?: 0L
            if (loop <= 0L) GIF_MIN_MS else (loop * GIF_TARGET_LOOPS).coerceIn(GIF_MIN_MS, GIF_MAX_MS)
        }
        MediaKind.PHOTO -> GIF_MIN_MS // unreachable via buildReel; a safe floor if one slips through
    }

    /** Planned on-screen time, for estimating a reel's runtime before it starts. */
    fun plannedDurationMs(item: MediaItem): Long = when (item.kind) {
        MediaKind.VIDEO -> item.durationMs ?: 30_000L
        else -> holdCapMs(item) ?: GIF_MIN_MS
    }

    fun plannedRuntimeMs(items: List<MediaItem>): Long = items.sumOf { plannedDurationMs(it) }

    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}
