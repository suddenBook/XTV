package com.xtv.app.core.auth

import com.xtv.app.core.purchase.RateCard
import com.xtv.app.core.storage.AccountBinding
import com.xtv.app.core.storage.CachedUsageState
import com.xtv.app.core.storage.CursorState
import com.xtv.app.core.storage.EmptyLegacyStateSource
import com.xtv.app.core.storage.EncryptedEnvelopeBackend
import com.xtv.app.core.storage.EnvelopeRead
import com.xtv.app.core.storage.LedgerState
import com.xtv.app.core.storage.PrivateStateRead
import com.xtv.app.core.storage.PrivateStateStore
import com.xtv.app.core.storage.PrivateStateV2
import com.xtv.app.core.storage.ProvisionedCredentials
import com.xtv.app.core.storage.ReelState
import com.xtv.app.core.storage.StateEnvelopeCodec
import com.xtv.app.core.storage.StorageFailure
import com.xtv.app.core.storage.StoredPurchaseReceipt
import com.xtv.app.core.storage.StoredResourceCounts
import com.xtv.app.core.storage.StoredTerminalOperation
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningCoordinatorTest {
    @Test
    fun `all three supplied credentials are required before any exchange`() = runBlocking {
        val exchange = FakeExchange()
        val coordinator = coordinator(exchange = exchange)

        val result = coordinator.provision(
            ProvisioningRequest("request-1", "", "refresh", "bearer"),
        )

        assertTrue(result is ProvisioningResult.Rejected)
        assertEquals(0, exchange.calls)
    }

    @Test
    fun `rotated token is durably staged before account lookup`() = runBlocking {
        val backend = MemoryBackend()
        val store = PrivateStateStore(backend, EmptyLegacyStateSource)
        val exchange = FakeExchange(
            Tokens("new-access", "new-refresh", 500_000L),
        )
        val resolver = object : AccountResolver {
            override suspend fun resolve(accessToken: String): String? {
                val staged = (store.read() as PrivateStateRead.Ready).state.provisionCandidate
                assertEquals("new-refresh", staged?.rotatedSession?.refreshToken)
                return "account-1"
            }
        }
        val coordinator = ProvisioningCoordinator(
            store,
            exchange,
            resolver,
        )

        val result = coordinator.provision(
            ProvisioningRequest("request-1", "client", "old-refresh", "bearer"),
        )

        assertTrue(result is ProvisioningResult.Committed)
        val state = (store.read() as PrivateStateRead.Ready).state
        assertEquals("new-refresh", state.session?.refreshToken)
        assertNull(state.provisionCandidate)
    }

    @Test
    fun `retry resumes a durably rotated candidate without spending the old token again`() =
        runBlocking {
            val initial = PrivateStateV2(
                provisionCandidate = com.xtv.app.core.storage.ProvisionCandidate(
                    requestId = "interrupted-request",
                    credentials = ProvisionedCredentials("client", "bearer"),
                    suppliedRefreshToken = null,
                    sourceRefreshFingerprint = fingerprint("spent-refresh"),
                    rotatedSession = com.xtv.app.core.storage.StoredSession(
                        "staged-access",
                        "staged-refresh",
                        500_000L,
                    ),
                ),
            )
            val exchange = FakeExchange()
            val coordinator = coordinator(initial, exchange = exchange)

            val result = coordinator.provision(
                ProvisioningRequest("retry-request", "client", "spent-refresh", "bearer"),
            )

            assertTrue(result is ProvisioningResult.Committed)
            assertEquals(0, exchange.calls)
        }

    @Test
    fun `same account and project preserves reel cursor and ledger`() = runBlocking {
        val initial = PrivateStateV2(
            credentials = ProvisionedCredentials("client", "old-bearer"),
            account = AccountBinding("account-1", "client"),
            reel = ReelState("[]", nextIndex = 3, savedAtMs = 10L, itemCount = 3),
            cursor = CursorState("99"),
            ledger = priorJournal(),
        )
        val coordinator = coordinator(initial)

        coordinator.provision(
            ProvisioningRequest("request-1", "client", "refresh", "new-bearer"),
        )
        val state = coordinator.state()

        assertEquals(3, state.reel?.nextIndex)
        assertEquals("99", state.cursor?.sinceId)
        assertEquals("prior", state.ledger.lastReceipt?.operationId)
        assertEquals("prior", state.ledger.terminalOperation?.operationId)
    }

    @Test
    fun `different identity clears private viewing and accounting state`() = runBlocking {
        val initial = PrivateStateV2(
            credentials = ProvisionedCredentials("old-client", "old-bearer"),
            account = AccountBinding("account-old", "old-client"),
            reel = ReelState("[]", nextIndex = 3, savedAtMs = 10L, itemCount = 3),
            cursor = CursorState("99"),
            ledger = priorJournal(),
        )
        val coordinator = coordinator(initial, accountId = "account-new")

        coordinator.provision(
            ProvisioningRequest("request-1", "new-client", "refresh", "new-bearer"),
        )
        val state = coordinator.state()

        assertNull(state.reel)
        assertNull(state.cursor)
        assertNull(state.ledger.lastReceipt)
        assertNull(state.ledger.terminalOperation)
    }

    @Test
    fun `failed verification leaves canonical identity untouched and retains recovery`() = runBlocking {
        val initial = PrivateStateV2(
            credentials = ProvisionedCredentials("client", "bearer"),
            account = AccountBinding("account-1", "client"),
        )
        val coordinator = coordinator(initial, accountId = null)

        val result = coordinator.provision(
            ProvisioningRequest("request-7", "other", "refresh", "other-bearer"),
        )

        assertTrue(result is ProvisioningResult.Rejected)
        val state = coordinator.state()
        assertEquals("client", state.credentials?.clientId)
        assertEquals("rotated", state.provisionCandidate?.rotatedSession?.refreshToken)
        assertNull(state.provisionCandidate?.verifiedAccountId)
    }

    @Test
    fun `same fingerprint retry reuses the rotated token instead of spending a new one`() =
        runBlocking {
            val backend = MemoryBackend()
            val store = PrivateStateStore(backend, EmptyLegacyStateSource)
            val exchange = FakeExchange(Tokens("access", "rotated", 500_000L))
            var resolverCalls = 0
            val coordinator = ProvisioningCoordinator(
                store,
                exchange,
                AccountResolver {
                    resolverCalls += 1
                    if (resolverCalls == 1) null else "account-1"
                },
                nowMs = { 1_000L },
            )
            val request = ProvisioningRequest(
                "request-1",
                "client",
                "source-refresh",
                "bearer",
            )

            val first = coordinator.provision(request)
            val second = coordinator.provision(request)

            assertTrue(first is ProvisioningResult.Rejected)
            assertTrue(second is ProvisioningResult.Committed)
            assertEquals(1, exchange.calls)
            assertEquals(2, resolverCalls)
            assertNull(coordinator.state().provisionCandidate)
        }

    @Test
    fun `renewed source replaces a same-project recovery candidate`() = runBlocking {
        val initial = PrivateStateV2(
            provisionCandidate = com.xtv.app.core.storage.ProvisionCandidate(
                requestId = "interrupted",
                credentials = ProvisionedCredentials("client", "bearer"),
                suppliedRefreshToken = null,
                sourceRefreshFingerprint = fingerprint("first-source"),
                rotatedSession = com.xtv.app.core.storage.StoredSession(
                    "access",
                    "rotated",
                    500_000L,
                ),
            ),
        )
        val exchange = FakeExchange(Tokens("other", "other-rotated", 500_000L))
        val coordinator = coordinator(initial, exchange = exchange)

        val result = coordinator.provision(
            ProvisioningRequest("new-request", "client", "other-source", "bearer"),
        )

        assertTrue(result is ProvisioningResult.Committed)
        assertEquals(1, exchange.calls)
        assertEquals("other-rotated", coordinator.state().session?.refreshToken)
    }

    @Test
    fun `different account on the same project retains ledger but clears account content`() =
        runBlocking {
            val initial = PrivateStateV2(
                credentials = ProvisionedCredentials("client", "bearer"),
                account = AccountBinding("old-account", "client"),
                reel = ReelState(
                    "[]",
                    nextIndex = 0,
                    savedAtMs = 10L,
                    reelId = "old-reel",
                ),
                cursor = CursorState("99"),
                ledger = LedgerState(
                    lastReceipt = StoredPurchaseReceipt(
                        operationId = "old-operation",
                        requestedPosts = 1,
                        resources = StoredResourceCounts(posts = 1),
                        estimatedChargeUsdMicros = 5_000,
                        reservationUsdMicros = 35_000,
                        rateCardVersion = RateCard.current().version,
                        accountingCertainty = "SETTLED_RESPONSE",
                        cursorAdvanced = true,
                        warnings = emptyList(),
                        completedAtMs = 9,
                    ),
                    terminalOperation = StoredTerminalOperation(
                        operationId = "old-operation",
                        kind = "FINISHED",
                        outcome = "REEL_READY",
                        reelId = "old-reel",
                    ),
                ),
                cachedUsage = CachedUsageState(12, 26, 10),
            )
            val coordinator = coordinator(initial, accountId = "new-account")

            val result = coordinator.provision(
                ProvisioningRequest("request", "client", "refresh", "new-bearer"),
            )
            val state = coordinator.state()

            assertEquals(false, (result as ProvisioningResult.Committed).preservedPrivateState)
            assertNull(state.reel)
            assertNull(state.cursor)
            assertEquals("old-operation", state.ledger.lastReceipt?.operationId)
            assertEquals(12, state.cachedUsage?.posts)
            assertNull(state.ledger.terminalOperation)
        }

    @Test
    fun `unknown legacy project conservatively retains ledger but not cross-account content`() =
        runBlocking {
            val initial = PrivateStateV2(
                account = AccountBinding("old-account", ""),
                reel = ReelState("[]", nextIndex = 0, savedAtMs = 10L),
                cursor = CursorState("99"),
                ledger = priorJournal(),
                cachedUsage = CachedUsageState(12, 26, 10),
            )
            val coordinator = coordinator(initial, accountId = "new-account")

            coordinator.provision(
                ProvisioningRequest("request", "client", "refresh", "bearer"),
            )
            val state = coordinator.state()

            assertNull(state.reel)
            assertNull(state.cursor)
            assertEquals("prior", state.ledger.lastReceipt?.operationId)
            assertNull(state.cachedUsage)
        }

    @Test
    fun `definitively invalid bearer prevents token exchange`() = runBlocking {
        val exchange = FakeExchange(Tokens("access", "rotated", 500_000L))
        val coordinator = ProvisioningCoordinator(
            PrivateStateStore(MemoryBackend(), EmptyLegacyStateSource),
            exchange,
            AccountResolver { "account" },
            bearerValidator = AppBearerValidator { AppBearerValidation.InvalidAuth },
            nowMs = { 1_000L },
        )

        val result = coordinator.provision(
            ProvisioningRequest("request", "client", "refresh", "bad-bearer"),
        )

        assertEquals(
            ProvisioningResult.Reason.BEARER_REJECTED,
            (result as ProvisioningResult.Rejected).reason,
        )
        assertEquals(0, exchange.calls)
    }

    @Test
    fun `bearer outage is not mislabeled as invalid credential`() = runBlocking {
        val exchange = FakeExchange(Tokens("access", "rotated", 500_000L))
        val coordinator = ProvisioningCoordinator(
            PrivateStateStore(MemoryBackend(), EmptyLegacyStateSource),
            exchange,
            AccountResolver { "account" },
            bearerValidator = AppBearerValidator { AppBearerValidation.Unavailable },
            nowMs = { 1_000L },
        )

        val result = coordinator.provision(
            ProvisioningRequest("request", "client", "refresh", "bearer"),
        )

        assertEquals(
            ProvisioningResult.Reason.BEARER_VALIDATION_UNAVAILABLE,
            (result as ProvisioningResult.Rejected).reason,
        )
        assertEquals(0, exchange.calls)
    }

    @Test
    fun `expired staged access refreshes from staged rotation not original source`() = runBlocking {
        val initial = PrivateStateV2(
            provisionCandidate = com.xtv.app.core.storage.ProvisionCandidate(
                requestId = "old",
                credentials = ProvisionedCredentials("client", "bearer"),
                suppliedRefreshToken = null,
                sourceRefreshFingerprint = fingerprint("source"),
                rotatedSession = com.xtv.app.core.storage.StoredSession(
                    "expired-access",
                    "staged-refresh",
                    2_000L,
                ),
            ),
        )
        var exchangedToken: String? = null
        val coordinator = ProvisioningCoordinator(
            PrivateStateStore(
                MemoryBackend(StateEnvelopeCodec.encode(initial)),
                EmptyLegacyStateSource,
            ),
            RefreshTokenExchange { token, _ ->
                exchangedToken = token
                RefreshTokenExchangeResult.Success(
                    Tokens("fresh-access", "fresh-refresh", 500_000L),
                )
            },
            AccountResolver { "account" },
            nowMs = { 100_000L },
        )

        val result = coordinator.provision(
            ProvisioningRequest("retry", "client", "source", "bearer"),
        )

        assertTrue(result is ProvisioningResult.Committed)
        assertEquals("staged-refresh", exchangedToken)
        assertEquals("fresh-refresh", coordinator.state().session?.refreshToken)
    }

    @Test
    fun `ambiguous token exchange is journaled and never retried automatically`() = runBlocking {
        val exchange = FakeExchange(
            result = RefreshTokenExchangeResult.OutcomeUnknown,
        )
        val coordinator = coordinator(exchange = exchange)
        val request = ProvisioningRequest("request", "client", "refresh", "bearer")

        val first = coordinator.provision(request)
        val second = coordinator.provision(request)

        assertEquals(
            ProvisioningResult.Reason.TOKEN_EXCHANGE_OUTCOME_UNKNOWN,
            (first as ProvisioningResult.Rejected).reason,
        )
        assertEquals(
            ProvisioningResult.Reason.TOKEN_EXCHANGE_OUTCOME_UNKNOWN,
            (second as ProvisioningResult.Rejected).reason,
        )
        assertEquals(1, exchange.calls)
    }

    @Test
    fun `idempotent retry reports the original preservation result`() = runBlocking {
        val initial = PrivateStateV2(
            account = AccountBinding("old-account", "client"),
            ledger = priorJournal(),
        )
        val coordinator = coordinator(initial, accountId = "new-account")
        val request = ProvisioningRequest("request", "client", "refresh", "bearer")

        val first = coordinator.provision(request) as ProvisioningResult.Committed
        val second = coordinator.provision(request) as ProvisioningResult.Committed

        assertEquals(false, first.preservedPrivateState)
        assertEquals(false, second.preservedPrivateState)
    }

    @Test
    fun `known client without cached account retains its project ledger`() = runBlocking {
        val initial = PrivateStateV2(
            credentials = ProvisionedCredentials("client", "old-bearer"),
            ledger = priorJournal(),
        )
        val coordinator = coordinator(initial)

        coordinator.provision(
            ProvisioningRequest("request", "client", "refresh", "new-bearer"),
        )

        assertEquals("prior", coordinator.state().ledger.lastReceipt?.operationId)
    }

    private suspend fun ProvisioningCoordinator.state(): PrivateStateV2 =
        (privateState.read() as PrivateStateRead.Ready).state

    private fun coordinator(
        initial: PrivateStateV2 = PrivateStateV2(),
        exchange: FakeExchange = FakeExchange(Tokens("access", "rotated", 500_000L)),
        accountId: String? = "account-1",
    ): ProvisioningCoordinator {
        val backend = MemoryBackend(StateEnvelopeCodec.encode(initial))
        return ProvisioningCoordinator(
            PrivateStateStore(backend, EmptyLegacyStateSource),
            exchange,
            object : AccountResolver {
                override suspend fun resolve(accessToken: String): String? = accountId
            },
            nowMs = { 1_000L },
        )
    }

    /**
     * A journal from a purchase that already happened.
     *
     * Reprovisioning has two different retention rules and this can tell them apart: the receipt
     * belongs to the developer project and survives an account switch, while the terminal is what
     * the *viewer* was last shown and does not.
     */
    private fun priorJournal() = LedgerState(
        lastReceipt = StoredPurchaseReceipt(
            operationId = "prior",
            requestedPosts = 30,
            resources = StoredResourceCounts(posts = 30),
            estimatedChargeUsdMicros = 150_000,
            reservationUsdMicros = 180_000,
            rateCardVersion = RateCard.current().version,
            accountingCertainty = "SETTLED_RESPONSE",
            cursorAdvanced = true,
            warnings = emptyList(),
            completedAtMs = 9,
        ),
        terminalOperation = StoredTerminalOperation(
            operationId = "prior",
            kind = "INTERRUPTED",
            conservativelyCommittedUsdMicros = 180_000,
        ),
    )
}

private class FakeExchange(
    tokens: Tokens? = null,
    private val result: RefreshTokenExchangeResult =
        tokens?.let(RefreshTokenExchangeResult::Success)
            ?: RefreshTokenExchangeResult.OutcomeUnknown,
) : RefreshTokenExchange {
    var calls = 0

    override suspend fun exchange(
        refreshToken: String,
        clientId: String,
    ): RefreshTokenExchangeResult {
        calls += 1
        return result
    }
}

private class MemoryBackend(initial: ByteArray? = null) : EncryptedEnvelopeBackend {
    private var bytes = initial

    override suspend fun read(): EnvelopeRead =
        bytes?.let(EnvelopeRead::Present) ?: EnvelopeRead.Missing

    override suspend fun write(plaintext: ByteArray): StorageFailure? {
        bytes = plaintext
        return null
    }

    override suspend fun clear(): StorageFailure? {
        bytes = null
        return null
    }
}

private fun fingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
