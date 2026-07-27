package com.xtv.app.core.budget

import com.xtv.app.core.diag.Diagnostics
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
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
 * `GET /2/usage/tweets` returns a flat `data.project_usage` count for the current cap period. It does
 * **not** report money: X publishes no billing, balance, or credits endpoint. The app therefore
 * presents this count separately from its resource-based local charge estimate. What this buys is
 * that the *Post count* comes from X rather than from our own tally.
 *
 * Requires **app-only** auth: an OAuth 2.0 user token is rejected outright with
 * `unsupported-authentication`. The bearer for that is the one shown on the app's Keys and tokens
 * page, which is a different credential from the OAuth 2.0 client id/secret.
 */
object UsageApi {

    /**
     * Short timeouts on purpose. The home screen no longer waits for this call, but a stalled socket
     * behind a captive portal would still hold a slot for 20s on OkHttp's defaults, and a spend
     * figure that arrives late is worth nothing.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

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
     * included. That makes it the real bill rather than this app's share of it, which is the point,
     * but it is also why it must never drive the local spending ceiling.
     *
     * Failures are recorded to [Diagnostics] rather than swallowed. With the bearer now mandatory,
     * the likeliest fault is a *present but wrong* one — the token's literal `%2F` and `%3D` invite
     * URL-decoding, which yields a 401 — and that passes every presence check the app can make. An
     * unexplained fall back to the estimate wording would be the only symptom.
     */
    sealed interface LookupResult {
        data class Success(val usage: Usage) : LookupResult
        data object InvalidAuth : LookupResult
        data object Unavailable : LookupResult
    }

    suspend fun postsThisPeriod(appOnlyBearer: String): Usage? =
        (lookup(appOnlyBearer) as? LookupResult.Success)?.usage

    /**
     * Distinguishes a definitive credential rejection from transport, server, and schema failures.
     * Provisioning must not tell an operator to replace a valid bearer merely because X is down.
     */
    suspend fun lookup(appOnlyBearer: String): LookupResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.x.com/2/usage/tweets")
                // The bearer from the console contains literal '%2F' / '%3D' characters. They are
                // part of the token, not percent-encoding — decoding them yields a 401.
                .header("Authorization", "Bearer $appOnlyBearer")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    Diagnostics.record("usage", "authorization rejected")
                    return@use LookupResult.InvalidAuth
                }
                if (!response.isSuccessful) {
                    Diagnostics.record("usage", "HTTP ${response.code} — spend figure is an estimate")
                    return@use LookupResult.Unavailable
                }
                val data = json.parseToJsonElement(response.body.string())
                    .jsonObject["data"]?.jsonObject
                    ?: return@use LookupResult.Unavailable
                val used = data["project_usage"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?.takeIf { it >= 0 }
                    ?: return@use LookupResult.Unavailable
                LookupResult.Success(
                    Usage(
                        posts = used,
                        // A day outside 1..31 would render as "the 0th". Treat it as absent instead.
                        resetDay = data["cap_reset_day"]?.jsonPrimitive?.content?.toIntOrNull()
                            ?.takeIf { it in 1..31 },
                    ),
                )
            }
        } catch (e: CancellationException) {
            // Leaving the screen is not a usage failure; let it cancel.
            throw e
        } catch (e: IOException) {
            Diagnostics.record("usage", "unreachable (${e.javaClass.simpleName})")
            LookupResult.Unavailable
        } catch (e: Exception) {
            Diagnostics.record("usage", "unreadable response (${e.javaClass.simpleName})")
            LookupResult.Unavailable
        }
    }

    data class Usage(val posts: Int, val resetDay: Int?)
}
