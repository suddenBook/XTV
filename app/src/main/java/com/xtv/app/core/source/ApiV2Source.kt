package com.xtv.app.core.source

import android.util.Log
import com.xtv.app.core.diag.Diagnostics
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.PageFailure
import com.xtv.app.core.model.PageResult
import com.xtv.app.core.model.PageStats
import com.xtv.app.core.model.PageWarning
import com.xtv.app.core.purchase.PurchaseProblem
import com.xtv.app.core.purchase.RequestSentState
import com.xtv.app.core.purchase.ResourceCounts
import com.xtv.app.core.purchase.TimelineOutcome
import com.xtv.app.core.purchase.TimelineReadPort
import com.xtv.app.core.purchase.TimelineRequest
import com.xtv.app.data.Normalizer
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "XTV-API"

/**
 * The HTTP/normalization adapter. It counts returned Posts, Users, and Media independently, keeps
 * paid partial pages, and never treats "no playable video" as proof that no resources were read.
 */
class ApiV2Reader(
    private val client: OkHttpClient = defaultClient,
) {
    suspend fun loadHead(
        budget: Int,
        sinceId: String?,
        userId: String,
        accessToken: suspend () -> String?,
    ): PageResult = withContext(Dispatchers.IO) {
        require(budget in 1..MAX_PAGE) { "timeline budget must be 1..100" }

        val collected = mutableListOf<MediaItem>()
        var newestId: String? = null
        var postsRead = 0
        var seen = 0
        var recognised = 0
        var usersReturned = 0
        var mediaReturned = 0
        var withoutMedia = 0
        var dropped = 0
        var pageToken: String? = null
        var emptyStreak = 0
        val warnings = mutableSetOf<PageWarning>()
        var partialFailure: PageFailure? = null

        while (postsRead < budget) {
            val want = (budget - postsRead).coerceIn(MIN_PAGE, MAX_PAGE)
            val url = buildString {
                append("https://api.x.com/2/users/").append(userId)
                append("/timelines/reverse_chronological?max_results=").append(want)
                append('&').append(FIELDS)
                sinceId?.let { append("&since_id=").append(it) }
                pageToken?.let { append("&pagination_token=").append(it) }
            }

            when (val outcome = fetch(url, accessToken)) {
                is Fetched.Problem -> {
                    // Text-only pages are still paid pages. `postsRead`, not extracted media, decides
                    // whether this is a partial success.
                    if (postsRead == 0) return@withContext outcome.result
                    warnings += PageWarning.PARTIAL_PAGE
                    partialFailure = outcome.result.toFailure()
                    Log.w(TAG, "stopping after a paid partial read: ${outcome.result}")
                    break
                }
                is Fetched.Page -> {
                    val parsed = outcome.result
                    if (parsed !is PageResult.Ok) {
                        if (postsRead == 0) return@withContext parsed
                        warnings += PageWarning.PARTIAL_PAGE
                        partialFailure = parsed.toFailure()
                        break
                    }
                    if (parsed.postsRead > want) {
                        warnings += PageWarning.SERVER_OVERDELIVERY
                        Log.w(TAG, "server returned ${parsed.postsRead} for max_results=$want")
                    }
                    postsRead += parsed.postsRead
                    seen += parsed.stats.postsSeen
                    recognised += parsed.stats.postsRecognised
                    usersReturned += parsed.stats.usersReturned
                    mediaReturned += parsed.stats.mediaReturned
                    withoutMedia += parsed.stats.postsWithoutMedia
                    dropped += parsed.stats.mediaDropped
                    collected += parsed.items
                    warnings += parsed.warnings
                    if (parsed.partialFailure != null) partialFailure = parsed.partialFailure
                    if (newestId == null) newestId = parsed.newestPostId

                    if (parsed.postsRead == 0) {
                        if (++emptyStreak >= MAX_EMPTY_PAGES) break
                    } else {
                        emptyStreak = 0
                    }

                    pageToken = outcome.nextToken ?: break
                    if (postsRead < budget) delay(PAGE_DELAY_MS)
                }
            }
        }

        Diagnostics.record(
            "timeline",
            "requested=$budget posts=$postsRead users=$usersReturned media=$mediaReturned " +
                "parsed=$recognised/$seen dropped=$dropped",
        )
        PageResult.Ok(
            items = collected,
            newestPostId = newestId,
            postsRead = postsRead,
            stats = PageStats(
                postsSeen = seen,
                postsRecognised = recognised,
                mediaExtracted = collected.size,
                postsWithoutMedia = withoutMedia,
                mediaDropped = dropped,
                usersReturned = usersReturned,
                mediaReturned = mediaReturned,
            ),
            warnings = warnings,
            partialFailure = partialFailure,
        )
    }

    private sealed interface Fetched {
        data class Page(val result: PageResult, val nextToken: String?) : Fetched
        data class Problem(val result: PageResult) : Fetched
    }

    private suspend fun fetch(
        url: String,
        accessToken: suspend () -> String?,
    ): Fetched {
        val token = accessToken() ?: return Fetched.Problem(PageResult.AuthRequired)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                Diagnostics.record(
                    "http ${response.code}",
                    "remaining=${response.header("x-rate-limit-remaining") ?: "-"} " +
                        "limit=${response.header("x-rate-limit-limit") ?: "-"} bytes=${body.length}",
                )
                when (response.code) {
                    401, 403 -> return Fetched.Problem(PageResult.AuthRequired)
                    402 -> return Fetched.Problem(
                        (parseBody(body) as? PageResult.PaymentRequired)
                            ?: PageResult.PaymentRequired("API credits depleted"),
                    )
                    429 -> {
                        val reset = response.header("x-rate-limit-reset")?.toLongOrNull()?.times(1000)
                        return Fetched.Problem(PageResult.RateLimited(reset))
                    }
                    in 500..599 -> return Fetched.Problem(
                        PageResult.Transient("HTTP ${response.code}", requestPossiblyBilled = false),
                    )
                }
                if (!response.isSuccessful) {
                    return Fetched.Problem(
                        parseBody(body).takeUnless { it is PageResult.Ok }
                            ?: PageResult.UpstreamChanged("HTTP ${response.code} returned a page body"),
                    )
                }
                when (val parsed = parseBody(body)) {
                    is PageResult.Ok -> Fetched.Page(parsed, nextToken(body))
                    else -> Fetched.Problem(parsed)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // The request may have reached X and the response may have been lost. Purchase recovery
            // must conservatively reserve it and must not retry automatically.
            Fetched.Problem(PageResult.Transient(t.message ?: t.javaClass.simpleName))
        }
    }

    private fun parseBody(body: String): PageResult =
        runCatching { Normalizer.parse(json.parseToJsonElement(body)) }
            .getOrElse { PageResult.UpstreamChanged("unreadable JSON: ${it.javaClass.simpleName}") }

    private fun nextToken(body: String): String? = runCatching {
        (json.parseToJsonElement(body) as? JsonObject)
            ?.get("meta")
            ?.let { it as? JsonObject }
            ?.get("next_token")
            ?.let { it as? JsonPrimitive }
            ?.content
    }.getOrNull()

    companion object {
        const val MIN_PAGE = 1
        const val MAX_PAGE = 100
        const val PAGE_DELAY_MS = 900L
        private const val MAX_EMPTY_PAGES = 2

        const val FIELDS =
            "expansions=attachments.media_keys,author_id" +
                "&media.fields=url,variants,preview_image_url,duration_ms,alt_text,type,width,height" +
                "&tweet.fields=created_at,possibly_sensitive" +
                "&user.fields=username,name,profile_image_url"

        val json = Json { ignoreUnknownKeys = true }
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofSeconds(30))
            .build()
    }
}

class ApiTimelineReadPort(
    private val reader: ApiV2Reader = ApiV2Reader(),
) : TimelineReadPort {
    override suspend fun read(request: TimelineRequest): TimelineOutcome {
        val result = reader.loadHead(
            budget = request.requestedPosts,
            sinceId = request.sinceId,
            userId = request.accountId,
            accessToken = { request.accessToken },
        )
        return when (result) {
            is PageResult.Ok -> {
                val resources = ResourceCounts(
                    posts = result.postsRead,
                    users = result.stats.usersReturned,
                    media = result.stats.mediaReturned,
                )
                val mappedWarnings = buildSet {
                    if (PageWarning.SERVER_OVERDELIVERY in result.warnings) {
                        add(com.xtv.app.core.purchase.PurchaseWarning.SERVER_OVERDELIVERY)
                    }
                    if (PageWarning.PARTIAL_ERRORS in result.warnings || result.stats.shapeDrift) {
                        add(com.xtv.app.core.purchase.PurchaseWarning.SHAPE_DRIFT)
                    }
                }
                if (result.partialFailure != null || PageWarning.PARTIAL_PAGE in result.warnings) {
                    TimelineOutcome.Partial(
                        items = result.items,
                        newestPostId = result.newestPostId,
                        resources = resources,
                        problem = result.partialFailure.toPurchaseProblem(),
                        sentState =
                            if (
                                result.partialFailure is PageFailure.UpstreamChanged ||
                                (result.partialFailure as? PageFailure.Transient)
                                    ?.requestPossiblyBilled == true
                            ) {
                                RequestSentState.POSSIBLY_SENT
                            } else {
                                RequestSentState.NOT_SENT
                            },
                        warnings = mappedWarnings,
                    )
                } else {
                    TimelineOutcome.Success(
                        items = result.items,
                        newestPostId = result.newestPostId,
                        resources = resources,
                        warnings = mappedWarnings,
                    )
                }
            }
            PageResult.AuthRequired -> TimelineOutcome.Failure(
                PurchaseProblem.AuthenticationRequired,
                RequestSentState.NOT_SENT,
            )
            is PageResult.PaymentRequired -> TimelineOutcome.Failure(
                PurchaseProblem.PaymentRequired,
                RequestSentState.NOT_SENT,
            )
            is PageResult.RateLimited -> TimelineOutcome.Failure(
                PurchaseProblem.RateLimited(result.resetAtMs),
                RequestSentState.NOT_SENT,
            )
            is PageResult.UpstreamChanged -> TimelineOutcome.Failure(
                PurchaseProblem.UpstreamContractChanged,
                // This commonly represents a 2xx response whose resource shape could not be
                // decoded. The request definitely left the device and may have returned resources.
                RequestSentState.POSSIBLY_SENT,
            )
            is PageResult.Transient -> TimelineOutcome.Failure(
                PurchaseProblem.Network,
                if (result.requestPossiblyBilled) {
                    RequestSentState.POSSIBLY_SENT
                } else {
                    RequestSentState.NOT_SENT
                },
            )
        }
    }
}

private fun PageResult.toFailure(): PageFailure = when (this) {
    PageResult.AuthRequired -> PageFailure.AuthRequired
    is PageResult.PaymentRequired -> PageFailure.PaymentRequired(detail)
    is PageResult.RateLimited -> PageFailure.RateLimited(resetAtMs)
    is PageResult.UpstreamChanged -> PageFailure.UpstreamChanged(detail)
    is PageResult.Transient -> PageFailure.Transient(cause, requestPossiblyBilled)
    is PageResult.Ok -> partialFailure
        ?: PageFailure.UpstreamChanged("partial page ended without a semantic failure")
}

private fun PageFailure?.toPurchaseProblem(): PurchaseProblem = when (this) {
    PageFailure.AuthRequired -> PurchaseProblem.AuthenticationRequired
    is PageFailure.PaymentRequired -> PurchaseProblem.PaymentRequired
    is PageFailure.RateLimited -> PurchaseProblem.RateLimited(resetAtMs)
    is PageFailure.UpstreamChanged -> PurchaseProblem.UpstreamContractChanged
    is PageFailure.Transient -> PurchaseProblem.Network
    null -> PurchaseProblem.Unexpected
}
