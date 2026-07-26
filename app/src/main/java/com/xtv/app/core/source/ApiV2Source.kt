package com.xtv.app.core.source

import android.util.Log
import com.xtv.app.core.auth.SessionManager
import com.xtv.app.core.diag.Diagnostics
import com.xtv.app.core.model.PageResult
import com.xtv.app.data.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration

private const val TAG = "XTV-API"

/**
 * The real source: X API v2, reverse-chronological home timeline.
 *
 * Field selection is not cosmetic — two of these were learned the hard way:
 *  - `media.fields=url` is what carries a **photo's** URL. Leave it out and photos come back with
 *    nowhere to load from, silently.
 *  - `variants` is what carries a **video's** URLs, and one variant (HLS) has no `bit_rate` at all.
 *
 * Paging is bounded by a post budget rather than run to exhaustion. This timeline produces ~4,500
 * posts a day and each read is billed, so "fetch everything since last time" would be both
 * unaffordable and pointless — the user cannot watch it either way.
 */
class ApiV2Source(
    private val session: SessionManager,
    private val client: OkHttpClient = defaultClient,
) : TimelineSource {

    override suspend fun loadHead(budget: Int, sinceId: String?): PageResult = withContext(Dispatchers.IO) {
        val userId = session.accountId() ?: return@withContext PageResult.AuthRequired

        val collected = mutableListOf<com.xtv.app.core.model.MediaItem>()
        var newestId: String? = null
        var postsRead = 0
        var seen = 0
        var recognised = 0
        var withoutMedia = 0
        var dropped = 0
        var pageToken: String? = null
        var emptyStreak = 0

        // `max_results` has a documented floor of 5, so a remaining budget below that would get
        // rounded UP into an overspend. Stop instead — a few unread posts is the correct rounding
        // direction when the rounding costs money.
        while (budget - postsRead >= MIN_PAGE) {
            val want = (budget - postsRead).coerceIn(MIN_PAGE, 100)
            val url = buildString {
                append("https://api.x.com/2/users/").append(userId)
                append("/timelines/reverse_chronological?max_results=").append(want)
                append('&').append(FIELDS)
                sinceId?.let { append("&since_id=").append(it) }
                pageToken?.let { append("&pagination_token=").append(it) }
            }

            when (val outcome = fetch(url)) {
                is Fetched.Problem -> {
                    // A failure on a later page still leaves usable items from the earlier ones;
                    // returning what we have beats discarding a page the user already paid for.
                    if (collected.isEmpty()) return@withContext outcome.result
                    Log.w(TAG, "stopping early after ${outcome.result}")
                    break
                }
                is Fetched.Page -> {
                    val parsed = outcome.result
                    if (parsed !is PageResult.Ok) return@withContext parsed
                    // Observed: a page can come back with MORE posts than `max_results` asked for
                    // (budget 30 -> 34 read). Since every post is billed, the budget has to be
                    // enforced here rather than trusted to the server.
                    if (parsed.postsRead > want) {
                        Log.w(TAG, "server returned ${parsed.postsRead} for max_results=$want")
                    }
                    postsRead += parsed.postsRead
                    seen += parsed.stats.postsSeen
                    recognised += parsed.stats.postsRecognised
                    withoutMedia += parsed.stats.postsWithoutMedia
                    dropped += parsed.stats.mediaDropped
                    collected += parsed.items
                    if (newestId == null) newestId = parsed.newestPostId

                    // Two consecutive pages with nothing new means the head is exhausted (common when
                    // since_id is recent). Stop rather than spend on more empty pages — the reference
                    // scrapers tolerate over a hundred of these, which is a request-storm recipe.
                    if (parsed.postsRead == 0) {
                        if (++emptyStreak >= 2) break
                    } else {
                        emptyStreak = 0
                    }

                    pageToken = outcome.nextToken ?: break
                    delay(PAGE_DELAY_MS)
                }
            }
        }

        Log.i(TAG, "loadHead budget=$budget read=$postsRead items=${collected.size}")
        // Counts, not content: recognised<seen is the only way to tell "upstream changed" apart
        // from "nothing new tonight", and both render as an empty screen.
        Diagnostics.record(
            "timeline",
            "budget=$budget read=$postsRead 识别=$recognised/$seen 媒体=${collected.size} 无媒体=$withoutMedia 丢弃=$dropped",
        )
        PageResult.Ok(
            items = collected,
            newestPostId = newestId,
            postsRead = postsRead,
            stats = com.xtv.app.core.model.PageStats(seen, recognised, collected.size, withoutMedia, dropped),
        )
    }

    private sealed interface Fetched {
        data class Page(val result: PageResult, val nextToken: String?) : Fetched
        data class Problem(val result: PageResult) : Fetched
    }

    private suspend fun fetch(url: String): Fetched {
        val token = session.accessToken() ?: return Fetched.Problem(PageResult.AuthRequired)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                // 401 is the one status worth special-casing before parsing: the body may be empty,
                // and it is the signal to re-authorise rather than to retry.
                // The three rate-limit headers are the authoritative budget signal; record them
                // whatever the status, so a 429 is never a surprise in hindsight.
                Diagnostics.record(
                    "http ${response.code}",
                    "剩余=${response.header("x-rate-limit-remaining") ?: "-"}" +
                        "/${response.header("x-rate-limit-limit") ?: "-"} ${body.length}B",
                )
                if (response.code == 401) return Fetched.Problem(PageResult.AuthRequired)
                if (response.code == 429) {
                    val reset = response.header("x-rate-limit-reset")?.toLongOrNull()?.times(1000)
                    return Fetched.Problem(PageResult.RateLimited(reset))
                }
                val parsed = Normalizer.parse(json.parseToJsonElement(body))
                when (parsed) {
                    is PageResult.Ok -> Fetched.Page(parsed, nextToken(body))
                    else -> Fetched.Problem(parsed)
                }
            }
        } catch (t: Throwable) {
            Fetched.Problem(PageResult.Transient(t.message ?: t.javaClass.simpleName))
        }
    }

    private fun nextToken(body: String): String? = runCatching {
        json.parseToJsonElement(body).let { root ->
            (root as? kotlinx.serialization.json.JsonObject)
                ?.get("meta")?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("next_token")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }
    }.getOrNull()

    private companion object {
        /** `url` for photos, `variants` for video — omit either and that media type goes dark. */
        const val FIELDS =
            "expansions=attachments.media_keys,author_id" +
                "&media.fields=url,variants,preview_image_url,duration_ms,alt_text,type,width,height" +
                "&tweet.fields=created_at,possibly_sensitive" +
                "&user.fields=username,name,profile_image_url"

        /** Human-ish cadence between pages; the reference scrapers use none, which is not a model. */
        const val PAGE_DELAY_MS = 900L

        /** X's documented minimum for `max_results` on this endpoint. */
        const val MIN_PAGE = 5

        val json = Json { ignoreUnknownKeys = true }
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofSeconds(30))
            .build()
    }
}
