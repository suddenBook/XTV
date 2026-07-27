package com.xtv.app.core.auth

import com.xtv.app.core.purchase.PurchaseProblem
import com.xtv.app.core.purchase.PurchaseSessionPort
import com.xtv.app.core.purchase.SessionOutcome
import com.xtv.app.core.storage.AccountBinding
import com.xtv.app.core.storage.PrivateStateRead
import com.xtv.app.core.storage.PrivateStateStore
import com.xtv.app.core.storage.StoredSession
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One application-scoped refresh/account resolver for purchases.
 *
 * Rotated refresh tokens are committed before account or timeline reads. The returned access token
 * remains internal to the purchase module and never enters observable state or diagnostics.
 */
class PrivateStatePurchaseSession(
    private val privateState: PrivateStateStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val tokenExchange: RefreshTokenExchange = RefreshTokenExchange { refresh, clientId ->
        when (val result = OAuthFlow.refresh(refresh, clientId)) {
            is OAuthFlow.Result.Success -> RefreshTokenExchangeResult.Success(result.tokens)
            is OAuthFlow.Result.Denied -> RefreshTokenExchangeResult.Rejected
            is OAuthFlow.Result.Failed -> RefreshTokenExchangeResult.OutcomeUnknown
        }
    },
    private val accountResolver: AccountResolver = AccountResolver(UserLookup::me),
) : PurchaseSessionPort {
    private val mutex = Mutex()

    override suspend fun session(
        beforeIdentityLookup: suspend () -> Boolean,
    ): SessionOutcome = mutex.withLock {
        val initial = when (val loaded = privateState.read()) {
            is PrivateStateRead.Ready -> loaded.state
            is PrivateStateRead.Unavailable -> {
                return@withLock SessionOutcome.Failure(PurchaseProblem.StorageUnavailable)
            }
        }
        val credentials = initial.credentials
            ?: return@withLock SessionOutcome.Failure(PurchaseProblem.SetupRequired)
        var stored = initial.session
            ?: return@withLock SessionOutcome.Failure(PurchaseProblem.AuthenticationRequired)

        if (stored.accessToken.isBlank() || nowMs() >= stored.expiresAtMs - REFRESH_EARLY_MS) {
            val refresh = stored.refreshToken
                ?: return@withLock SessionOutcome.Failure(PurchaseProblem.AuthenticationRequired)
            val attemptFingerprint = refresh.sha256()
            if (initial.sessionExchangeAttemptFingerprint == attemptFingerprint) {
                return@withLock SessionOutcome.Failure(
                    if (initial.sessionExchangeAttemptRejected) {
                        PurchaseProblem.AuthenticationRequired
                    } else {
                        PurchaseProblem.Network
                    },
                )
            }
            try {
                val marked = privateState.update { state ->
                    if (state.session?.refreshToken != refresh) {
                        state
                    } else {
                        state.copy(
                            sessionExchangeAttemptFingerprint = attemptFingerprint,
                            sessionExchangeAttemptRejected = false,
                        )
                    }
                }
                if (marked.sessionExchangeAttemptFingerprint != attemptFingerprint) {
                    return@withLock SessionOutcome.Failure(
                        PurchaseProblem.AuthenticationRequired,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withLock SessionOutcome.Failure(PurchaseProblem.StorageUnavailable)
            }
            val refreshed = try {
                tokenExchange.exchange(refresh, credentials.clientId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                RefreshTokenExchangeResult.OutcomeUnknown
            }
            val tokens = when (refreshed) {
                is RefreshTokenExchangeResult.Success -> refreshed.tokens
                RefreshTokenExchangeResult.Rejected -> {
                    try {
                        privateState.update { state ->
                            if (state.sessionExchangeAttemptFingerprint == attemptFingerprint) {
                                state.copy(sessionExchangeAttemptRejected = true)
                            } else {
                                state
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        return@withLock SessionOutcome.Failure(
                            PurchaseProblem.StorageUnavailable,
                        )
                    }
                    return@withLock SessionOutcome.Failure(
                        PurchaseProblem.AuthenticationRequired,
                    )
                }
                RefreshTokenExchangeResult.OutcomeUnknown -> {
                    return@withLock SessionOutcome.Failure(PurchaseProblem.Network)
                }
            }
            val rotatedRefresh = tokens.refreshToken?.takeIf(String::isNotBlank)
                ?: return@withLock SessionOutcome.Failure(
                    PurchaseProblem.AuthenticationRequired,
                )
            if (tokens.accessToken.isBlank()) {
                return@withLock SessionOutcome.Failure(PurchaseProblem.Network)
            }
            stored = StoredSession(
                accessToken = tokens.accessToken,
                refreshToken = rotatedRefresh,
                expiresAtMs = tokens.expiresAtMs,
            )
            try {
                val persisted = privateState.update { state ->
                    if (state.sessionExchangeAttemptFingerprint != attemptFingerprint) {
                        state
                    } else {
                        state.copy(
                            session = stored,
                            sessionExchangeAttemptFingerprint = null,
                            sessionExchangeAttemptRejected = false,
                        )
                    }
                }
                if (
                    persisted.session != stored ||
                    persisted.sessionExchangeAttemptFingerprint != null
                ) {
                    return@withLock SessionOutcome.Failure(
                        PurchaseProblem.StorageUnavailable,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withLock SessionOutcome.Failure(PurchaseProblem.StorageUnavailable)
            }
        }

        initial.account?.let {
            return@withLock SessionOutcome.Ready(
                accessToken = stored.accessToken,
                accountId = it.accountId,
                identityLookupCharged = false,
            )
        }

        // `/users/me` is a User read. A null result is conservatively "possibly charged" because a
        // response can be lost after the resource was returned upstream.
        if (!beforeIdentityLookup()) {
            return@withLock SessionOutcome.Failure(PurchaseProblem.StorageUnavailable)
        }
        val accountId = try {
            accountResolver.resolve(stored.accessToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }?.takeIf(String::isNotBlank)
            ?: return@withLock SessionOutcome.Failure(
                problem = PurchaseProblem.Network,
                possiblyCharged = true,
            )

        try {
            privateState.update {
                it.copy(account = AccountBinding(accountId, credentials.clientId))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@withLock SessionOutcome.Failure(
                problem = PurchaseProblem.StorageUnavailable,
                possiblyCharged = true,
            )
        }
        SessionOutcome.Ready(
            accessToken = stored.accessToken,
            accountId = accountId,
            identityLookupCharged = true,
        )
    }

    private companion object {
        const val REFRESH_EARLY_MS = 60_000L
    }
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
