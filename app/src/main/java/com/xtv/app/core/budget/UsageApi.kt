package com.xtv.app.core.budget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * X's own consumption meter — the authoritative alternative to counting locally.
 *
 * `GET /2/usage/tweets` reports `tweets_consumed` per day plus the monthly `project_cap`. It does
 * **not** report money: X publishes no billing, balance, or credits endpoint, so dollars are still
 * derived by multiplying by the published per-post price. What this buys is that the *count* comes
 * from X rather than from our own tally, which catches any drift between the two — X dedupes repeat
 * reads of the same resource within a UTC day, so a local tally is an upper bound.
 *
 * Requires **app-only** auth: an OAuth 2.0 user token is rejected outright with
 * `unsupported-authentication`. The bearer for that is the one shown on the app's Keys and tokens
 * page, which is a different credential from the OAuth 2.0 client id/secret. It is optional — with
 * no bearer the app falls back to its local estimate and says so.
 */
object UsageApi {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Posts consumed so far in the current cap period, or null if unavailable.
     *
     * Two things the docs do not make obvious, both confirmed against the live endpoint:
     *  - The response is a flat `data.project_usage` **string**, not the `daily_project_usage`
     *    breakdown the reference page describes, and `?days=` changes nothing.
     *  - The period resets on `cap_reset_day` (26 in the account this was checked against), **not**
     *    on the 1st. So this figure and a calendar-month tally count different windows.
     *
     * It is also *project*-wide: anything else using the same credentials — a script, a CLI — is
     * included. That makes it the real bill rather than this app's share of it, which is the point.
     */
    suspend fun postsThisPeriod(appOnlyBearer: String): Usage? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.x.com/2/usage/tweets")
                // The bearer from the console contains literal '%2F' / '%3D' characters. They are
                // part of the token, not percent-encoding — decoding them yields a 401.
                .header("Authorization", "Bearer $appOnlyBearer")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val data = json.parseToJsonElement(response.body?.string().orEmpty())
                    .jsonObject["data"]?.jsonObject ?: return@use null
                val used = data["project_usage"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: return@use null
                Usage(
                    posts = used,
                    capPosts = data["project_cap"]?.jsonPrimitive?.content?.toIntOrNull(),
                    resetDay = data["cap_reset_day"]?.jsonPrimitive?.content?.toIntOrNull(),
                )
            }
        }.getOrNull()
    }

    data class Usage(val posts: Int, val capPosts: Int?, val resetDay: Int?)
}
