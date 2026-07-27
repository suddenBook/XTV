package com.xtv.app.data

import com.xtv.app.core.model.Author
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.MediaKind
import com.xtv.app.core.model.PageFailure
import com.xtv.app.core.model.PageResult
import com.xtv.app.core.model.PageStats
import com.xtv.app.core.model.PageWarning
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * Pure JSON -> domain translation for the X API v2 timeline responses. No I/O, no Android, no
 * suspend: the whole thing is exercised from `src/test` against captured fixtures, which is the only
 * cheap place to test a parser whose upstream can change without warning.
 *
 * Everything here was derived from real captured responses — the sanitized ones in
 * `app/src/test/resources/fixtures/` are what the tests still run against — including four things
 * that are easy to get wrong and expensive to discover on a TV:
 *
 *  1. **Photos carry their URL in `media.url`, and only if `url` was requested in `media.fields`.**
 *     Omit it and every photo silently has nowhere to load from.
 *  2. **Not every video variant has `bit_rate`** — the HLS entry has none. Selecting the "best"
 *     variant by a non-null bitrate throws or silently picks the m3u8.
 *  3. **`duration_ms` is absent on animated GIFs.**
 *  4. **Errors arrive as a top-level RFC 7807 object** (`{"status":402,"title":...}`), not as a 200
 *     with an `errors` array. Parsing one of those as a page yields zero items, which reads exactly
 *     like "nothing new tonight".
 */
object Normalizer {

    /** Media types we can display. Anything else counts as dropped rather than silently vanishing. */
    private val KNOWN_KINDS = mapOf(
        "photo" to MediaKind.PHOTO,
        "video" to MediaKind.VIDEO,
        "animated_gif" to MediaKind.GIF,
    )

    fun parse(root: JsonElement): PageResult {
        val obj = root as? JsonObject ?: return PageResult.UpstreamChanged("response root is not an object")

        // RFC 7807 problem document. Check before anything else: these carry no `data`, so falling
        // through would produce a perfectly plausible empty page.
        (obj["status"] as? JsonPrimitive)?.intOrNull?.let { status ->
            if (status >= 400) {
                val detail = (obj["detail"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["title"] as? JsonPrimitive)?.contentOrNull
                    ?: "HTTP $status"
                return errorResult(status, detail)
            }
        }

        val embeddedError = obj["errors"]?.asArrayOrNull()
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull()
            ?.let(::parseError)
        val dataElement = obj["data"]
        if (dataElement != null && dataElement !is JsonArray) {
            return PageResult.UpstreamChanged("data is not an array")
        }
        if (dataElement == null) {
            embeddedError?.let { return errorResult(it.status, it.detail) }
            val explicitEmpty = (obj["meta"] as? JsonObject)
                ?.get("result_count")
                ?.let { it as? JsonPrimitive }
                ?.intOrNull == 0
            if (!explicitEmpty) return PageResult.UpstreamChanged("response carried neither data nor an explicit empty result")
        }

        val posts = dataElement.orEmpty()
        val includes = obj["includes"] as? JsonObject
        val returnedMedia = includes?.get("media")?.asArrayOrNull().orEmpty()
        val returnedUsers = includes?.get("users")?.asArrayOrNull().orEmpty()
        val mediaByKey = returnedMedia
            .mapNotNull { m -> (m as? JsonObject)?.let { it.str("media_key")?.to(it) } }
            .toMap()
        val usersById = returnedUsers
            .mapNotNull { u -> (u as? JsonObject)?.let { it.str("id")?.to(it) } }
            .toMap()

        val items = mutableListOf<MediaItem>()
        var recognised = 0
        var withoutMedia = 0
        var dropped = 0
        var newestPostId: String? = (obj["meta"] as? JsonObject)?.str("newest_id")

        for (element in posts) {
            val post = element as? JsonObject ?: continue
            val postId = post.str("id") ?: continue
            recognised++
            if (newestPostId == null) newestPostId = postId

            val keys = (post["attachments"] as? JsonObject)?.get("media_keys")?.asArrayOrNull()
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            if (keys.isEmpty()) {
                withoutMedia++
                continue
            }

            val author = usersById[post.str("author_id")]?.toAuthor()
                ?: Author(id = post.str("author_id") ?: "", username = "", name = "")
            val text = post.str("text").orEmpty()
            val createdAtMs = post.str("created_at")?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            } ?: 0L
            val sensitive = (post["possibly_sensitive"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

            // Resolve first so countInPost reflects what is actually displayable: a post whose 3rd
            // photo failed to expand should read "2 of 2", not "3 of 4".
            val resolved = keys.mapNotNull { key ->
                val media = mediaByKey[key]
                if (media == null) { dropped++; null } else media
            }
            val displayable = resolved.mapNotNull { media ->
                val kind = KNOWN_KINDS[media.str("type")]
                if (kind == null) {
                    dropped++
                    null
                } else {
                    val url = media.displayUrl(kind)
                    if (url == null) {
                        dropped++
                        null
                    } else {
                        Triple(kind, media, url)
                    }
                }
            }
            if (displayable.isEmpty()) {
                withoutMedia++
                continue
            }

            displayable.forEachIndexed { index, (kind, media, url) ->
                items += MediaItem(
                    id = "$postId:${media.str("media_key")}",
                    kind = kind,
                    indexInPost = index,
                    countInPost = displayable.size,
                    displayUrl = url,
                    posterUrl = media.str("preview_image_url"),
                    width = media.int("width") ?: 0,
                    height = media.int("height") ?: 0,
                    durationMs = media.long("duration_ms"),
                    author = author,
                    text = text,
                    createdAtMs = createdAtMs,
                    possiblySensitive = sensitive,
                )
            }
        }

        return PageResult.Ok(
            items = items,
            newestPostId = newestPostId,
            postsRead = posts.size,
            stats = PageStats(
                postsSeen = posts.size,
                postsRecognised = recognised,
                mediaExtracted = items.size,
                postsWithoutMedia = withoutMedia,
                mediaDropped = dropped,
                usersReturned = returnedUsers.size,
                mediaReturned = returnedMedia.size,
            ),
            warnings = if (embeddedError == null) emptySet() else setOf(PageWarning.PARTIAL_ERRORS),
            partialFailure = embeddedError?.let { errorFailure(it.status, it.detail) },
        )
    }

    private data class EmbeddedError(val status: Int?, val detail: String)

    private fun parseError(error: JsonObject): EmbeddedError = EmbeddedError(
        status = (error["status"] as? JsonPrimitive)?.intOrNull,
        detail = (error["detail"] as? JsonPrimitive)?.contentOrNull
            ?: (error["title"] as? JsonPrimitive)?.contentOrNull
            ?: "response carried errors",
    )

    private fun errorResult(status: Int?, detail: String): PageResult = when (status) {
        401, 403 -> PageResult.AuthRequired
        402 -> PageResult.PaymentRequired(detail)
        429 -> PageResult.RateLimited(resetAtMs = null)
        in 500..599 -> PageResult.Transient("HTTP $status: $detail", requestPossiblyBilled = false)
        else -> PageResult.UpstreamChanged("HTTP ${status ?: "?"}: $detail")
    }

    private fun errorFailure(status: Int?, detail: String): PageFailure = when (status) {
        401, 403 -> PageFailure.AuthRequired
        402 -> PageFailure.PaymentRequired(detail)
        429 -> PageFailure.RateLimited(resetAtMs = null)
        in 500..599 -> PageFailure.Transient("HTTP $status: $detail", requestPossiblyBilled = false)
        else -> PageFailure.UpstreamChanged("HTTP ${status ?: "?"}: $detail")
    }

    /**
     * Photos expose `url` directly; video and GIF expose a variant ladder.
     *
     * Variant choice prefers the highest-bitrate progressive MP4 — a TV can play the top rung, and
     * progressive playback avoids pulling in an HLS manifest per item. The HLS variant has **no
     * `bit_rate` field at all**, so it sorts last via the `?: -1` default rather than throwing, and
     * is only returned if no MP4 exists.
     */
    private fun JsonObject.displayUrl(kind: MediaKind): String? = when (kind) {
        MediaKind.PHOTO -> str("url")
        MediaKind.VIDEO, MediaKind.GIF -> {
            val variants = this["variants"]?.asArrayOrNull().orEmpty().mapNotNull { it as? JsonObject }
            val mp4s = variants.filter { it.str("content_type") == "video/mp4" }
            (mp4s.maxByOrNull { it.int("bit_rate") ?: -1 } ?: variants.firstOrNull())?.str("url")
        }
    }

    private fun JsonObject.toAuthor() = Author(
        id = str("id").orEmpty(),
        username = str("username").orEmpty(),
        name = str("name").orEmpty(),
        avatarUrl = str("profile_image_url"),
    )

    private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
}
