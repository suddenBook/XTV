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
import com.xtv.app.core.budget.UsageApi
import com.xtv.app.core.budget.UsageCache
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
        data class NeedsSetup(val missing: MissingCredential) : Screen
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

        // Credential injection. Nothing ships inside the APK: X bills the owner of the developer app,
        // so a published build carrying anyone's client id would spend that person's credits for every
        // user. All three arrive over adb and are stored on the device only:
        //   adb shell am start -n com.xtv.app/.MainActivity \
        //       --es client_id <id> --es refresh_token <token> --es bearer <app-only bearer>
        //
        // Blocking, and before setContent, on purpose — see Credentials.injectBlocking. Launching this
        // as a coroutine left the write racing the composition's read of the same values.
        Credentials.injectBlocking(
            context = this,
            clientId = intent?.getStringExtra("client_id")?.takeIf { it.isNotBlank() },
            bearer = intent?.getStringExtra("bearer")?.takeIf { it.isNotBlank() },
        )
        val injectedRefresh = intent?.getStringExtra("refresh_token")?.takeIf { it.isNotBlank() }

        // Consumed, so drop them. Otherwise the bearer sits in the Intent for the life of the
        // activity — visible to `dumpsys activity` — and is replayed verbatim if the process is
        // killed and restored, re-running a token exchange that was already spent.
        intent?.let { launchIntent ->
            listOf("client_id", "bearer", "refresh_token").forEach(launchIntent::removeExtra)
            setIntent(launchIntent)
        }

        setContent {
            XtvTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val store = remember { TokenStore(context) }
                val reelStore = remember { ReelStore(context) }
                val guard = remember { SpendGuard(context) }
                val usageCache = remember { UsageCache.shared }

                var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
                var reel by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
                var startIndex by remember { mutableIntStateOf(0) }
                // A fixture run is a playback harness with no credentials behind it, so leaving it must
                // leave the app. Falling through to Home would be a way past the credential gate.
                var fixtureRun by remember { mutableStateOf(false) }

                // Four independent writers, one derived value. Assembling HomeState by copying the
                // previous one let a slow usage response overwrite a note written while it was in
                // flight — "Building…" would vanish mid-build. Disjoint sources make that unsayable.
                var resume by remember { mutableStateOf<ReelStore.Saved?>(null) }
                var localSpend by remember {
                    mutableStateOf(SpendGuard.State(0, 0.0, SpendGuard.DEFAULT_CAP_USD))
                }
                var usage by remember { mutableStateOf<UsageApi.Usage?>(null) }
                var statusNote by remember { mutableStateOf<String?>(null) }

                val home = remember(resume, localSpend, usage, statusNote) {
                    val spend = localSpend.mergedWith(usage)
                    HomeState(
                        resumeRemaining = resume?.remaining,
                        resumeTotal = resume?.items?.size,
                        spentText = spend.spentText,
                        capText = spend.capText,
                        budgetExceeded = spend.exceeded,
                        spendAuthoritative = spend.authoritative,
                        resetDay = spend.resetDay,
                        note = statusNote,
                    )
                }

                /**
                 * Rebuild the home card from local state. Never blocks on the network.
                 *
                 * X's usage meter is fetched alongside and folded in whenever it lands. It used to be
                 * awaited here, which put a metered HTTP round trip in front of the first frame of
                 * every launch — and with the bearer now required, that would be every user.
                 */
                suspend fun refreshHome(note: String? = null) {
                    resume = reelStore.loadReel()
                    localSpend = guard.state()
                    statusNote = note
                    scope.launch { usage = usageCache.get(Credentials.appOnlyBearer(context)) }
                }

                LaunchedEffect(Unit) {
                    val clientId = Credentials.clientId(context)
                    val bearer = Credentials.appOnlyBearer(context)

                    // Spend the injected refresh token before deciding, so a provisioning run lands on
                    // Home rather than the consent page it just made unnecessary.
                    if (!clientId.isNullOrBlank() && injectedRefresh != null && store.load() == null) {
                        android.util.Log.i(TAG, "exchanging injected refresh token")
                        when (val r = OAuthFlow.refresh(injectedRefresh, clientId)) {
                            is OAuthFlow.Result.Success -> store.save(r.tokens)
                            else -> android.util.Log.w(TAG, "injected token rejected: $r")
                        }
                    }

                    // ★ Cold start reads local state and, at most, refreshes a token. Nothing here
                    // buys posts; every fetch sits behind a keypress that has already stated its price.
                    val start = decideStart(clientId, bearer, fixtureName, store.load() != null)
                    // Say where we landed and why. A provisioning run is otherwise unobservable from
                    // the couch — the setup screen looks identical whether a credential is genuinely
                    // absent or merely failed to arrive, and guessing between those cost a whole
                    // debugging session once already. tools/provision.sh reads this line.
                    android.util.Log.i(TAG, "start: $start")
                    screen = when (start) {
                        is Start.NeedsSetup -> Screen.NeedsSetup(start.missing)
                        is Start.Fixture -> {
                            fixtureRun = true
                            reel = ReelPolicy.buildReel(FixtureSource.load(context, start.name))
                            Screen.Reel
                        }
                        Start.NeedsLogin -> { refreshHome(); Screen.NeedsLogin }
                        Start.Home -> { refreshHome(); Screen.Home }
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
                                // The one moment the spend line is being watched for movement; a
                                // minute-old cached figure would read as the app losing track.
                                usageCache.invalidate()
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

                when (val current = screen) {
                    Screen.Loading -> Unit
                    is Screen.NeedsSetup -> SetupGuideScreen(current.missing)
                    Screen.NeedsLogin -> LoginScreen(
                        onSuccess = { fresh: Tokens ->
                            lifecycleScope.launch {
                                store.save(fresh); refreshHome(); screen = Screen.Home
                            }
                        },
                        // Backing out of the consent page leaves the app. There is nothing behind it:
                        // the credentials are already present, so a setup screen would have nothing
                        // to ask for.
                        onCancel = { finish() },
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
                        onExit = {
                            if (fixtureRun) finish()
                            else scope.launch { refreshHome(); screen = Screen.Home }
                        },
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
