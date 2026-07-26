package com.xtv.app.core.model

/** Who posted an item. */
data class Author(
    val id: String,
    val username: String,
    val name: String,
    val avatarUrl: String? = null,
)

enum class MediaKind { PHOTO, VIDEO, GIF }

/**
 * One displayable thing. The reel, the grid and the history all index the same flat list of these,
 * so a post carrying four photos becomes four items rather than one item you have to click into.
 *
 * [id] is `"<postId>:<mediaKey>"` — stable across fetches, so it doubles as the dedup key and the
 * history key.
 */
data class MediaItem(
    val id: String,
    val kind: MediaKind,
    val indexInPost: Int,
    val countInPost: Int,
    /** Photo: `media.url`. Video/GIF: the highest-bitrate progressive MP4 variant. */
    val displayUrl: String,
    /** Video/GIF poster frame; null for photos (the photo *is* the poster). */
    val posterUrl: String? = null,
    val width: Int,
    val height: Int,
    /**
     * Nullable on purpose. `duration_ms` is documented as optional and is routinely absent on
     * animated GIFs; a non-null type here crashes on the first GIF the reel encounters.
     */
    val durationMs: Long? = null,
    val author: Author,
    val text: String,
    val createdAtMs: Long,
    val possiblySensitive: Boolean = false,
) {
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 1f
}

/**
 * Per-page parse accounting.
 *
 * [postsRecognised] < [postsSeen] means the response contained entries this parser did not
 * understand — an upstream shape change. Every reference scraper silently skips those, which makes
 * "the API changed" indistinguishable from "there is nothing to show". XTV surfaces it instead.
 */
data class PageStats(
    val postsSeen: Int,
    val postsRecognised: Int,
    val mediaExtracted: Int,
    val postsWithoutMedia: Int,
    val mediaDropped: Int,
) {
    val shapeDrift: Boolean get() = postsRecognised < postsSeen
    val mediaDensity: Float get() = if (postsSeen == 0) 0f else (postsSeen - postsWithoutMedia).toFloat() / postsSeen
}

/**
 * Outcome of asking a [com.xtv.app.core.source.TimelineSource] for a page.
 *
 * The error cases are deliberately semantic rather than transport-shaped: callers decide what to
 * show, not how to read a status code. In particular [PaymentRequired] is its own case because X
 * signals an exhausted prepaid balance with HTTP 402 and an RFC 7807 body, and treating that as
 * "empty page" would silently tell the user they have nothing to watch.
 */
sealed interface PageResult {
    data class Ok(
        val items: List<MediaItem>,
        val newestPostId: String?,
        val postsRead: Int,
        val stats: PageStats,
    ) : PageResult

    data object AuthRequired : PageResult
    data class RateLimited(val resetAtMs: Long?) : PageResult
    data class PaymentRequired(val detail: String) : PageResult
    data class UpstreamChanged(val detail: String) : PageResult
    data class Transient(val cause: String) : PageResult
}
