package com.xtv.app.core.auth

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "XTV-AUTH"

/**
 * Owns the live session: hands out a valid access token, refreshing when needed.
 *
 * The mutex is not decorative. The reel can have two requests in flight, and both would otherwise
 * notice the token is stale at the same moment and refresh concurrently — and because X **rotates**
 * refresh tokens, the second refresh would present a token the first one already spent, killing the
 * session. Single-flight refresh is what stops a routine expiry from logging the user out.
 */
class SessionManager(private val store: TokenStore, private val clientId: String) {

    private val mutex = Mutex()
    @Volatile private var cached: Tokens? = null

    suspend fun accessToken(): String? = mutex.withLock {
        val current = cached ?: store.load()?.also { cached = it } ?: return null
        if (!current.isExpired()) return current.accessToken

        val refresh = current.refreshToken ?: run {
            Log.w(TAG, "access token expired and no refresh token — session is over")
            return null
        }
        when (val result = OAuthFlow.refresh(refresh, clientId)) {
            is OAuthFlow.Result.Success -> {
                // Carry the account id forward: the refresh response does not repeat it.
                val fresh = result.tokens.copy(accountId = current.accountId)
                cached = fresh
                store.save(fresh)
                Log.i(TAG, "refreshed session"); com.xtv.app.core.diag.Diagnostics.record("session", "已刷新")
                fresh.accessToken
            }
            is OAuthFlow.Result.Denied, is OAuthFlow.Result.Failed -> {
                Log.w(TAG, "refresh failed: $result"); com.xtv.app.core.diag.Diagnostics.record("session", "刷新失败 — 需重新登录")
                null
            }
        }
    }

    /** The authenticated user's numeric id, resolved once and remembered. */
    suspend fun accountId(): String? {
        cached?.accountId?.let { return it }
        store.load()?.accountId?.let { return it }
        val token = accessToken() ?: return null
        val id = UserLookup.me(token) ?: return null
        val updated = (cached ?: store.load())?.copy(accountId = id) ?: return null
        cached = updated
        store.save(updated)
        return id
    }

    suspend fun signOut() {
        mutex.withLock {
            cached = null
            store.clear()
        }
    }
}
