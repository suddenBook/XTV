package com.xtv.app.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Refresh-only OAuth client.
 *
 * Authorization happens on a trusted operator machine. XTV deliberately has no embedded browser,
 * authorization URL, redirect handler, or client secret; secure adb provisioning supplies the
 * resulting refresh token to a DUMP-protected component.
 */
object OAuthFlow {
    private const val TOKEN = "https://api.x.com/2/oauth2/token"

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        data class Success(val tokens: Tokens) : Result
        data class Denied(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Trades a refresh token for a fresh access token.
     *
     * X rotates refresh tokens: the response carries a **new** one and the old is spent. Callers must
     * persist what comes back before doing further network work, or the next refresh fails.
     */
    suspend fun refresh(refreshToken: String, clientId: String): Result = withContext(Dispatchers.IO) {
        exchange(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .build(),
        )
    }

    private fun exchange(form: FormBody): Result = try {
        val request = Request.Builder().url(TOKEN).post(form).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                when (response.code) {
                    408, 425, 429 -> Result.Failed("HTTP ${response.code}")
                    in 400..499 -> Result.Denied("HTTP ${response.code}")
                    else -> Result.Failed("HTTP ${response.code}")
                }
            } else {
                val body = response.body.string()
                val obj = json.parseToJsonElement(body).jsonObject
                val access = obj["access_token"]?.jsonPrimitive?.content
                if (access.isNullOrBlank()) {
                    Result.Failed("token response carried no access_token")
                } else {
                    val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 7200L
                    Result.Success(
                        Tokens(
                            accessToken = access,
                            refreshToken = obj["refresh_token"]?.jsonPrimitive?.content,
                            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000,
                        ),
                    )
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Result.Failed(t.message ?: t.javaClass.simpleName)
    }
}
