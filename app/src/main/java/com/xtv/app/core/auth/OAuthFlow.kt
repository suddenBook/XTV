package com.xtv.app.core.auth

import android.net.Uri
import android.util.Base64
import com.xtv.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth 2.0 Authorization Code + PKCE against X, for a **public** client.
 *
 * XTV registers as a "Native App", which X treats as a public client: there is no defensible client
 * secret on a device, so PKCE — not a secret — is what binds the authorization code to this client.
 * The secret X hands out alongside the client id is simply unused here.
 *
 * The redirect URI never has to resolve. The consent page runs inside our own WebView and
 * [LoginScreen][com.xtv.app.ui.auth.LoginScreen] intercepts the navigation to the callback *before*
 * it happens, lifting `?code=` straight out of the URL. That is what makes this work on a TV, where
 * there is no browser to hand a redirect back to and X offers no device-authorization grant.
 */
object OAuthFlow {

    private const val AUTHORIZE = "https://x.com/i/oauth2/authorize"
    private const val TOKEN = "https://api.x.com/2/oauth2/token"

    /**
     * `offline.access` is what yields a refresh token. Without it the session dies in a couple of
     * hours and re-authorising becomes the dominant interaction — on a device with no keyboard.
     * Everything else is read-only: XTV never writes.
     */
    private val SCOPES = listOf("tweet.read", "users.read", "offline.access")

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /** One login attempt's PKCE material. The verifier must outlive the WebView round trip. */
    class Attempt internal constructor(val verifier: String, val state: String) {
        val challenge: String = s256(verifier)
    }

    fun begin(): Attempt = Attempt(verifier = randomUrlSafe(64), state = randomUrlSafe(16))

    fun authorizeUrl(attempt: Attempt, clientId: String): String =
        Uri.parse(AUTHORIZE).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", BuildConfig.X_REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("state", attempt.state)
            .appendQueryParameter("code_challenge", attempt.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()

    /**
     * True when [url] is the redirect we are waiting for. Compared on scheme+authority+path only:
     * the query carries the code and must not participate in the match.
     */
    fun isRedirect(url: String): Boolean {
        val target = Uri.parse(BuildConfig.X_REDIRECT_URI)
        val candidate = Uri.parse(url)
        return candidate.scheme == target.scheme &&
            candidate.authority == target.authority &&
            candidate.path == target.path
    }

    sealed interface Result {
        data class Success(val tokens: Tokens) : Result
        data class Denied(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    /** Exchanges the intercepted redirect for tokens. [attempt] must be the one that started it. */
    suspend fun complete(redirectUrl: String, attempt: Attempt, clientId: String): Result = withContext(Dispatchers.IO) {
        val uri = Uri.parse(redirectUrl)
        uri.getQueryParameter("error")?.let {
            return@withContext Result.Denied(uri.getQueryParameter("error_description") ?: it)
        }
        // A mismatched state means the response did not come from the request we made. Bail rather
        // than exchange it.
        if (uri.getQueryParameter("state") != attempt.state) {
            return@withContext Result.Failed("state mismatch")
        }
        val code = uri.getQueryParameter("code")
            ?: return@withContext Result.Failed("redirect carried no code")

        exchange(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", clientId)
                .add("redirect_uri", BuildConfig.X_REDIRECT_URI)
                .add("code", code)
                .add("code_verifier", attempt.verifier)
                .build(),
        )
    }

    /**
     * Trades a refresh token for a fresh access token.
     *
     * X rotates refresh tokens: the response carries a **new** one and the old is spent. Callers must
     * persist what comes back, or the next refresh fails. This is also why a refresh token baked into
     * a build is only a bootstrap — once used, the stored copy is the authority, and two devices
     * sharing one baked token will knock each other out.
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
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Result.Failed("HTTP ${response.code}: ${body.take(200)}")
            } else {
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
    } catch (t: Throwable) {
        Result.Failed(t.message ?: t.javaClass.simpleName)
    }

    // --- PKCE primitives --------------------------------------------------------------------

    private fun randomUrlSafe(bytes: Int): String =
        Base64.encodeToString(ByteArray(bytes).also(SecureRandom()::nextBytes), URL_SAFE)

    private fun s256(verifier: String): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            URL_SAFE,
        )

    /** RFC 7636 wants unpadded base64url; padding and newlines both break the challenge. */
    private const val URL_SAFE = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
}
