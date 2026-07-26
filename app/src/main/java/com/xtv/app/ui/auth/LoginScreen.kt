package com.xtv.app.ui.auth

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.xtv.app.BuildConfig
import com.xtv.app.core.auth.OAuthFlow
import com.xtv.app.core.auth.Tokens

private const val TAG = "XTV-AUTH"

/**
 * Runs X's OAuth consent page in a WebView and lifts the authorization code out of the redirect.
 *
 * The trick that makes this viable on a TV: `shouldOverrideUrlLoading` fires *before* the navigation
 * happens, so we see the full callback URL — code and all — and cancel the load. The registered
 * redirect URI therefore never needs a server behind it, which sidesteps the whole "X has no device
 * authorization grant and a TV has no browser to redirect back into" problem.
 *
 * Two settings are load-bearing and both default to the wrong value:
 *  - `javaScriptEnabled` — off by default. X's login is a JS app; without this the WebView shows the
 *    site's `<noscript>` "browser not supported" page, which reads as X blocking WebViews. It isn't.
 *  - `domStorageEnabled` — off by default; the login flow needs it.
 *
 * Spatial (D-pad) navigation inside the page comes for free: Chromium turns it on for devices with
 * no touchscreen, which is exactly an Android TV. Password entry should come from the platform
 * autofill service rather than the on-screen keyboard — typing an X password on a D-pad is miserable.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onSuccess: (Tokens) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clientId = remember { com.xtv.app.core.auth.Credentials.clientId(context).orEmpty() }
    val attempt = remember { OAuthFlow.begin() }
    var status by remember { mutableStateOf<String?>(null) }
    var redirect by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(redirect) {
        val url = redirect ?: return@LaunchedEffect
        status = "Exchanging authorization code…"
        when (val result = OAuthFlow.complete(url, attempt, clientId)) {
            is OAuthFlow.Result.Success -> {
                Log.i(TAG, "authorised; refresh token present=${result.tokens.refreshToken != null}")
                onSuccess(result.tokens)
            }
            is OAuthFlow.Result.Denied -> status = "Authorization denied: ${result.reason}"
            is OAuthFlow.Result.Failed -> status = "Sign-in failed: ${result.reason}"
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0E))) {
        if (clientId.isBlank()) {
            CenterNote("No client id configured — see README.")
            return@Box
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Present as Chrome-on-Android rather than a WebView: the default UA carries
                    // "; wv", and sites do sniff for it.
                    settings.userAgentString =
                        WebSettings.getDefaultUserAgent(ctx).replace("; wv", "")
                    // The bot-challenge iframe is cross-origin and needs third-party cookies.
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val url = request.url.toString()
                            if (!OAuthFlow.isRedirect(url)) return false
                            // Swallow the navigation: nothing is listening on the callback address,
                            // and letting it proceed would just show a connection error.
                            Log.i(TAG, "intercepted redirect")
                            redirect = url
                            return true
                        }
                    }
                    loadUrl(OAuthFlow.authorizeUrl(attempt, clientId))
                }
            },
        )

        status?.let {
            Column(
                Modifier.align(Alignment.BottomStart).padding(48.dp),
            ) {
                Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text("Press Back to cancel", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }

    androidx.activity.compose.BackHandler { onCancel() }
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Color.White)
    }
}
