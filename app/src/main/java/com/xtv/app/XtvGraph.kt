package com.xtv.app

import android.content.Context
import com.xtv.app.core.auth.AccountResolver
import com.xtv.app.core.auth.AppBearerValidation
import com.xtv.app.core.auth.AppBearerValidator
import com.xtv.app.core.auth.LegacyAuthStateSource
import com.xtv.app.core.auth.OAuthFlow
import com.xtv.app.core.auth.PrivateStateMutationGate
import com.xtv.app.core.auth.PrivateStatePurchaseSession
import com.xtv.app.core.auth.ProvisioningCoordinator
import com.xtv.app.core.auth.RefreshTokenExchange
import com.xtv.app.core.auth.RefreshTokenExchangeResult
import com.xtv.app.core.auth.UserLookup
import com.xtv.app.core.budget.UsageApi
import com.xtv.app.core.purchase.DefaultReelPurchase
import com.xtv.app.core.purchase.PrivateStatePlaybackCheckpoint
import com.xtv.app.core.purchase.PrivateStatePurchasePort
import com.xtv.app.core.purchase.ProjectPostUsage
import com.xtv.app.core.purchase.ProjectUsagePort
import com.xtv.app.core.purchase.PurchaseCommand
import com.xtv.app.core.purchase.ReelPurchase
import com.xtv.app.core.source.ApiTimelineReadPort
import com.xtv.app.core.storage.CompositeLegacyStateSource
import com.xtv.app.core.storage.PrivateStateClearResult
import com.xtv.app.core.storage.PrivateStateRead
import com.xtv.app.core.storage.PrivateStateStore
import com.xtv.app.core.storage.PrivateStateUnavailableException
import com.xtv.app.core.storage.androidPrivateStateStore
import com.xtv.app.data.LegacyReelBudgetStateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process composition root. Construction and state collection are local-only; every X request is
 * behind a purchase command or an explicit Diagnostics refresh.
 */
class XtvGraph(context: Context) {
    private val appContext = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Graph-owned repository; every adapter receives this same transaction boundary. */
    val privateState: PrivateStateStore = androidPrivateStateStore(
        appContext,
        CompositeLegacyStateSource(
            listOf(
                LegacyAuthStateSource(appContext),
                LegacyReelBudgetStateSource(appContext),
            ),
        ),
    )

    val purchaseState = PrivateStatePurchasePort(privateState)
    private val privateStateMutationGate = PrivateStateMutationGate()

    private val refreshTokenExchange = RefreshTokenExchange { refreshToken, clientId ->
        when (val result = OAuthFlow.refresh(refreshToken, clientId)) {
            is OAuthFlow.Result.Success -> RefreshTokenExchangeResult.Success(result.tokens)
            is OAuthFlow.Result.Denied -> RefreshTokenExchangeResult.Rejected
            is OAuthFlow.Result.Failed -> RefreshTokenExchangeResult.OutcomeUnknown
        }
    }
    private val accountResolver = AccountResolver(UserLookup::me)

    val provisioning = ProvisioningCoordinator(
        privateState = privateState,
        tokenExchange = refreshTokenExchange,
        accountResolver = accountResolver,
        bearerValidator = AppBearerValidator { bearer ->
            when (UsageApi.lookup(bearer)) {
                is UsageApi.LookupResult.Success -> AppBearerValidation.Valid
                UsageApi.LookupResult.InvalidAuth -> AppBearerValidation.InvalidAuth
                UsageApi.LookupResult.Unavailable -> AppBearerValidation.Unavailable
            }
        },
        mutationGate = privateStateMutationGate,
    )

    val purchases: ReelPurchase = DefaultReelPurchase(
        applicationScope = applicationScope,
        store = purchaseState,
        session = PrivateStatePurchaseSession(
            privateState,
            tokenExchange = refreshTokenExchange,
            accountResolver = accountResolver,
        ),
        timeline = ApiTimelineReadPort(),
        usage = PrivateProjectUsage(privateState),
    )
    // No reload on commit. Every clip transition writes a checkpoint, and re-reading the whole
    // envelope after each one meant two full decrypt/encrypt round trips per video plus a home
    // screen that went briefly dead mid-reel. Leaving the player already reloads once, which is the
    // only moment the home screen's copy of the reel can matter.
    val playbackCheckpoint = PrivateStatePlaybackCheckpoint(
        privateState = privateState,
        scope = applicationScope,
    )

    /** Keep the verified account binding/reel/ledger so reprovisioning the same identity can restore. */
    suspend fun resetCredentials() {
        privateStateMutationGate.mutate {
            check(
                purchases.state.value.operation
                    !is com.xtv.app.core.purchase.PurchaseOperation.Running,
            ) {
                "cannot reset credentials while an operation is running"
            }
            privateState.update {
                check(it.ledger.pendingOperation == null) {
                    "cannot reset credentials while a paid request is pending"
                }
                check(it.provisionCandidate == null) {
                    "cannot reset credentials while provisioning is recoverable"
                }
                it.copy(
                    credentials = null,
                    session = null,
                    sessionExchangeAttemptFingerprint = null,
                    sessionExchangeAttemptRejected = false,
                    provisionCandidate = null,
                    lastProvisionedRequestId = null,
                    lastProvisionedSourceFingerprint = null,
                    lastProvisionedPreservedPrivateState = null,
                )
            }
        }
        purchases.dispatch(PurchaseCommand.ReloadLocalState)
    }

    /** Irreversibly replace all private state with a fresh encrypted envelope. */
    suspend fun resetAllPrivateState() {
        privateStateMutationGate.mutate {
            check(
                purchases.state.value.operation
                    !is com.xtv.app.core.purchase.PurchaseOperation.Running,
            ) {
                "cannot reset private state while an operation is running"
            }
            when (
                val result = privateState.clearGuarded(
                    allowUnavailableRecovery = true,
                    canClear = { it.ledger.pendingOperation == null },
                )
            ) {
                PrivateStateClearResult.Cleared -> Unit
                PrivateStateClearResult.Rejected ->
                    error("cannot reset private state while a paid request is pending")
                is PrivateStateClearResult.Failed ->
                    throw PrivateStateUnavailableException(result.failure)
            }
        }
        purchases.dispatch(PurchaseCommand.ReloadLocalState)
    }

    private class PrivateProjectUsage(
        private val privateState: PrivateStateStore,
    ) : ProjectUsagePort {
        override suspend fun refresh(): ProjectPostUsage? {
            val credentials = when (val loaded = privateState.read()) {
                is PrivateStateRead.Ready -> loaded.state.credentials
                is PrivateStateRead.Unavailable -> null
            } ?: return null
            val bearer = credentials.appOnlyBearer.takeIf(String::isNotBlank) ?: return null
            return UsageApi.postsThisPeriod(bearer)?.let {
                ProjectPostUsage(
                    posts = it.posts,
                    resetDay = it.resetDay,
                    observedAtMs = System.currentTimeMillis(),
                    projectScope = credentials.clientId,
                )
            }
        }
    }
}
