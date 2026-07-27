package com.xtv.app.core.auth

import com.xtv.app.core.storage.AccountBinding
import com.xtv.app.core.storage.LedgerState
import com.xtv.app.core.storage.PrivateStateRead
import com.xtv.app.core.storage.PrivateStateStore
import com.xtv.app.core.storage.ProvisionCandidate
import com.xtv.app.core.storage.ProvisionedCredentials
import com.xtv.app.core.storage.StoredSession
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProvisioningRequest(
    val requestId: String,
    val clientId: String,
    val refreshToken: String,
    val appOnlyBearer: String,
)

sealed interface ProvisioningResult {
    val requestId: String

    data class Committed(
        override val requestId: String,
        val preservedPrivateState: Boolean,
    ) : ProvisioningResult

    data class Rejected(
        override val requestId: String,
        val reason: Reason,
    ) : ProvisioningResult

    enum class Reason {
        MISSING_CREDENTIAL,
        BEARER_REJECTED,
        BEARER_VALIDATION_UNAVAILABLE,
        TOKEN_REJECTED,
        TOKEN_EXCHANGE_OUTCOME_UNKNOWN,
        ROTATED_REFRESH_MISSING,
        ACCOUNT_LOOKUP_FAILED,
        PURCHASE_IN_FLIGHT,
        RECOVERY_REQUIRED,
        STORAGE_UNAVAILABLE,
        SUPERSEDED,
    }
}

sealed interface RefreshTokenExchangeResult {
    data class Success(val tokens: Tokens) : RefreshTokenExchangeResult
    data object Rejected : RefreshTokenExchangeResult
    /** A request may have reached X and spent the supplied one-time refresh token. */
    data object OutcomeUnknown : RefreshTokenExchangeResult
}

/** X token endpoint boundary with rotation-aware outcome certainty. */
fun interface RefreshTokenExchange {
    suspend fun exchange(refreshToken: String, clientId: String): RefreshTokenExchangeResult
}

/** `/2/users/me` boundary. A failed result remains durably recoverable and conservatively billed. */
fun interface AccountResolver {
    suspend fun resolve(accessToken: String): String?
}

sealed interface AppBearerValidation {
    data object Valid : AppBearerValidation
    data object InvalidAuth : AppBearerValidation
    data object Unavailable : AppBearerValidation
}

/** App-only `/2/usage/tweets` validation boundary. */
fun interface AppBearerValidator {
    suspend fun validate(appOnlyBearer: String): AppBearerValidation
}

/**
 * Serializes identity-changing mutations. Provisioning and reset must share this process-wide gate
 * so a successful reset cannot be followed by an older in-flight provision resurrecting secrets.
 */
class PrivateStateMutationGate {
    private val mutex = Mutex()

    suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Stages a candidate, verifies it, then atomically makes it canonical.
 *
 * Refresh tokens rotate. Every exchange is journaled before dispatch, and the rotated value is
 * persisted before the separately billed account lookup starts. A crash or ambiguous response can
 * therefore fail closed without retrying a possibly spent token.
 */
class ProvisioningCoordinator(
    val privateState: PrivateStateStore,
    private val tokenExchange: RefreshTokenExchange,
    private val accountResolver: AccountResolver,
    private val bearerValidator: AppBearerValidator =
        AppBearerValidator { AppBearerValidation.Valid },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val mutationGate: PrivateStateMutationGate = PrivateStateMutationGate(),
) {
    suspend fun provision(request: ProvisioningRequest): ProvisioningResult =
        mutationGate.mutate { provisionLocked(request) }

    private suspend fun provisionLocked(request: ProvisioningRequest): ProvisioningResult {
        val clean = request.cleaned()
        if (
            clean.requestId.isBlank() ||
            clean.clientId.isBlank() ||
            clean.refreshToken.isBlank() ||
            clean.appOnlyBearer.isBlank()
        ) {
            return rejected(request.requestId, ProvisioningResult.Reason.MISSING_CREDENTIAL)
        }

        val credentials = ProvisionedCredentials(clean.clientId, clean.appOnlyBearer)
        val sourceFingerprint = clean.refreshToken.sha256()
        val initial = readState(clean.requestId) ?: return rejected(
            clean.requestId,
            ProvisioningResult.Reason.STORAGE_UNAVAILABLE,
        )

        // This check deliberately precedes network validation. Activity/process retries after a
        // verified commit are local-only and cannot be broken by an unrelated X outage.
        if (
            initial.lastProvisionedRequestId == clean.requestId &&
            initial.lastProvisionedSourceFingerprint == sourceFingerprint &&
            initial.credentials == credentials &&
            initial.session != null &&
            initial.account != null
        ) {
            return ProvisioningResult.Committed(
                requestId = clean.requestId,
                preservedPrivateState =
                    initial.lastProvisionedPreservedPrivateState ?: false,
            )
        }
        if (initial.ledger.pendingOperation != null) {
            return rejected(clean.requestId, ProvisioningResult.Reason.PURCHASE_IN_FLIGHT)
        }

        // A same-source retry resumes its rotated token. An independently renewed source token may
        // replace an ambiguous candidate only inside the same developer project; whether a billable
        // account lookup already left the device is carried forward, while account verification
        // itself starts over.
        val existingCandidate = initial.provisionCandidate
        val resumable = existingCandidate?.takeIf {
            it.credentials.clientId == clean.clientId &&
                it.sourceRefreshFingerprint == sourceFingerprint
        }
        val replacement =
            existingCandidate?.takeIf {
                resumable == null && it.credentials.clientId == clean.clientId
            }
        if (existingCandidate != null && resumable == null && replacement == null) {
            return rejected(clean.requestId, ProvisioningResult.Reason.RECOVERY_REQUIRED)
        }

        if (existingCandidate?.credentials?.appOnlyBearer != clean.appOnlyBearer) {
            val validation = try {
                bearerValidator.validate(clean.appOnlyBearer)
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                AppBearerValidation.Unavailable
            }
            when (validation) {
                AppBearerValidation.Valid -> Unit
                AppBearerValidation.InvalidAuth ->
                    return rejected(clean.requestId, ProvisioningResult.Reason.BEARER_REJECTED)
                AppBearerValidation.Unavailable -> {
                    return rejected(
                        clean.requestId,
                        ProvisioningResult.Reason.BEARER_VALIDATION_UNAVAILABLE,
                    )
                }
            }
        }

        val staged = try {
            var accepted = false
            val updated = privateState.update { state ->
                if (state.ledger.pendingOperation != null) {
                    state
                } else {
                    accepted = true
                    val candidate = when {
                        resumable != null -> resumable.copy(
                            requestId = clean.requestId,
                            credentials = credentials,
                        )
                        replacement != null -> ProvisionCandidate(
                            requestId = clean.requestId,
                            credentials = credentials,
                            suppliedRefreshToken = clean.refreshToken,
                            sourceRefreshFingerprint = sourceFingerprint,
                            // Verification restarts, but a lookup that already left the device
                            // stays on the record: the replacement must not look discardable.
                            accountLookupDispatched = replacement.accountLookupDispatched,
                        )
                        else -> ProvisionCandidate(
                            requestId = clean.requestId,
                            credentials = credentials,
                            suppliedRefreshToken = clean.refreshToken,
                            sourceRefreshFingerprint = sourceFingerprint,
                        )
                    }
                    state.copy(provisionCandidate = candidate)
                }
            }
            if (!accepted) {
                return rejected(clean.requestId, ProvisioningResult.Reason.PURCHASE_IN_FLIGHT)
            }
            updated.provisionCandidate
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return rejected(clean.requestId, ProvisioningResult.Reason.STORAGE_UNAVAILABLE)
        }
        if (staged?.requestId != clean.requestId) {
            return rejected(clean.requestId, ProvisioningResult.Reason.SUPERSEDED)
        }

        val sessionResult = ensureUsableSession(clean, staged)
        val stagedSession = when (sessionResult) {
            is SessionPreparation.Ready -> sessionResult.session
            is SessionPreparation.Failed -> return rejected(clean.requestId, sessionResult.reason)
        }

        val afterSession = readState(clean.requestId)
            ?: return rejected(clean.requestId, ProvisioningResult.Reason.STORAGE_UNAVAILABLE)
        val currentCandidate = afterSession.provisionCandidate
        if (currentCandidate?.requestId != clean.requestId) {
            return rejected(clean.requestId, ProvisioningResult.Reason.SUPERSEDED)
        }

        val accountId = currentCandidate.verifiedAccountId ?: resolveAndJournalAccount(
            requestId = clean.requestId,
            accessToken = stagedSession.accessToken,
        ).let { resolved ->
            when (resolved) {
                is AccountPreparation.Ready -> resolved.accountId
                is AccountPreparation.Failed ->
                    return rejected(clean.requestId, resolved.reason)
            }
        }

        return commitCanonical(
            clean = clean,
            sourceFingerprint = sourceFingerprint,
            stagedSession = stagedSession,
            accountId = accountId,
        )
    }

    private suspend fun ensureUsableSession(
        request: ProvisioningRequest,
        candidate: ProvisionCandidate,
    ): SessionPreparation {
        val existing = candidate.rotatedSession
        if (
            existing != null &&
            existing.accessToken.isNotBlank() &&
            nowMs() < existing.expiresAtMs - REFRESH_EARLY_MS
        ) {
            return SessionPreparation.Ready(existing)
        }

        val exchangeToken = if (existing == null) {
            candidate.suppliedRefreshToken
        } else {
            existing.refreshToken
        }?.takeIf(String::isNotBlank)
            ?: return SessionPreparation.Failed(
                ProvisioningResult.Reason.ROTATED_REFRESH_MISSING,
            )
        val attemptFingerprint = exchangeToken.sha256()
        if (candidate.exchangeAttemptFingerprint == attemptFingerprint) {
            return SessionPreparation.Failed(
                if (candidate.exchangeAttemptRejected) {
                    ProvisioningResult.Reason.TOKEN_REJECTED
                } else {
                    ProvisioningResult.Reason.TOKEN_EXCHANGE_OUTCOME_UNKNOWN
                },
            )
        }

        try {
            var marked = false
            val afterMark = privateState.update { state ->
                val current = state.provisionCandidate
                if (current?.requestId != request.requestId) {
                    state
                } else {
                    marked = true
                    state.copy(
                        provisionCandidate = current.copy(
                            exchangeAttemptFingerprint = attemptFingerprint,
                            exchangeAttemptRejected = false,
                        ),
                    )
                }
            }
            if (!marked || afterMark.provisionCandidate?.requestId != request.requestId) {
                return SessionPreparation.Failed(ProvisioningResult.Reason.SUPERSEDED)
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return SessionPreparation.Failed(ProvisioningResult.Reason.STORAGE_UNAVAILABLE)
        }

        val exchangeResult = try {
            tokenExchange.exchange(exchangeToken, request.clientId)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            RefreshTokenExchangeResult.OutcomeUnknown
        }
        val tokens = when (exchangeResult) {
            is RefreshTokenExchangeResult.Success -> exchangeResult.tokens
            RefreshTokenExchangeResult.OutcomeUnknown -> {
                return SessionPreparation.Failed(
                    ProvisioningResult.Reason.TOKEN_EXCHANGE_OUTCOME_UNKNOWN,
                )
            }
            RefreshTokenExchangeResult.Rejected -> {
                // Nothing was rotated and no billable `/users/me` was ever dispatched, so there is
                // no durable exposure to preserve and the candidate can simply be dropped. A
                // *completed* lookup is the wrong test here: one that was dispatched and never
                // resolved is exactly the case that has to be kept for recovery.
                if (existing == null && !candidate.accountLookupDispatched) {
                    val discarded = rejectAndDiscard(
                        request.requestId,
                        ProvisioningResult.Reason.TOKEN_REJECTED,
                    )
                    return SessionPreparation.Failed(discarded.reason)
                }
                val recorded = try {
                    privateState.update { state ->
                        val current = state.provisionCandidate
                        if (
                            current?.requestId == request.requestId &&
                            current.exchangeAttemptFingerprint == attemptFingerprint
                        ) {
                            state.copy(
                                provisionCandidate = current.copy(
                                    exchangeAttemptRejected = true,
                                ),
                            )
                        } else {
                            state
                        }
                    }
                    true
                } catch (failure: Throwable) {
                    failure.rethrowCancellation()
                    false
                }
                return SessionPreparation.Failed(
                    if (recorded) {
                        ProvisioningResult.Reason.TOKEN_REJECTED
                    } else {
                        ProvisioningResult.Reason.STORAGE_UNAVAILABLE
                    },
                )
            }
        }
        val rotatedRefresh = tokens.refreshToken?.takeIf(String::isNotBlank)
            ?: return SessionPreparation.Failed(
                ProvisioningResult.Reason.ROTATED_REFRESH_MISSING,
            )
        val rotated = StoredSession(
            accessToken = tokens.accessToken,
            refreshToken = rotatedRefresh,
            expiresAtMs = tokens.expiresAtMs,
        )
        if (rotated.accessToken.isBlank()) {
            return SessionPreparation.Failed(
                ProvisioningResult.Reason.TOKEN_EXCHANGE_OUTCOME_UNKNOWN,
            )
        }

        return try {
            var persisted = false
            val afterStage = privateState.update { state ->
                val current = state.provisionCandidate
                if (
                    current?.requestId != request.requestId ||
                    current.exchangeAttemptFingerprint != attemptFingerprint
                ) {
                    state
                } else {
                    persisted = true
                    state.copy(
                        provisionCandidate = current.copy(
                            suppliedRefreshToken = null,
                            rotatedSession = rotated,
                            exchangeAttemptFingerprint = null,
                            exchangeAttemptRejected = false,
                        ),
                    )
                }
            }
            if (persisted && afterStage.provisionCandidate?.requestId == request.requestId) {
                SessionPreparation.Ready(rotated)
            } else {
                SessionPreparation.Failed(ProvisioningResult.Reason.SUPERSEDED)
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            // The response may have rotated the refresh token even if local verification failed.
            SessionPreparation.Failed(ProvisioningResult.Reason.STORAGE_UNAVAILABLE)
        }
    }

    /**
     * Resolves `/2/users/me` and journals both the attempt and the result.
     *
     * The dispatch is marked *before* the request leaves, not only after it succeeds. A null
     * result, a thrown exception and a process death mid-flight are otherwise indistinguishable
     * from never having asked, and that ambiguity is what decides whether a later retry can throw
     * the candidate away or has to keep it for recovery.
     *
     * This used to be a monetary reservation checked against a local monthly ceiling, which could
     * refuse to provision a device over money the Developer Console had already agreed to. The
     * ceiling is gone; the pre-dispatch journal entry is not, because it was never really about
     * the money.
     */
    private suspend fun resolveAndJournalAccount(
        requestId: String,
        accessToken: String,
    ): AccountPreparation {
        try {
            var marked = false
            val afterMark = privateState.update { state ->
                val candidate = state.provisionCandidate
                if (candidate?.requestId != requestId) {
                    state
                } else {
                    marked = true
                    state.copy(
                        provisionCandidate = candidate.copy(accountLookupDispatched = true),
                    )
                }
            }
            if (!marked || afterMark.provisionCandidate?.requestId != requestId) {
                return AccountPreparation.Failed(ProvisioningResult.Reason.SUPERSEDED)
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return AccountPreparation.Failed(ProvisioningResult.Reason.STORAGE_UNAVAILABLE)
        }

        val resolved = try {
            accountResolver.resolve(accessToken)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            null
        }?.takeIf(String::isNotBlank)
            ?: return AccountPreparation.Failed(
                ProvisioningResult.Reason.ACCOUNT_LOOKUP_FAILED,
            )

        return try {
            var persisted = false
            val afterResolution = privateState.update { state ->
                val candidate = state.provisionCandidate
                if (candidate?.requestId != requestId) {
                    state
                } else {
                    persisted = true
                    state.copy(
                        provisionCandidate = candidate.copy(verifiedAccountId = resolved),
                    )
                }
            }
            if (persisted && afterResolution.provisionCandidate?.requestId == requestId) {
                AccountPreparation.Ready(resolved)
            } else {
                AccountPreparation.Failed(ProvisioningResult.Reason.SUPERSEDED)
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            AccountPreparation.Failed(ProvisioningResult.Reason.STORAGE_UNAVAILABLE)
        }
    }

    private suspend fun commitCanonical(
        clean: ProvisioningRequest,
        sourceFingerprint: String,
        stagedSession: StoredSession,
        accountId: String,
    ): ProvisioningResult = try {
        var contentPreserved = false
        var committed = false
        privateState.update { current ->
            val candidate = current.provisionCandidate
            if (candidate?.requestId != clean.requestId) {
                current
            } else {
                committed = true
                val oldProject =
                    current.account?.projectKey?.takeIf(String::isNotBlank)
                        ?: current.credentials?.clientId.orEmpty()
                val sameProject = oldProject.isNotBlank() && oldProject == clean.clientId
                val unknownLegacyProject =
                    current.account != null && current.account.projectKey.isBlank()
                val unscopedLegacyLedger =
                    oldProject.isBlank() &&
                        current.credentials?.clientId.isNullOrBlank() &&
                        current.ledger.hasExposure()
                val sameAccount = current.account?.accountId == accountId

                // Reel/cursor are account content. The billing journal and usage cache are
                // developer-project state.
                contentPreserved = sameAccount && (sameProject || unknownLegacyProject)
                val preserveLedger =
                    sameProject || unknownLegacyProject || unscopedLegacyLedger
                val retainedLedger =
                    if (preserveLedger) current.ledger else LedgerState()
                current.copy(
                    credentials = candidate.credentials,
                    session = stagedSession,
                    sessionExchangeAttemptFingerprint = null,
                    sessionExchangeAttemptRejected = false,
                    account = AccountBinding(accountId, clean.clientId),
                    reel = if (contentPreserved) current.reel else null,
                    cursor = if (contentPreserved) current.cursor else null,
                    ledger = retainedLedger.copy(
                        terminalOperation =
                            if (contentPreserved) {
                                retainedLedger.terminalOperation
                            } else {
                                null
                            },
                    ),
                    cachedUsage = if (sameProject) current.cachedUsage else null,
                    provisionCandidate = null,
                    lastProvisionedRequestId = clean.requestId,
                    lastProvisionedSourceFingerprint = sourceFingerprint,
                    lastProvisionedPreservedPrivateState = contentPreserved,
                )
            }
        }
        if (committed) {
            ProvisioningResult.Committed(clean.requestId, contentPreserved)
        } else {
            rejected(clean.requestId, ProvisioningResult.Reason.SUPERSEDED)
        }
    } catch (failure: Throwable) {
        failure.rethrowCancellation()
        rejected(clean.requestId, ProvisioningResult.Reason.STORAGE_UNAVAILABLE)
    }

    private suspend fun rejectAndDiscard(
        requestId: String,
        reason: ProvisioningResult.Reason,
    ): ProvisioningResult.Rejected {
        val discarded = try {
            privateState.update {
                if (it.provisionCandidate?.requestId == requestId) {
                    it.copy(provisionCandidate = null)
                } else {
                    it
                }
            }
            true
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            false
        }
        return rejected(
            requestId,
            if (discarded) reason else ProvisioningResult.Reason.STORAGE_UNAVAILABLE,
        )
    }

    private suspend fun readState(requestId: String) = when (val loaded = privateState.read()) {
        is PrivateStateRead.Ready -> loaded.state
        is PrivateStateRead.Unavailable -> null
    }

    private fun rejected(requestId: String, reason: ProvisioningResult.Reason) =
        ProvisioningResult.Rejected(requestId, reason)

    private fun Throwable.rethrowCancellation() {
        if (this is CancellationException) throw this
    }

    private fun ProvisioningRequest.cleaned() = copy(
        requestId = requestId.trim(),
        clientId = clientId.trim(),
        refreshToken = refreshToken.trim(),
        appOnlyBearer = appOnlyBearer.trim(),
    )

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private sealed interface SessionPreparation {
        data class Ready(val session: StoredSession) : SessionPreparation
        data class Failed(val reason: ProvisioningResult.Reason) : SessionPreparation
    }

    private sealed interface AccountPreparation {
        data class Ready(val accountId: String) : AccountPreparation
        data class Failed(val reason: ProvisioningResult.Reason) : AccountPreparation
    }

    private companion object {
        const val REFRESH_EARLY_MS = 60_000L
    }
}

/** Whether this journal records a paid request that a reprovision should not silently discard. */
private fun LedgerState.hasExposure(): Boolean =
    pendingOperation != null || lastReceipt != null || terminalOperation != null
