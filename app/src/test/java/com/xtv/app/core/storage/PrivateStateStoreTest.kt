package com.xtv.app.core.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateStateStoreTest {
    @Test
    fun `legacy state is cleared only after the canonical envelope verifies`() = runBlocking {
        val backend = MemoryEnvelopeBackend()
        val legacy = MemoryLegacySource(
            PrivateStateV2(
                credentials = ProvisionedCredentials("client", "bearer"),
                session = StoredSession("access", "refresh", 123L),
            ),
        )
        val store = PrivateStateStore(backend, legacy)

        val loaded = store.read()

        assertEquals("client", (loaded as PrivateStateRead.Ready).state.credentials?.clientId)
        assertTrue(legacy.cleared)
        assertEquals(2, backend.writes)
    }

    @Test
    fun `failed canonical verification retains legacy state`() = runBlocking {
        val backend = MemoryEnvelopeBackend(corruptAfterWrite = true)
        val legacy = MemoryLegacySource(
            PrivateStateV2(credentials = ProvisionedCredentials("client", "bearer")),
        )
        val store = PrivateStateStore(backend, legacy)

        val loaded = store.read()

        assertTrue(loaded is PrivateStateRead.Unavailable)
        assertFalse(legacy.cleared)
    }

    @Test
    fun `an existing canonical envelope makes migration idempotent`() = runBlocking {
        val canonical = PrivateStateV2(credentials = ProvisionedCredentials("new", "bearer"))
        val backend = MemoryEnvelopeBackend(StateEnvelopeCodec.encode(canonical))
        val legacy = MemoryLegacySource(
            PrivateStateV2(credentials = ProvisionedCredentials("old", "bearer")),
        )

        val loaded = PrivateStateStore(backend, legacy).read()

        assertEquals("new", (loaded as PrivateStateRead.Ready).state.credentials?.clientId)
        assertFalse(legacy.cleared)
        assertEquals(0, backend.writes)
    }

    @Test
    fun `interrupted legacy cleanup resumes from the canonical marker`() = runBlocking {
        val canonical = PrivateStateV2(
            credentials = ProvisionedCredentials("new", "bearer"),
            legacyCleanupPending = true,
        )
        val backend = MemoryEnvelopeBackend(StateEnvelopeCodec.encode(canonical))
        val legacy = MemoryLegacySource(null)

        val loaded = PrivateStateStore(backend, legacy).read()

        assertFalse((loaded as PrivateStateRead.Ready).state.legacyCleanupPending)
        assertTrue(legacy.cleared)
    }

    @Test
    fun `key loss fails closed instead of looking like signed out`() = runBlocking {
        val backend = MemoryEnvelopeBackend(
            initial = byteArrayOf(1),
            readFailure = StorageFailure.KeyUnavailable,
        )

        val loaded = PrivateStateStore(backend, EmptyLegacyStateSource).read()

        assertEquals(
            StorageFailure.KeyUnavailable,
            (loaded as PrivateStateRead.Unavailable).failure,
        )
    }

    @Test
    fun `updates replace one atomic snapshot`() = runBlocking {
        val backend = MemoryEnvelopeBackend()
        val store = PrivateStateStore(backend, EmptyLegacyStateSource)

        store.update {
            it.copy(
                credentials = ProvisionedCredentials("client", "bearer"),
                cursor = CursorState("101"),
            )
        }
        val loaded = store.read() as PrivateStateRead.Ready

        assertEquals("client", loaded.state.credentials?.clientId)
        assertEquals("101", loaded.state.cursor?.sinceId)
    }

    @Test
    fun `package legacy sources compose before any of them are cleared`() = runBlocking {
        val auth = MemoryLegacySource(
            PrivateStateV2(credentials = ProvisionedCredentials("client", "bearer")),
        )
        val reel = MemoryLegacySource(
            PrivateStateV2(
                reel = ReelState("[]", nextIndex = 0, savedAtMs = 1L),
                cursor = CursorState("101"),
            ),
        )
        val store = PrivateStateStore(
            MemoryEnvelopeBackend(),
            CompositeLegacyStateSource(listOf(auth, reel)),
        )

        val loaded = (store.read() as PrivateStateRead.Ready).state

        assertEquals("client", loaded.credentials?.clientId)
        assertEquals("101", loaded.cursor?.sinceId)
        assertTrue(auth.cleared)
        assertTrue(reel.cleared)
    }

    /**
     * The upgrade every existing install takes.
     *
     * The fixture is hand-written because the v3 encoder can no longer emit the fields being
     * dropped — round-tripping through `encode` would quietly take the strict path and prove
     * nothing. Note also that `schemaVersion` has to be rewritten in the JSON tree before
     * deserialization: `PrivateStateV2.init` is compiled into the synthetic constructor
     * kotlinx-serialization calls, so a document declaring 2 throws before any code can adjust it.
     */
    @Test
    fun `a v2 envelope upgrades in place instead of forcing a wipe`() = runBlocking {
        val v2 = """
            {"schemaVersion":2,"legacyCleanupPending":false,
             "credentials":{"clientId":"client","appOnlyBearer":"bearer"},
             "session":{"accessToken":"access","refreshToken":"refresh","expiresAtMs":500000},
             "sessionExchangeAttemptFingerprint":null,"sessionExchangeAttemptRejected":false,
             "account":{"accountId":"account-1","projectKey":"client"},
             "reel":{"itemsJson":"[]","nextIndex":0,"savedAtMs":10,"itemCount":0,
                     "status":"READY","reelId":"old-reel","partial":false},
             "cursor":{"sinceId":"99"},
             "ledger":{"utcMonth":"2026-07","committedUsdMicros":5000,"reservedUsdMicros":180000,
                       "postsRead":30,
                       "pendingOperation":{"operationId":"in-flight","phase":"TIMELINE_DISPATCHED",
                                           "reservedUsdMicros":180000,"requestedPosts":30,
                                           "offerToken":"token","knownEstimateUsdMicros":150000,
                                           "rateCardVersion":"x-pay-per-use-2026-07-27",
                                           "utcMonth":"2026-07","startedAtMs":1,
                                           "identityExposureUsdMicros":0},
                       "lastReceipt":null,"terminalOperation":null},
             "cachedUsage":{"posts":12,"resetDay":26,"fetchedAtMs":10},
             "provisionCandidate":null,"lastProvisionedRequestId":null,
             "lastProvisionedSourceFingerprint":null,
             "lastProvisionedPreservedPrivateState":null,"diagnostics":[]}
        """.trimIndent().encodeToByteArray()

        val loaded = PrivateStateStore(
            MemoryEnvelopeBackend(v2),
            EmptyLegacyStateSource,
        ).read()
        val state = (loaded as PrivateStateRead.Ready).state

        assertEquals(PrivateStateV2.SCHEMA_VERSION, state.schemaVersion)
        assertEquals("client", state.credentials?.clientId)
        assertEquals("refresh", state.session?.refreshToken)
        assertEquals("account-1", state.account?.accountId)
        assertEquals("old-reel", state.reel?.reelId)
        assertEquals("99", state.cursor?.sinceId)
        assertEquals(12, state.cachedUsage?.posts)
        // The one record whose entire job is surviving a crash must also survive an upgrade.
        assertEquals("in-flight", state.ledger.pendingOperation?.operationId)
        assertEquals(180_000L, state.ledger.pendingOperation?.reservedUsdMicros)
    }

    @Test
    fun `an unknown schema fails closed rather than guessing`() = runBlocking {
        val future = StateEnvelopeCodec.encode(PrivateStateV2()).decodeToString()
            .replace("\"schemaVersion\":3", "\"schemaVersion\":99")
            .encodeToByteArray()

        val loaded = PrivateStateStore(
            MemoryEnvelopeBackend(future),
            EmptyLegacyStateSource,
        ).read()

        assertTrue(loaded is PrivateStateRead.Unavailable)
    }

    @Test
    fun `semantic corruption fails closed instead of resetting the ledger`() = runBlocking {
        val valid = StateEnvelopeCodec.encode(
            PrivateStateV2(
                reel = ReelState("[]", nextIndex = 0, savedAtMs = 1L, itemCount = 0),
            ),
        ).decodeToString()
        val corrupt = valid.replace(
            "\"nextIndex\":0",
            "\"nextIndex\":-1",
        ).encodeToByteArray()

        val loaded = PrivateStateStore(
            MemoryEnvelopeBackend(corrupt),
            EmptyLegacyStateSource,
        ).read()

        assertTrue(loaded is PrivateStateRead.Unavailable)
        assertTrue(
            (loaded as PrivateStateRead.Unavailable).failure is
                StorageFailure.CorruptEnvelope,
        )
    }

    @Test
    fun `guarded clear refuses a durable paid request`() = runBlocking {
        val pending = PendingOperation(
            operationId = "paid",
            phase = OperationPhase.TIMELINE_DISPATCHED,
            reservedUsdMicros = 100,
            requestedPosts = 1,
            offerToken = "offer",
            knownEstimateUsdMicros = 50,
            rateCardVersion = "rates",
            startedAtMs = 1,
        )
        val initial = PrivateStateV2(ledger = LedgerState(pendingOperation = pending))
        val backend = MemoryEnvelopeBackend(StateEnvelopeCodec.encode(initial))
        val store = PrivateStateStore(backend, EmptyLegacyStateSource)

        val result = store.clearGuarded(
            allowUnavailableRecovery = false,
            canClear = { it.ledger.pendingOperation == null },
        )

        assertEquals(PrivateStateClearResult.Rejected, result)
        assertTrue(store.read() is PrivateStateRead.Ready)
    }
}

private class MemoryEnvelopeBackend(
    initial: ByteArray? = null,
    private val corruptAfterWrite: Boolean = false,
    private val readFailure: StorageFailure? = null,
) : EncryptedEnvelopeBackend {
    private var bytes = initial
    var writes = 0

    override suspend fun read(): EnvelopeRead {
        readFailure?.let { return EnvelopeRead.Failed(it) }
        return bytes?.let(EnvelopeRead::Present) ?: EnvelopeRead.Missing
    }

    override suspend fun write(plaintext: ByteArray): StorageFailure? {
        writes += 1
        bytes = if (corruptAfterWrite) byteArrayOf(0x7f) else plaintext
        return null
    }

    override suspend fun clear(): StorageFailure? {
        bytes = null
        return null
    }
}

private class MemoryLegacySource(private val state: PrivateStateV2?) : LegacyStateSource {
    var cleared = false

    override suspend fun read(): PrivateStateV2? = state

    override suspend fun clear() {
        cleared = true
    }
}
