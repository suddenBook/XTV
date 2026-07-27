package com.xtv.app.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves the signed-in account's numeric id, which every timeline path needs
 * (`/2/users/{id}/timelines/...`).
 *
 * Called once and cached in the encrypted account binding. It is billed like any other User read,
 * so it must not run on every launch.
 */
internal object UserLookup {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun me(accessToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.x.com/2/users/me")
                .header("Authorization", "Bearer $accessToken")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.parseToJsonElement(response.body.string())
                    .jsonObject["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }
}
