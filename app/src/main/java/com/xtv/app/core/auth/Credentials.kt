package com.xtv.app.core.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xtv.app.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.credStore by preferencesDataStore("xtv_credentials")

/**
 * Where the X app credentials live — on the device, never in the APK.
 *
 * X bills API usage to the **owner of the developer app**, and OAuth does not move that invoice to
 * whoever signs in. A published build with a client id compiled into it would therefore spend its
 * author's credits for every stranger who installed it, and could drain their balance until their
 * own app stopped working. So the shipped APK carries nothing, and each user provides their own:
 *
 * ```
 * adb shell am start -n com.xtv.app/.MainActivity \
 *     --es client_id <id> --es refresh_token <token> [--es bearer <app-only bearer>]
 * ```
 *
 * A build-time value in `local.properties` is still honoured, for a private build someone makes for
 * their own TV; it is simply never set in anything published.
 *
 * - **client id** — identifies the app. Required. Not secret for a public client.
 * - **refresh token** — the user's own session, obtained by authorising once elsewhere.
 * - **app-only bearer** — optional, and only used to read X's own usage meter so the spend figure
 *   is X's count rather than a local tally.
 *
 * The OAuth 2.0 *client secret* is deliberately absent: a Native App is a public client and PKCE,
 * not a secret, is what binds the authorisation code. XTV never has a use for it.
 */
object Credentials {

    suspend fun setClientId(context: Context, value: String) {
        context.credStore.edit { it[CLIENT_ID] = value.trim() }
    }

    suspend fun setAppOnlyBearer(context: Context, value: String) {
        context.credStore.edit { it[BEARER] = value.trim() }
    }

    suspend fun appOnlyBearer(context: Context): String? =
        context.credStore.data.first()[BEARER]?.takeIf { it.isNotBlank() }

    /**
     * Blocking because it is read while building the OAuth URL, deep inside code that is not
     * suspending. It is a single small DataStore read behind an in-memory cache after the first hit.
     */
    fun clientId(context: Context): String? {
        cachedClientId?.let { return it }
        val stored = runBlocking { context.credStore.data.first()[CLIENT_ID] }
        val value = stored?.takeIf { it.isNotBlank() }
            ?: BuildConfig.X_CLIENT_ID.takeIf { it.isNotBlank() }
        cachedClientId = value
        return value
    }

    @Volatile
    private var cachedClientId: String? = null

    private val CLIENT_ID = stringPreferencesKey("client_id")
    private val BEARER = stringPreferencesKey("bearer")
}
