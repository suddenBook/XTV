package com.xtv.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.xtv.app.core.auth.Credentials
import com.xtv.app.core.auth.OAuthFlow
import com.xtv.app.core.auth.SessionManager
import com.xtv.app.core.auth.TokenStore
import com.xtv.app.core.auth.Tokens
import com.xtv.app.core.budget.SpendGuard
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.PageResult
import com.xtv.app.core.source.ApiV2Source
import com.xtv.app.data.FixtureSource
import com.xtv.app.data.ReelStore
import com.xtv.app.ui.auth.LoginScreen
import com.xtv.app.ui.debug.DiagnosticsScreen
import com.xtv.app.ui.grid.GridScreen
import com.xtv.app.ui.home.HomeScreen
import com.xtv.app.ui.home.HomeState
import com.xtv.app.ui.home.ReelSize
import com.xtv.app.ui.setup.SetupGuideScreen
import com.xtv.app.ui.theme.XtvTheme
import com.xtv.app.ui.viewer.ReelPolicy
import com.xtv.app.ui.viewer.ReelViewer
import kotlinx.coroutines.launch

private const val TAG = "XTV"

class MainActivity : ComponentActivity() {

    private sealed interface Screen {
        data object Loading : Screen
        data object NeedsSetup : Screen
        data object NeedsLogin : Screen
        data object Home : Screen
        data object Reel : Screen
        data object Grid : Screen
        data object Diagnostics : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep this app's content out of the Overview (recents) thumbnail — it's a living-room device.
        //
        // Release only. Measured on a Google TV Streamer (Android 14): with this enabled, `adb exec-out
        // screencap` returns a fully black frame even though the app renders normally on the panel. The
        // documented contract says it should only affect the Overview representation, but this device
        // applies it to system captures too. Screencap is the only way to see what the UI actually looks
        // like on a TV, so debug builds keep it.
        if (!BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }

        // A long video can outlast the system's idle timer, and Android TV's Ambient Mode takes the
        // screen after ten minutes of "inactivity" — which a reel, taking no input, looks like.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        //   adb shell am start -n com.xtv.app/.MainActivity --es fixture dead_links.json
        // Replays a captured response with no token and no spend.
        val fixtureName = intent?.getStringExtra("fixture")?.takeIf { it.isNotBlank() }

        // Credential injection, for the cases where the on-device consent page is not the way in:
        //   adb shell am start -n com.xtv.app/.MainActivity --es refresh_token <token>
        // Same path a CI build uses when a refresh token is baked in, so "installs already signed in"
        // and this debug hook share one implementation.
        // Nothing ships inside the APK. X bills the owner of the developer app, so a published
        // build carrying anyone's client id would spend that person's credits for every user. Both
        // values arrive over adb at install time and are stored on the device only:
        //   adb shell am start -n com.xtv.app/.MainActivity \
        //       --es client_id <id> --es refresh_token <token> [--es bearer <app-only bearer>]
        lifecycleScope.launch {
            intent?.getStringExtra("client_id")?.takeIf { it.isNotBlank() }
                ?.let { Credentials.setClientId(this@MainActivity, it) }
            intent?.getStringExtra("bearer")?.takeIf { it.isNotBlank() }
                ?.let { Credentials.setAppOnlyBearer(this@MainActivity, it) }
        }
        val injectedRefresh = intent?.getStringExtra("refresh_token")?.takeIf { it.isNotBlank() }

        setContent {
            XtvTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val store = remember { TokenStore(context) }
                val reelStore = remember { ReelStore(context) }
                val guard = remember { SpendGuard(context) }

                var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
                var reel by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
                var startIndex by remember { mutableIntStateOf(0) }
                var home by remember { mutableStateOf(HomeState(null, null, "$0.00", "$20.00", false)) }

                suspend fun refreshHome(note: String? = null) {
                    val saved = reelStore.loadReel()
                    // With an app-only bearer configured this is X's own consumption count; without
                    // one it falls back to the local tally, which is an upper bound.
                    val spend = guard.stateWithUsage(Credentials.appOnlyBearer(context))
                    home = HomeState(
                        resumeRemaining = saved?.remaining,
                        resumeTotal = saved?.items?.size,
                        spentText = spend.spentText,
                        capText = spend.capText,
                        budgetExceeded = spend.exceeded,
                        spendAuthoritative = spend.authoritative,
                        note = note,
                    )
                }

                LaunchedEffect(Unit) {
                    when {
                        Credentials.clientId(context).isNullOrBlank() -> screen = Screen.NeedsSetup
                        // A fixture run is for exercising playback: offline, free, straight in.
                        fixtureName != null -> {
                            reel = ReelPolicy.buildReel(FixtureSource.load(context, fixtureName))
                            screen = Screen.Reel
                        }
                        else -> {
                            if (store.load() == null && injectedRefresh != null) {
                                android.util.Log.i(TAG, "exchanging injected refresh token")
                                when (val r = OAuthFlow.refresh(injectedRefresh, Credentials.clientId(context).orEmpty())) {
                                    is OAuthFlow.Result.Success -> store.save(r.tokens)
                                    else -> android.util.Log.w(TAG, "injected token rejected: $r")
                                }
                            }
                            // ★ Cold start touches the network for nothing but a possible token
                            // refresh. The home card is built from local state alone; every fetch
                            // sits behind a keypress that has already stated its price.
                            refreshHome()
                            screen = if (store.load() == null) Screen.NeedsLogin else Screen.Home
                        }
                    }
                }

                fun buildReel(size: ReelSize) {
                    scope.launch {
                        refreshHome(note = getString(R.string.note_building))
                        val allowed = guard.allowance(size.posts)
                        if (allowed < 5) {
                            refreshHome(note = getString(R.string.note_budget_short))
                            return@launch
                        }
                        val source = ApiV2Source(SessionManager(store, Credentials.clientId(context).orEmpty()))
                        when (val page = source.loadHead(allowed, reelStore.sinceId())) {
                            is PageResult.Ok -> {
                                guard.record(page.postsRead)
                                if (page.stats.shapeDrift) {
                                    // Never skip silently: an unparsed entry means the upstream
                                    // changed, and it looks exactly like "nothing new" if unreported.
                                    android.util.Log.w(
                                        TAG,
                                        "SHAPE DRIFT ${page.stats.postsRecognised}/${page.stats.postsSeen}",
                                    )
                                }
                                val built = ReelPolicy.buildReel(page.items)
                                if (built.isEmpty()) {
                                    refreshHome(note = getString(R.string.note_no_videos, page.postsRead))
                                } else {
                                    // Freeze it: a later refresh must not insert items ahead of
                                    // whatever is on screen.
                                    reelStore.saveReel(built, page.newestPostId)
                                    reel = built
                                    startIndex = 0
                                    screen = Screen.Reel
                                }
                            }
                            is PageResult.AuthRequired -> screen = Screen.NeedsLogin
                            is PageResult.PaymentRequired ->
                                refreshHome(note = getString(R.string.note_credits, page.detail))
                            is PageResult.RateLimited -> refreshHome(note = getString(R.string.note_rate_limited))
                            is PageResult.UpstreamChanged ->
                                refreshHome(note = getString(R.string.note_upstream, page.detail))
                            is PageResult.Transient -> refreshHome(note = getString(R.string.note_transient, page.cause))
                        }
                    }
                }

                when (screen) {
                    Screen.Loading -> Unit
                    Screen.NeedsSetup -> SetupGuideScreen()
                    Screen.NeedsLogin -> LoginScreen(
                        onSuccess = { fresh: Tokens ->
                            lifecycleScope.launch {
                                store.save(fresh); refreshHome(); screen = Screen.Home
                            }
                        },
                        onCancel = { screen = Screen.NeedsSetup },
                    )
                    Screen.Home -> HomeScreen(
                        state = home,
                        onBuild = ::buildReel,
                        onResume = {
                            scope.launch {
                                reelStore.loadReel()?.let {
                                    reel = it.items; startIndex = it.position; screen = Screen.Reel
                                }
                            }
                        },
                        onOpenGrid = {
                            scope.launch {
                                reelStore.loadReel()?.let { reel = it.items; screen = Screen.Grid }
                            }
                        },
                        onOpenDiagnostics = { screen = Screen.Diagnostics },
                        onExitApp = { finish() },
                    )
                    Screen.Diagnostics -> DiagnosticsScreen(
                        header = getString(R.string.history_header, home.spentText, reel.size),
                        onBack = { screen = Screen.Home },
                    )
                    Screen.Grid -> GridScreen(
                        items = reel,
                        onPlay = { i -> startIndex = i; screen = Screen.Reel },
                        onBack = { screen = Screen.Home },
                    )
                    Screen.Reel -> ReelViewer(
                        items = reel,
                        startIndex = startIndex,
                        onExit = { scope.launch { refreshHome(); screen = Screen.Home } },
                        // Throttled: a reel changes item every few seconds and each write is a
                        // DataStore fsync. Losing at most one position on a hard kill is a fair
                        // trade for not writing to disk on every transition.
                        onProgress = { i ->
                            if (i % 3 == 0 || i == reel.lastIndex) {
                                scope.launch { reelStore.savePosition(i) }
                            }
                        },
                    )
                }
            }
        }
    }
}
