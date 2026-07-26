package com.xtv.app.core.auth

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
 * Called once and cached in [TokenStore]; it is billed like any other read, so it should not run on
 * every launch.
 */
internal object UserLookup {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun me(accessToken: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.x.com/2/users/me")
                .header("Authorization", "Bearer $accessToken")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.parseToJsonElement(response.body?.string().orEmpty())
                    .jsonObject["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            }
        }.getOrNull()
    }
}
