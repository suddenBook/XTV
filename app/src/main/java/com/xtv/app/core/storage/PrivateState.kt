package com.xtv.app.core.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The only durable private-state document.
 *
 * Keeping credentials, accounting, the cursor and the immutable reel in one versioned document is
 * intentional: purchase dispatch must never advance one of them without the others. Callers update
 * this value through [PrivateStateStore.update], which commits one encrypted DataStore value.
 */
@Serializable
data class PrivateStateV2(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Durable migration journal bit; cleared only after legacy files have been cleared. */
    val legacyCleanupPending: Boolean = false,
    val credentials: ProvisionedCredentials? = null,
    val session: StoredSession? = null,
    /** Pre-dispatch journal for rotating the canonical refresh token. */
    val sessionExchangeAttemptFingerprint: String? = null,
    val sessionExchangeAttemptRejected: Boolean = false,
    val account: AccountBinding? = null,
    val reel: ReelState? = null,
    val cursor: CursorState? = null,
    val ledger: LedgerState = LedgerState(),
    val cachedUsage: CachedUsageState? = null,
    val provisionCandidate: ProvisionCandidate? = null,
    /** Idempotency marker for Activity recreation/process restart after a completed provision. */
    val lastProvisionedRequestId: String? = null,
    val lastProvisionedSourceFingerprint: String? = null,
    val lastProvisionedPreservedPrivateState: Boolean? = null,
    val diagnostics: List<StoredDiagnostic> = emptyList(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Private state schema $schemaVersion is not supported by this build"
        }
    }

    companion object {
        /**
         * v3 removed the local spending guard's monthly accounting from [LedgerState] and the
         * billable-lookup reservations from [ProvisionCandidate]. Reading a v2 document is handled
         * in [StateEnvelopeCodec.decode], not here.
         */
        const val SCHEMA_VERSION = 3
        const val LEGACY_GUARD_SCHEMA_VERSION = 2
    }
}

@Serializable
data class ProvisionedCredentials(
    val clientId: String,
    val appOnlyBearer: String,
)

@Serializable
data class StoredSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
)

@Serializable
data class AccountBinding(
    val accountId: String,
    /** A stable project identity. Until X exposes one, the OAuth client id is the project key. */
    val projectKey: String,
)

@Serializable
data class ReelState(
    /** Serialized media items; the data adapter owns this payload's shape. */
    val itemsJson: String,
    /** The next item to play, in the inclusive range 0..itemCount. */
    val nextIndex: Int,
    val savedAtMs: Long,
    val itemCount: Int = 0,
    val status: ReelStatus = ReelStatus.READY,
    val reelId: String = "",
    /** Partial provenance survives completion/replay. */
    val partial: Boolean = false,
)

@Serializable
enum class ReelStatus { READY, PARTIAL, COMPLETED }

@Serializable
data class CursorState(val sinceId: String?)

/**
 * The billing journal.
 *
 * It no longer accumulates money. What survives is the record of one in-flight or just-finished
 * paid request, which exists so a crash cannot make XTV silently re-dispatch a request that may
 * already have been charged — a different job from the monthly guard that used to live here, and
 * one that outlives it.
 *
 * The name stays `ledger` because it is a serialized key: renaming it would make every v2 document's
 * journal an unknown field on the migration path in [StateEnvelopeCodec], silently discarding the
 * one record whose whole purpose is surviving a crash.
 */
@Serializable
data class LedgerState(
    val pendingOperation: PendingOperation? = null,
    val lastReceipt: StoredPurchaseReceipt? = null,
    val terminalOperation: StoredTerminalOperation? = null,
)

@Serializable
data class PendingOperation(
    val operationId: String,
    val phase: OperationPhase,
    val reservedUsdMicros: Long,
    val requestedPosts: Int,
    val offerToken: String,
    val knownEstimateUsdMicros: Long = 0,
    val rateCardVersion: String = "",
    val startedAtMs: Long = 0,
    val identityExposureUsdMicros: Long = 0,
)

@Serializable
enum class OperationPhase {
    PREPARED,
    IDENTITY_DISPATCHED,
    TIMELINE_DISPATCHED,
    /** Backward-compatible v2 value; treated as full timeline exposure. */
    DISPATCHED,
}

@Serializable
data class StoredResourceCounts(
    val posts: Int = 0,
    val users: Int = 0,
    val media: Int = 0,
    val identityLookups: Int = 0,
)

@Serializable
data class StoredPurchaseReceipt(
    val operationId: String,
    val requestedPosts: Int,
    val resources: StoredResourceCounts,
    val estimatedChargeUsdMicros: Long,
    val reservationUsdMicros: Long,
    val rateCardVersion: String,
    val accountingCertainty: String,
    val cursorAdvanced: Boolean,
    val warnings: List<String>,
    val completedAtMs: Long,
)

@Serializable
data class StoredTerminalOperation(
    val operationId: String,
    /** FINISHED, FAILED, or INTERRUPTED. */
    val kind: String,
    val outcome: String? = null,
    val reelId: String? = null,
    val problem: String? = null,
    val resetAtMs: Long? = null,
    val conservativelyCommittedUsdMicros: Long = 0,
)

@Serializable
data class CachedUsageState(
    val posts: Int,
    val resetDay: Int?,
    val fetchedAtMs: Long,
)

@Serializable
data class ProvisionCandidate(
    val requestId: String,
    val credentials: ProvisionedCredentials,
    val suppliedRefreshToken: String?,
    /** One-way binding to the operator-supplied source token; prevents cross-user resumption. */
    val sourceRefreshFingerprint: String = "",
    val rotatedSession: StoredSession? = null,
    /**
     * A successfully resolved `/2/users/me` result.
     *
     * Persisting this before the final identity switch makes provisioning resumable without issuing
     * a second billable User read after a process death.
     */
    val verifiedAccountId: String? = null,
    /**
     * A billable `/2/users/me` request may have left the device.
     *
     * Journaled before dispatch, because a null result, a crash, or a lost response are otherwise
     * indistinguishable from never having asked. Without it a retry silently pays for the lookup a
     * second time, and a candidate mid-recovery looks discardable.
     */
    val accountLookupDispatched: Boolean = false,
    /**
     * Fingerprint of a refresh token whose exchange was durably marked before network dispatch.
     *
     * If no rotated response was persisted, retrying that token could spend a one-time refresh
     * token twice. The candidate therefore remains blocked for explicit recovery.
     */
    val exchangeAttemptFingerprint: String? = null,
    /** True only when X definitively rejected [exchangeAttemptFingerprint]. */
    val exchangeAttemptRejected: Boolean = false,
)

@Serializable
data class StoredDiagnostic(
    val recordedAtMs: Long,
    val category: String,
    val message: String,
)

/** Stable JSON encoding inside the encrypted envelope. */
object StateEnvelopeCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    /**
     * Reads a v2 document, whose guard fields this build no longer declares.
     *
     * Leniency is scoped to that one branch on purpose. Strict decoding is what makes a renamed or
     * mistyped field fail closed rather than silently drop credentials or a billing journal, and
     * this document holds both.
     */
    private val legacyGuardJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(state: PrivateStateV2): ByteArray {
        state.validateSemantics()
        return json.encodeToString(PrivateStateV2.serializer(), state).encodeToByteArray()
    }

    /**
     * The version is rewritten in the JSON tree *before* deserialization, never on the decoded
     * value.
     *
     * `PrivateStateV2.init` is copied into the synthetic constructor kotlinx-serialization calls,
     * so a document declaring schema 2 throws from inside `decodeFromJsonElement` — a `.copy()`
     * afterwards would never be reached, and every existing install would fail closed onto
     * "Clear and start over" with its credentials and reel intact but unreadable.
     */
    fun decode(bytes: ByteArray): PrivateStateV2 {
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        // No default. Assuming the current schema for a document that does not declare one would
        // read a truncated envelope as an empty, valid, fresh install — every field here has a
        // default — which is precisely the "unreadable is not the same as absent" distinction the
        // store exists to preserve.
        val version = root["schemaVersion"]?.jsonPrimitive?.int
            ?: error("Private state envelope declares no schema version")
        // Explicit serializers, never the reified form: the reified overload resolves through
        // `serializer(typeOf<T>())`, which is a reflective lookup that only fails once R8 has run.
        val decoded = when (version) {
            PrivateStateV2.SCHEMA_VERSION ->
                json.decodeFromJsonElement(PrivateStateV2.serializer(), root)
            PrivateStateV2.LEGACY_GUARD_SCHEMA_VERSION -> legacyGuardJson.decodeFromJsonElement(
                PrivateStateV2.serializer(),
                JsonObject(
                    root + ("schemaVersion" to JsonPrimitive(PrivateStateV2.SCHEMA_VERSION)),
                ),
            )
            else -> error("Private state schema $version is not supported by this build")
        }
        return decoded.also(PrivateStateV2::validateSemantics)
    }
}

/**
 * Authenticated ciphertext can still contain logically impossible state after an old bug or manual
 * test mutation. Reject it at the envelope boundary rather than clamping: what is left in here is
 * the record of whether a paid request may already have been dispatched, and a clamped value there
 * would resolve to "no" — which is the answer that spends money twice.
 */
private fun PrivateStateV2.validateSemantics() {
    account?.let {
        require(it.accountId.isNotBlank()) { "account id is blank" }
        // v0.1 could persist an account id while its client id existed only in BuildConfig. A
        // blank project key is therefore a supported migration state until reprovisioning binds it.
    }
    fun validateSession(session: StoredSession) {
        session.let {
            require(it.accessToken.isNotBlank() || !it.refreshToken.isNullOrBlank()) {
                "session has no usable token"
            }
            require(it.expiresAtMs >= 0) { "session expiry is negative" }
        }
    }
    session?.let(::validateSession)
    require(
        sessionExchangeAttemptFingerprint == null ||
            sessionExchangeAttemptFingerprint.isSha256(),
    ) { "session exchange-attempt fingerprint is invalid" }
    require(sessionExchangeAttemptFingerprint != null || !sessionExchangeAttemptRejected) {
        "session exchange rejection has no attempted token"
    }
    credentials?.let {
        require(it.clientId.isNotBlank() || it.appOnlyBearer.isNotBlank()) {
            "credentials are empty"
        }
    }
    reel?.let {
        require(it.itemCount >= 0) { "reel item count is negative" }
        require(it.nextIndex in 0..it.itemCount) { "reel index is out of bounds" }
        if (it.status == ReelStatus.COMPLETED) {
            require(it.nextIndex == it.itemCount) { "completed reel has unfinished progress" }
        }
    }

    val pending = ledger.pendingOperation
    require(pending == null || ledger.terminalOperation == null) {
        "pending and terminal operations overlap"
    }
    require(pending == null || provisionCandidate == null) {
        "purchase and provisioning journals overlap"
    }
    pending?.let {
        require(it.operationId.isNotBlank()) { "pending operation id is blank" }
        require(it.offerToken.isNotBlank()) { "pending offer token is blank" }
        require(it.requestedPosts in 1..100) { "pending Post count is invalid" }
        require(
            it.reservedUsdMicros > 0 &&
                it.knownEstimateUsdMicros >= 0 &&
                it.identityExposureUsdMicros >= 0 &&
                it.identityExposureUsdMicros <= it.reservedUsdMicros,
        ) {
            "pending money is invalid"
        }
        require(it.rateCardVersion.isNotBlank()) { "pending rate-card version is blank" }
        require(it.startedAtMs >= 0) { "pending start time is negative" }
        if (it.phase == OperationPhase.IDENTITY_DISPATCHED) {
            require(it.identityExposureUsdMicros > 0) {
                "identity dispatch has no exposure"
            }
        }
    }
    ledger.lastReceipt?.let { receipt ->
        require(receipt.operationId.isNotBlank()) { "receipt operation id is blank" }
        require(receipt.requestedPosts in 1..100) { "receipt Post request is invalid" }
        require(
            receipt.resources.posts >= 0 &&
                receipt.resources.users >= 0 &&
                receipt.resources.media >= 0 &&
                receipt.resources.identityLookups >= 0,
        ) { "receipt resource count is negative" }
        require(
            receipt.estimatedChargeUsdMicros >= 0 &&
                receipt.reservationUsdMicros >= 0,
        ) { "receipt money is negative" }
        require(receipt.completedAtMs >= 0) { "receipt completion time is negative" }
    }
    ledger.terminalOperation?.let { terminal ->
        require(terminal.operationId.isNotBlank()) { "terminal operation id is blank" }
        require(terminal.conservativelyCommittedUsdMicros >= 0) {
            "terminal committed money is negative"
        }
        require(terminal.kind in setOf("FINISHED", "FAILED", "INTERRUPTED")) {
            "terminal kind is invalid"
        }
        if (terminal.kind == "FINISHED") {
            require(ledger.lastReceipt?.operationId == terminal.operationId) {
                "finished terminal does not match its receipt"
            }
            when (terminal.outcome) {
                "REEL_READY" -> {
                    require(reel != null && terminal.reelId == reel.reelId) {
                        "ready terminal does not match its reel"
                    }
                }
                "PARTIAL_REEL" -> {
                    require(
                        terminal.reelId == null ||
                            (reel != null && terminal.reelId == reel.reelId),
                    ) { "partial terminal does not match its reel" }
                }
                "NO_PLAYABLE_VIDEO" -> {
                    require(terminal.reelId == null) {
                        "video-empty terminal unexpectedly names a reel"
                    }
                }
                else -> error("finished terminal outcome is invalid")
            }
        }
    }
    cachedUsage?.let {
        require(it.posts >= 0) { "cached usage is negative" }
        require(it.resetDay == null || it.resetDay in 1..31) { "usage reset day is invalid" }
        require(it.fetchedAtMs >= 0) { "usage timestamp is negative" }
    }
    provisionCandidate?.let {
        require(it.requestId.isNotBlank()) { "provision request id is blank" }
        require(it.credentials.clientId.isNotBlank() && it.credentials.appOnlyBearer.isNotBlank()) {
            "provision candidate credentials are incomplete"
        }
        require(it.sourceRefreshFingerprint.isSha256()) {
            "provision source fingerprint is invalid"
        }
        require(!it.suppliedRefreshToken.isNullOrBlank() || it.rotatedSession != null) {
            "provision candidate has no recoverable token"
        }
        it.rotatedSession?.let(::validateSession)
        require(it.verifiedAccountId == null || it.verifiedAccountId.isNotBlank()) {
            "verified account id is blank"
        }
        // A verified account is only reachable through a rotated session and a dispatched lookup,
        // so either one missing is a journal this code could not have written.
        require(it.verifiedAccountId == null || it.rotatedSession != null) {
            "verified account has no rotated session"
        }
        require(it.verifiedAccountId == null || it.accountLookupDispatched) {
            "verified account has no dispatched lookup"
        }
        require(it.exchangeAttemptFingerprint == null || it.exchangeAttemptFingerprint.isSha256()) {
            "exchange-attempt fingerprint is invalid"
        }
        require(it.exchangeAttemptFingerprint != null || !it.exchangeAttemptRejected) {
            "exchange rejection has no attempted token"
        }
    }
    require(
        (lastProvisionedRequestId == null) == (lastProvisionedSourceFingerprint == null),
    ) { "provision idempotency marker is incomplete" }
    require(lastProvisionedRequestId != null || lastProvisionedPreservedPrivateState == null) {
        "orphaned provision completion result"
    }
    lastProvisionedRequestId?.let {
        require(it.isNotBlank()) { "provision idempotency request id is blank" }
    }
    lastProvisionedSourceFingerprint?.let {
        require(it.isSha256()) { "provision idempotency fingerprint is invalid" }
    }
}

private fun String.isSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
