package com.xtv.app.core.auth

import com.xtv.app.core.purchase.PurchaseProblem
import com.xtv.app.core.purchase.SessionOutcome
import com.xtv.app.core.storage.EmptyLegacyStateSource
import com.xtv.app.core.storage.EncryptedEnvelopeBackend
import com.xtv.app.core.storage.EnvelopeRead
import com.xtv.app.core.storage.PrivateStateRead
import com.xtv.app.core.storage.PrivateStateStore
import com.xtv.app.core.storage.PrivateStateV2
import com.xtv.app.core.storage.ProvisionedCredentials
import com.xtv.app.core.storage.StateEnvelopeCodec
import com.xtv.app.core.storage.StorageFailure
import com.xtv.app.core.storage.StoredSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseSessionAdapterTest {
    @Test
    fun `ambiguous runtime rotation is journaled and never retried`() = runBlocking {
        val store = storeWith(expiredState())
        var exchangeCalls = 0
        val session = PrivateStatePurchaseSession(
            privateState = store,
            nowMs = { 100_000L },
            tokenExchange = RefreshTokenExchange { _, _ ->
                exchangeCalls += 1
                RefreshTokenExchangeResult.OutcomeUnknown
            },
            accountResolver = AccountResolver { error("account lookup must not run") },
        )

        val first = session.session()
        val second = session.session()

        assertEquals(PurchaseProblem.Network, (first as SessionOutcome.Failure).problem)
        assertEquals(PurchaseProblem.Network, (second as SessionOutcome.Failure).problem)
        assertEquals(1, exchangeCalls)
        assertTrue(readyState(store).sessionExchangeAttemptFingerprint != null)
    }

    @Test
    fun `successful rotation is durable before identity lookup`() = runBlocking {
        val store = storeWith(expiredState())
        var callbackCalled = false
        val session = PrivateStatePurchaseSession(
            privateState = store,
            nowMs = { 100_000L },
            tokenExchange = RefreshTokenExchange { _, _ ->
                RefreshTokenExchangeResult.Success(
                    Tokens("new-access", "new-refresh", 500_000L),
                )
            },
            accountResolver = AccountResolver {
                assertEquals("new-refresh", readyState(store).session?.refreshToken)
                assertNull(readyState(store).sessionExchangeAttemptFingerprint)
                "account"
            },
        )

        val result = session.session {
            callbackCalled = true
            true
        }

        assertTrue(callbackCalled)
        assertEquals(
            SessionOutcome.Ready("new-access", "account", identityLookupCharged = true),
            result,
        )
    }

    @Test
    fun `definitive runtime rejection stays definitive without retry`() = runBlocking {
        val store = storeWith(expiredState())
        var exchangeCalls = 0
        val session = PrivateStatePurchaseSession(
            privateState = store,
            nowMs = { 100_000L },
            tokenExchange = RefreshTokenExchange { _, _ ->
                exchangeCalls += 1
                RefreshTokenExchangeResult.Rejected
            },
            accountResolver = AccountResolver { error("account lookup must not run") },
        )

        val first = session.session() as SessionOutcome.Failure
        val second = session.session() as SessionOutcome.Failure

        assertEquals(PurchaseProblem.AuthenticationRequired, first.problem)
        assertEquals(PurchaseProblem.AuthenticationRequired, second.problem)
        assertEquals(1, exchangeCalls)
        assertTrue(readyState(store).sessionExchangeAttemptRejected)
    }

    @Test
    fun `identity lookup does not dispatch until purchase journal accepts it`() = runBlocking {
        val state = expiredState().copy(
            session = StoredSession("valid-access", "refresh", 500_000L),
        )
        val store = storeWith(state)
        var resolverCalls = 0
        val session = PrivateStatePurchaseSession(
            privateState = store,
            nowMs = { 100_000L },
            tokenExchange = RefreshTokenExchange {
                    _, _ -> error("refresh must not run")
            },
            accountResolver = AccountResolver {
                resolverCalls += 1
                "account"
            },
        )

        val result = session.session { false }

        assertEquals(
            PurchaseProblem.StorageUnavailable,
            (result as SessionOutcome.Failure).problem,
        )
        assertEquals(0, resolverCalls)
    }

    private fun expiredState() = PrivateStateV2(
        credentials = ProvisionedCredentials("client", "bearer"),
        session = StoredSession("expired-access", "refresh", 1_000L),
    )

    private fun storeWith(state: PrivateStateV2) = PrivateStateStore(
        SessionMemoryBackend(StateEnvelopeCodec.encode(state)),
        EmptyLegacyStateSource,
    )

    private suspend fun readyState(store: PrivateStateStore): PrivateStateV2 =
        (store.read() as PrivateStateRead.Ready).state
}

private class SessionMemoryBackend(initial: ByteArray? = null) : EncryptedEnvelopeBackend {
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
