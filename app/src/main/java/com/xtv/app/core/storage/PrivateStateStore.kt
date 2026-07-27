package com.xtv.app.core.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface StorageFailure {
    data object KeyUnavailable : StorageFailure
    data object AuthenticationFailed : StorageFailure
    data class CorruptEnvelope(val detail: String) : StorageFailure
    data class Io(val detail: String) : StorageFailure
}

sealed interface EnvelopeRead {
    data object Missing : EnvelopeRead
    data class Present(val plaintext: ByteArray) : EnvelopeRead
    data class Failed(val failure: StorageFailure) : EnvelopeRead
}

/** The Android implementation encrypts/decrypts at this system boundary. */
interface EncryptedEnvelopeBackend {
    suspend fun read(): EnvelopeRead
    /** Returns null only after the atomic write is committed. */
    suspend fun write(plaintext: ByteArray): StorageFailure?
    suspend fun clear(): StorageFailure?
}

interface LegacyStateSource {
    suspend fun read(): PrivateStateV2?
    suspend fun clear()
}

data object EmptyLegacyStateSource : LegacyStateSource {
    override suspend fun read(): PrivateStateV2? = null
    override suspend fun clear() = Unit
}

/**
 * Combines package-owned legacy readers into one migration transaction.
 *
 * Every source is read before the canonical envelope is written, and every source is cleared only
 * after that write verifies. Overlapping non-default fields must agree; silently choosing one would
 * make migration order decide the user's identity or accounting.
 */
class CompositeLegacyStateSource(
    private val sources: List<LegacyStateSource>,
) : LegacyStateSource {
    override suspend fun read(): PrivateStateV2? {
        val snapshots = sources.mapNotNull { it.read() }
        if (snapshots.isEmpty()) return null
        return snapshots.fold(PrivateStateV2(), ::merge)
    }

    override suspend fun clear() {
        sources.forEach { it.clear() }
    }

    private fun merge(left: PrivateStateV2, right: PrivateStateV2): PrivateStateV2 =
        PrivateStateV2(
            credentials = agree("credentials", left.credentials, right.credentials),
            session = agree("session", left.session, right.session),
            account = agree("account", left.account, right.account),
            reel = agree("reel", left.reel, right.reel),
            cursor = agree("cursor", left.cursor, right.cursor),
            ledger = when {
                left.ledger == LedgerState() -> right.ledger
                right.ledger == LedgerState() -> left.ledger
                left.ledger == right.ledger -> left.ledger
                else -> error("conflicting legacy ledger state")
            },
            cachedUsage = agree("cached usage", left.cachedUsage, right.cachedUsage),
            provisionCandidate = agree(
                "provision candidate",
                left.provisionCandidate,
                right.provisionCandidate,
            ),
            diagnostics = left.diagnostics + right.diagnostics,
        )

    private fun <T> agree(name: String, left: T?, right: T?): T? = when {
        left == null -> right
        right == null -> left
        left == right -> left
        else -> error("conflicting legacy $name")
    }
}

sealed interface PrivateStateRead {
    data class Ready(val state: PrivateStateV2) : PrivateStateRead
    data class Unavailable(val failure: StorageFailure) : PrivateStateRead
}

sealed interface PrivateStateClearResult {
    data object Cleared : PrivateStateClearResult
    data object Rejected : PrivateStateClearResult
    data class Failed(val failure: StorageFailure) : PrivateStateClearResult
}

class PrivateStateUnavailableException(val failure: StorageFailure) :
    IllegalStateException("Private state unavailable: $failure")

/**
 * Serializes all state transitions and verifies every write through the public read path.
 *
 * A missing store is a fresh install. An unreadable store is never interpreted as a fresh install:
 * that distinction is what makes key deletion and restored ciphertext fail closed instead of
 * silently losing the user's ledger.
 */
class PrivateStateStore(
    private val backend: EncryptedEnvelopeBackend,
    private val legacy: LegacyStateSource = EmptyLegacyStateSource,
) {
    private val mutex = Mutex()

    suspend fun read(): PrivateStateRead = mutex.withLock { readLocked(migrate = true) }

    /**
     * Atomically transforms the complete private document.
     *
     * The returned state is the value decoded from the just-written envelope, not the in-memory
     * candidate. A caller therefore cannot proceed after a truncated or unverifiable commit.
     */
    suspend fun update(transform: (PrivateStateV2) -> PrivateStateV2): PrivateStateV2 =
        mutex.withLock {
            val current = when (val loaded = readLocked(migrate = true)) {
                is PrivateStateRead.Ready -> loaded.state
                is PrivateStateRead.Unavailable -> throw PrivateStateUnavailableException(loaded.failure)
            }
            val candidate = transform(current)
            writeVerifiedLocked(candidate)
        }

    /**
     * Permanently clears both generations of private state.
     *
     * Legacy files are cleared first so a subsequent read cannot silently migrate credentials or a
     * ledger that the user explicitly chose to erase. The canonical envelope is left intact if that
     * first step fails.
     */
    suspend fun clearGuarded(
        allowUnavailableRecovery: Boolean,
        canClear: (PrivateStateV2) -> Boolean,
    ): PrivateStateClearResult = mutex.withLock {
        when (val loaded = readLocked(migrate = true)) {
            is PrivateStateRead.Ready -> {
                if (!canClear(loaded.state)) return@withLock PrivateStateClearResult.Rejected
            }
            is PrivateStateRead.Unavailable -> {
                if (!allowUnavailableRecovery) {
                    return@withLock PrivateStateClearResult.Failed(loaded.failure)
                }
            }
        }
        clearLocked()
    }

    private suspend fun clearLocked(): PrivateStateClearResult {
        try {
            legacy.clear()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            return PrivateStateClearResult.Failed(
                StorageFailure.Io(t.message ?: t.javaClass.simpleName),
            )
        }
        return backend.clear()?.let(PrivateStateClearResult::Failed)
            ?: PrivateStateClearResult.Cleared
    }

    private suspend fun readLocked(migrate: Boolean): PrivateStateRead {
        return when (val result = backend.read()) {
            EnvelopeRead.Missing -> {
                val old = try {
                    if (migrate) legacy.read() else null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: PrivateStateUnavailableException) {
                    return PrivateStateRead.Unavailable(failure.failure)
                } catch (t: Throwable) {
                    return PrivateStateRead.Unavailable(
                        StorageFailure.Io(t.message ?: t.javaClass.simpleName),
                    )
                }
                if (old == null) {
                    PrivateStateRead.Ready(PrivateStateV2())
                } else {
                    try {
                        val verified = writeVerifiedLocked(old.copy(legacyCleanupPending = true))
                        legacy.clear()
                        PrivateStateRead.Ready(
                            writeVerifiedLocked(verified.copy(legacyCleanupPending = false)),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: PrivateStateUnavailableException) {
                        PrivateStateRead.Unavailable(failure.failure)
                    } catch (t: Throwable) {
                        PrivateStateRead.Unavailable(
                            StorageFailure.Io(t.message ?: t.javaClass.simpleName),
                        )
                    }
                }
            }
            is EnvelopeRead.Failed -> PrivateStateRead.Unavailable(result.failure)
            is EnvelopeRead.Present -> {
                val decoded = decode(result.plaintext)
                val state = (decoded as? PrivateStateRead.Ready)?.state
                if (!migrate || state?.legacyCleanupPending != true) {
                    decoded
                } else {
                    try {
                        legacy.clear()
                        PrivateStateRead.Ready(
                            writeVerifiedLocked(state.copy(legacyCleanupPending = false)),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: PrivateStateUnavailableException) {
                        PrivateStateRead.Unavailable(failure.failure)
                    } catch (t: Throwable) {
                        PrivateStateRead.Unavailable(
                            StorageFailure.Io(t.message ?: t.javaClass.simpleName),
                        )
                    }
                }
            }
        }
    }

    private fun decode(bytes: ByteArray): PrivateStateRead = try {
        PrivateStateRead.Ready(StateEnvelopeCodec.decode(bytes))
    } catch (t: Throwable) {
        PrivateStateRead.Unavailable(
            StorageFailure.CorruptEnvelope(t.message ?: t.javaClass.simpleName),
        )
    }

    private suspend fun writeVerifiedLocked(state: PrivateStateV2): PrivateStateV2 {
        backend.write(StateEnvelopeCodec.encode(state))?.let {
            throw PrivateStateUnavailableException(it)
        }
        return when (val verified = readLocked(migrate = false)) {
            is PrivateStateRead.Ready -> {
                if (verified.state != state) {
                    throw PrivateStateUnavailableException(
                        StorageFailure.CorruptEnvelope("write verification mismatch"),
                    )
                }
                verified.state
            }
            is PrivateStateRead.Unavailable -> throw PrivateStateUnavailableException(verified.failure)
        }
    }
}
