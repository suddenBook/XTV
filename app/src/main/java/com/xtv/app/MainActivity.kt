package com.xtv.app

import android.content.Context
import android.content.Intent
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtv.app.core.auth.ProvisioningRequest
import com.xtv.app.core.diag.Diagnostics
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.motionOnly
import com.xtv.app.core.purchase.DispatchResult
import com.xtv.app.core.purchase.PurchaseCommand
import com.xtv.app.core.purchase.PurchaseOperation
import com.xtv.app.core.purchase.PurchaseOutcome
import com.xtv.app.core.purchase.PurchaseProblem
import com.xtv.app.core.purchase.PurchaseReadiness
import com.xtv.app.core.purchase.PurchaseSnapshot
import com.xtv.app.core.purchase.PurchasedReel
import com.xtv.app.core.purchase.ReelStatus
import com.xtv.app.core.storage.PrivateStateRead
import com.xtv.app.ui.common.LoadingScreen
import com.xtv.app.ui.debug.DiagnosticsScreen
import com.xtv.app.ui.grid.GridScreen
import com.xtv.app.ui.home.HomeScreen
import com.xtv.app.ui.notice.Notice
import com.xtv.app.ui.notice.Notices
import com.xtv.app.ui.settings.SettingsScreen
import com.xtv.app.ui.setup.SetupGuideScreen
import com.xtv.app.ui.theme.XtvTheme
import com.xtv.app.ui.viewer.ReelViewer
import java.util.UUID
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private sealed interface Screen {
        data object Loading : Screen
        data class NeedsSetup(val missing: MissingCredential) : Screen
        data object Home : Screen
        data object Reel : Screen
        data object Grid : Screen

        /** Money and the two erasures. Split from [Diagnostics]: different question, different visit. */
        data object Settings : Screen
        data object Diagnostics : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Private timelines, media URLs and billing/account state must never enter screenshots,
        // recents thumbnails, screen recording, or non-secure displays in a distributed build.
        if (!BuildConfig.DEBUG) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Release ignores fixture extras entirely. Debug uses reflection so fixture code and assets
        // can live only in src/debug and be absent from the release artifact.
        val fixtureName = if (BuildConfig.DEBUG) {
            intent?.getStringExtra(DEBUG_FIXTURE_EXTRA)?.takeIf(String::isNotBlank)
        } else {
            null
        }
        // A named scenario stands a whole screen up — including the ones that need a ledger, an
        // offer set or a failure to exist at all — without a network request or a charge.
        val scenarioName = if (BuildConfig.DEBUG && fixtureName == null) {
            intent?.getStringExtra(DEBUG_SCENARIO_EXTRA)?.takeIf(String::isNotBlank)
        } else {
            null
        }
        // Preserve the original operator workflow: credential extras sent to MainActivity are
        // forwarded into the same transactional provisioning path as tools/provision.sh.
        val legacyProvisioning = if (fixtureName == null && scenarioName == null) {
            legacyProvisioningIntent(intent)
        } else {
            null
        }
        // Scrub the source Intent once the values required by either supported launch mode have
        // been copied.
        intent?.replaceExtras(Bundle())
        setIntent(intent)

        // Every one of these is an ordinary type from the production model, so nothing about the
        // scaffolding leaks into a release build: `scenarioName` is null there, R8 folds the
        // BuildConfig branch, and the class it reflects on does not exist in the artifact.
        val scenarioScreen: String? = scenarioName?.let { debugScenario("screen", it) }
        val scenarioItems: List<MediaItem> =
            scenarioName?.let { debugScenario<List<MediaItem>>("items", this, it) }.orEmpty()
        val scenarioSnapshot: PurchaseSnapshot? =
            scenarioName?.let { debugScenario("snapshot", it, scenarioItems.size) }
        val scenarioNotice: Notice? = scenarioName?.let { debugScenario("notice", it) }
        val scenarioMissing: MissingCredential? = scenarioName
            ?.let { debugScenario<String>("missing", it) }
            ?.let { runCatching { MissingCredential.valueOf(it) }.getOrNull() }
        val scenarioNextIndex: Int =
            scenarioName?.let { debugScenario<Int>("nextIndex", it, scenarioItems.size) } ?: 0
        val scenarioPartial: Boolean =
            scenarioName?.let { debugScenario<Boolean>("partial", it) } ?: false
        scenarioName?.let { debugScenario<Unit>("seedDiagnostics", it) }

        val graph = (application as XtvApplication).graph
        setContent {
            XtvTheme {
                val scope = rememberCoroutineScope()
                val live by graph.purchases.state.collectAsStateWithLifecycle()
                // A scenario replaces the whole snapshot, so every screen below is the real
                // composable reading a real PurchaseSnapshot — just one that nobody paid for.
                val purchase = scenarioSnapshot ?: live

                var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
                var currentReel by remember { mutableStateOf<PurchasedReel?>(null) }
                var startIndex by remember { mutableIntStateOf(0) }
                var fixtureRun by remember { mutableStateOf(false) }
                var notice by remember { mutableStateOf<Notice?>(null) }
                var setupResetError by remember { mutableStateOf<String?>(null) }

                // Nothing has failed yet, so there is nothing to diagnose and no entry for it.
                val diagnosticsAvailable = remember(purchase.revision, purchase.operation) {
                    Diagnostics.snapshot().isNotEmpty()
                }

                // One usage read per launch, in the background, once local state is readable.
                //
                // This is a deliberate departure from the older "zero network requests on cold
                // launch" rule, which is now documented as "no *timeline* request on cold launch".
                // The distinction that matters is that this endpoint is metering, not content: it
                // fetches nothing to watch and cannot start a paid timeline read. Settings used to
                // open on a stale count with a Refresh button as the only way to fix it, which put
                // the work of keeping the number honest on the person reading it.
                //
                // Sync, not Refresh: nobody asked for this, so it must not claim the screen. The
                // Refresh command publishes a running operation, which on a cold launch put "Getting
                // videos…" over a disabled home screen for the length of two network timeouts.
                var usageRefreshed by remember { mutableStateOf(false) }
                LaunchedEffect(purchase.readiness) {
                    if (fixtureName != null || scenarioName != null || usageRefreshed) {
                        return@LaunchedEffect
                    }
                    if (purchase.readiness == PurchaseReadiness.READY) {
                        usageRefreshed = true
                        graph.purchases.dispatch(PurchaseCommand.SyncProjectUsage)
                    }
                }

                suspend fun loadSavedReel(): PurchasedReel? =
                    runCatching { graph.purchaseState.read().currentReel }.getOrNull()

                suspend fun routeFromPrivateState() {
                    when (val loaded = graph.privateState.read()) {
                        is PrivateStateRead.Unavailable -> {
                            screen = Screen.NeedsSetup(MissingCredential.PRIVATE_STATE)
                        }
                        is PrivateStateRead.Ready -> {
                            val state = loaded.state
                            if (state.provisionCandidate != null) {
                                screen = Screen.NeedsSetup(MissingCredential.PROVISIONING)
                                return
                            }
                            val start = decideStart(
                                clientId = state.credentials?.clientId,
                                bearer = state.credentials?.appOnlyBearer,
                                fixture = null,
                                hasSession = state.session != null && state.account != null,
                            )
                            screen = when (start) {
                                is Start.NeedsSetup -> Screen.NeedsSetup(start.missing)
                                is Start.Fixture -> error("fixture is handled before private state")
                                Start.Home -> Screen.Home
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (fixtureName != null) {
                        fixtureRun = true
                        val items = loadDebugFixture(this@MainActivity, fixtureName).motionOnly()
                        currentReel = PurchasedReel(
                            id = "debug-fixture",
                            items = items,
                            status = ReelStatus.IN_PROGRESS,
                            nextIndex = 0,
                        )
                        screen = Screen.Reel
                    } else if (scenarioScreen != null) {
                        fixtureRun = true
                        if (scenarioItems.isNotEmpty()) {
                            currentReel = PurchasedReel(
                                id = "debug-batch",
                                items = scenarioItems,
                                status = ReelStatus.IN_PROGRESS,
                                nextIndex = scenarioNextIndex.coerceIn(0, scenarioItems.size),
                                partial = scenarioPartial,
                            )
                            startIndex = scenarioNextIndex.coerceIn(0, scenarioItems.size)
                        }
                        notice = scenarioNotice
                        screen = when (scenarioScreen) {
                            "loading" -> Screen.Loading
                            "setup" -> Screen.NeedsSetup(
                                scenarioMissing ?: MissingCredential.CLIENT_ID,
                            )
                            "home" -> Screen.Home
                            "settings" -> Screen.Settings
                            "diagnostics" -> Screen.Diagnostics
                            "grid" -> Screen.Grid
                            "reel" -> Screen.Reel
                            else -> Screen.Home
                        }
                    } else {
                        routeFromPrivateState()
                    }
                }

                // Reconcile every local-state revision. This handles key loss globally, releases a
                // completed setup panel, and invalidates prior-account media held only in memory.
                LaunchedEffect(purchase.revision, fixtureRun) {
                    if (fixtureName != null || scenarioName != null || fixtureRun) {
                        return@LaunchedEffect
                    }
                    when (purchase.readiness) {
                        PurchaseReadiness.PRIVATE_STATE_UNAVAILABLE -> {
                            currentReel = null
                            setupResetError = null
                            screen = Screen.NeedsSetup(MissingCredential.PRIVATE_STATE)
                        }
                        PurchaseReadiness.LOADING_LOCAL_STATE -> {
                            if (screen == Screen.Home || screen == Screen.Loading) {
                                screen = Screen.Loading
                            }
                        }
                        PurchaseReadiness.READY -> {
                            when (val loaded = graph.privateState.read()) {
                                is PrivateStateRead.Unavailable -> {
                                    currentReel = null
                                    screen = Screen.NeedsSetup(MissingCredential.PRIVATE_STATE)
                                }
                                is PrivateStateRead.Ready -> {
                                    if (loaded.state.provisionCandidate != null) {
                                        currentReel = null
                                        screen = Screen.NeedsSetup(MissingCredential.PROVISIONING)
                                    } else {
                                        if (purchase.operation is PurchaseOperation.Idle) {
                                            val inMemoryId = currentReel?.id
                                            val durableId = purchase.currentReel?.id
                                            if (inMemoryId != null && durableId != inMemoryId) {
                                                currentReel = null
                                                if (screen == Screen.Reel || screen == Screen.Grid) {
                                                    screen = Screen.Home
                                                }
                                            }
                                        }
                                        if (
                                            screen is Screen.NeedsSetup ||
                                            screen == Screen.Loading
                                        ) {
                                            setupResetError = null
                                            routeFromPrivateState()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(purchase.operation) {
                    when (val operation = purchase.operation) {
                        is PurchaseOperation.Finished -> {
                            when (operation.outcome) {
                                is PurchaseOutcome.ReelReady,
                                is PurchaseOutcome.PartialReel,
                                -> {
                                    currentReel = loadSavedReel()
                                    if (currentReel != null) {
                                        startIndex = 0
                                        notice = null
                                        screen = Screen.Reel
                                    }
                                }
                                PurchaseOutcome.NoPlayableVideo -> {
                                    notice = Notices.of(operation.outcome, operation.receipt)
                                    screen = Screen.Home
                                }
                            }
                            graph.purchases.dispatch(PurchaseCommand.Acknowledge(operation.id))
                        }
                        is PurchaseOperation.Failed -> {
                            // Two of these are not messages but destinations; the rest classify by
                            // what the viewer can do, which is what Notices decides.
                            when (operation.problem) {
                                PurchaseProblem.SetupRequired,
                                PurchaseProblem.AuthenticationRequired,
                                -> screen = Screen.NeedsSetup(MissingCredential.SESSION)
                                PurchaseProblem.StorageUnavailable ->
                                    screen = Screen.NeedsSetup(MissingCredential.PRIVATE_STATE)
                                else -> notice = Notices.of(operation.problem)
                            }
                            graph.purchases.dispatch(PurchaseCommand.Acknowledge(operation.id))
                        }
                        is PurchaseOperation.Interrupted -> {
                            notice = Notices.interrupted(
                                operation.conservativelyCommitted.formatUsd(),
                            )
                            screen = Screen.Home
                            graph.purchases.dispatch(PurchaseCommand.Acknowledge(operation.id))
                        }
                        PurchaseOperation.Idle,
                        is PurchaseOperation.Running,
                        -> Unit
                    }
                }

                when (val current = screen) {
                    Screen.Loading -> LoadingScreen()
                    is Screen.NeedsSetup -> SetupGuideScreen(
                        missing = current.missing,
                        resetError = setupResetError,
                        onResetEverything =
                            if (
                                current.missing == MissingCredential.PRIVATE_STATE ||
                                current.missing == MissingCredential.PROVISIONING
                            ) {
                                {
                                    scope.launch {
                                        runCatching { graph.resetAllPrivateState() }
                                            .onSuccess {
                                                currentReel = null
                                                notice = null
                                                setupResetError = null
                                                screen = Screen.NeedsSetup(
                                                    MissingCredential.CLIENT_ID,
                                                )
                                            }
                                            .onFailure {
                                                setupResetError = getString(
                                                    R.string.setup_clear_failed,
                                                )
                                            }
                                    }
                                }
                            } else {
                                null
                            },
                    )
                    Screen.Home -> HomeScreen(
                        state = purchase,
                        notice = notice,
                        diagnosticsAvailable = diagnosticsAvailable,
                        onDismissNotice = { notice = null },
                        onBuild = { token ->
                            notice = null
                            when (val result = graph.purchases.dispatch(PurchaseCommand.Buy(token))) {
                                is DispatchResult.Accepted,
                                is DispatchResult.AlreadyAccepted,
                                -> Unit
                                is DispatchResult.Rejected -> {
                                    notice = Notices.of(result.reason)
                                }
                            }
                        },
                        onContinue = {
                            scope.launch {
                                currentReel = loadSavedReel()
                                currentReel?.let {
                                    startIndex = it.nextIndex.coerceIn(0, it.items.size)
                                    screen = Screen.Reel
                                }
                            }
                        },
                        onReplay = {
                            scope.launch {
                                currentReel = loadSavedReel()
                                currentReel?.let {
                                    startIndex = 0
                                    screen = Screen.Reel
                                }
                            }
                        },
                        onOpenGrid = {
                            scope.launch {
                                currentReel = loadSavedReel()
                                if (currentReel != null) screen = Screen.Grid
                            }
                        },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenDiagnostics = { screen = Screen.Diagnostics },
                        onExitApp = ::finish,
                    )
                    Screen.Settings -> SettingsScreen(
                        state = purchase,
                        onRefreshUsage = {
                            graph.purchases.dispatch(PurchaseCommand.RefreshProjectUsage)
                        },
                        onResetCredentials = {
                            scope.launch {
                                runCatching { graph.resetCredentials() }
                                    .onSuccess {
                                        notice = null
                                        screen = Screen.NeedsSetup(MissingCredential.CLIENT_ID)
                                    }
                                    .onFailure {
                                        notice = Notices.resetBlocked()
                                        screen = Screen.Home
                                    }
                            }
                        },
                        onResetEverything = {
                            scope.launch {
                                runCatching { graph.resetAllPrivateState() }
                                    .onSuccess {
                                        currentReel = null
                                        notice = null
                                        screen = Screen.NeedsSetup(MissingCredential.CLIENT_ID)
                                    }
                                    .onFailure {
                                        notice = Notices.resetBlocked()
                                        screen = Screen.Home
                                    }
                            }
                        },
                        onBack = { screen = Screen.Home },
                    )
                    Screen.Diagnostics -> DiagnosticsScreen(
                        onBack = { screen = Screen.Home },
                    )
                    Screen.Grid -> GridScreen(
                        items = currentReel?.items.orEmpty(),
                        // Always been in the saved state, never drawn until now.
                        nextIndex = currentReel?.nextIndex ?: 0,
                        onPlay = { index ->
                            startIndex = index
                            screen = Screen.Reel
                        },
                        onBack = { screen = Screen.Home },
                    )
                    Screen.Reel -> {
                        val reel = currentReel
                        if (reel == null) {
                            screen = Screen.Home
                        } else {
                            ReelViewer(
                                items = reel.items,
                                startIndex = startIndex,
                                reelId = reel.id,
                                fixture = fixtureRun,
                                partial = reel.partial,
                                playbackCheckpoint = if (fixtureRun) null else graph.playbackCheckpoint,
                                onExit = {
                                    if (fixtureRun) {
                                        finish()
                                    } else {
                                        graph.purchases.dispatch(PurchaseCommand.ReloadLocalState)
                                        screen = Screen.Home
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        legacyProvisioning?.let(::startActivity)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val legacyProvisioning = legacyProvisioningIntent(intent)
        intent.replaceExtras(Bundle())
        setIntent(intent)
        legacyProvisioning?.let(::startActivity)
    }

    private fun legacyProvisioningIntent(source: Intent?): Intent? {
        source ?: return null
        val request = legacyProvisioningRequest(
            requestId = source.getStringExtra(ProvisioningActivity.EXTRA_REQUEST_ID),
            clientId = source.getStringExtra(ProvisioningActivity.EXTRA_CLIENT_ID),
            refreshToken = source.getStringExtra(ProvisioningActivity.EXTRA_REFRESH_TOKEN),
            bearer = source.getStringExtra(ProvisioningActivity.EXTRA_BEARER),
        )
        return request?.let { ProvisioningActivity.intent(this, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadDebugFixture(context: Context, name: String): List<MediaItem> {
        if (!BuildConfig.DEBUG) return emptyList()
        return runCatching {
            val type = Class.forName("com.xtv.app.data.FixtureSource")
            val instance = type.getField("INSTANCE").get(null)
            type.getMethod("load", Context::class.java, String::class.java)
                .invoke(instance, context, name) as List<MediaItem>
        }.getOrDefault(emptyList())
    }

    /**
     * Calls one method on the debug-only scenario catalogue.
     *
     * Everything it can return is a type this file already uses, so the scaffolding never needs a
     * shared holder class living in `main`. In a release build the class is simply not present and
     * every call answers null, which is also what happens on a debug build with no fixture assets.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> debugScenario(method: String, vararg args: Any?): T? {
        if (!BuildConfig.DEBUG) return null
        return runCatching {
            val type = Class.forName("com.xtv.app.data.DebugScenarios")
            val instance = type.getField("INSTANCE").get(null)
            type.methods.first { it.name == method }.invoke(instance, *args) as T?
        }.getOrNull()
    }

    private companion object {
        const val DEBUG_FIXTURE_EXTRA = "fixture"
        const val DEBUG_SCENARIO_EXTRA = "scenario"
    }
}

internal fun legacyProvisioningRequest(
    requestId: String?,
    clientId: String?,
    refreshToken: String?,
    bearer: String?,
    newRequestId: () -> String = { UUID.randomUUID().toString() },
): ProvisioningRequest? {
    if (clientId == null && refreshToken == null && bearer == null) return null
    return ProvisioningRequest(
        requestId = requestId?.takeIf(String::isNotBlank) ?: newRequestId(),
        clientId = clientId.orEmpty(),
        refreshToken = refreshToken.orEmpty(),
        appOnlyBearer = bearer.orEmpty(),
    )
}
