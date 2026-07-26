package com.xtv.app.core.auth

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xtv.app.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.credStore by preferencesDataStore("xtv_credentials")
private const val TAG = "XTV-AUTH"

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
 * All three are **required**:
 *
 * - **client id** — identifies the app. Not secret for a public client.
 * - **refresh token** — the user's own session, obtained by authorising once elsewhere.
 * - **app-only bearer** — reads X's own usage meter, so the spend figure is X's count rather than a
 *   local guess. It is required because the local tally is an upper bound over a *calendar month*
 *   while X bills over a period ending on `cap_reset_day`; the two are not comparable, and an app
 *   that spends real money should not have to guess at the number it shows.
 *
 * The OAuth 2.0 *client secret* is deliberately absent: a Native App is a public client and PKCE,
 * not a secret, is what binds the authorisation code. XTV never has a use for it.
 */
object Credentials {

    /**
     * Stores credentials arriving as intent extras, blocking until they have actually landed.
     *
     * This has to be synchronous. The injected values are read moments later by the composition, and
     * when both sides were merely *launched* the read could win — leaving a freshly provisioned
     * install on the setup screen for the rest of the process, because the effect that reads them
     * runs once and never re-evaluates. A published APK carries no build-time client id to fall back
     * on, so there was nothing to hide the race behind.
     *
     * The cost is one DataStore write on the cold-start path of a launch that carried extras, which
     * is a provisioning run and not the common case.
     */
    fun injectBlocking(context: Context, clientId: String?, bearer: String?) {
        if (clientId == null && bearer == null) return
        runCatching {
            runBlocking {
                clientId?.let { setClientId(context, it) }
                bearer?.let { setAppOnlyBearer(context, it) }
            }
        }.onFailure {
            // Reaching here means the preference file is unreadable. Letting it escape would turn a
            // bad provisioning run into a crash on launch; the setup screen is the better answer.
            Log.w(TAG, "could not store injected credentials: ${it.message}")
        }
    }

    suspend fun setClientId(context: Context, value: String) {
        val trimmed = value.trim()
        context.credStore.edit { it[CLIENT_ID] = trimmed }
        // Keep the cache honest. A repeat `am start` builds a new Activity in the *same* process, so
        // a stale memoised id would otherwise outlive the value it was read from.
        trimmed.takeIf { it.isNotBlank() }?.let { cachedClientId = it }
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
