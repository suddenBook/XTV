package com.xtv.app.core.budget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.YearMonth

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

    /** Posts consumed so far in the current calendar month, or null if unavailable. */
    suspend fun postsThisMonth(appOnlyBearer: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.x.com/2/usage/tweets?days=31")
                .header("Authorization", "Bearer $appOnlyBearer")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val daily = root["data"]?.jsonObject?.get("daily_project_usage")?.jsonArray
                    ?: return@use null
                val month = YearMonth.now().toString() // "2026-07"
                daily.sumOf { day ->
                    val obj = day.jsonObject
                    val date = obj["date"]?.jsonPrimitive?.content.orEmpty()
                    // Only this calendar month: the window is 31 days and straddles the boundary.
                    if (date.startsWith(month)) {
                        obj["tweets_consumed"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    } else {
                        0
                    }
                }
            }
        }.getOrNull()
    }
}
