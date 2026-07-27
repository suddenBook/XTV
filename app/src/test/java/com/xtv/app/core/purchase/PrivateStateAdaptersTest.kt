package com.xtv.app.core.purchase

import com.xtv.app.core.model.Author
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.MediaKind
import com.xtv.app.core.playback.CheckpointResult
import com.xtv.app.core.storage.EmptyLegacyStateSource
import com.xtv.app.core.storage.EncryptedEnvelopeBackend
import com.xtv.app.core.storage.EnvelopeRead
import com.xtv.app.core.storage.PrivateStateRead
import com.xtv.app.core.storage.PrivateStateStore
import com.xtv.app.core.storage.PrivateStateV2
import com.xtv.app.core.storage.StorageFailure
import com.xtv.app.core.storage.StateEnvelopeCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivateStateAdaptersTest {
    @Test
    fun `purchase projection round trips journal reel cursor and scopes`() = runBlocking {
        val privateState = newStore()
        val port = PrivateStatePurchasePort(privateState)
        val quote = RateCard.current().quote(3, identityLookupNeeded = true)
        val pending = DurablePurchase(
            id = OperationId("operation"),
            token = OfferToken("offer"),
            requestedPosts = 3,
            quote = quote,
            stage = DurableStage.IDENTITY_DISPATCHED,
            startedAtMs = 42,
            identityExposure = RateCard.current().userRead,
        )
        val reel = PurchasedReel(
            id = "reel",
            items = listOf(video("one")),
            status = ReelStatus.IN_PROGRESS,
            nextIndex = 0,
            createdAtMs = 40,
        )

        port.update {
            PurchaseRecord(
                pending = pending,
                cursor = "99",
                currentReel = reel,
                accountId = "account",
                accountScope = "account",
                projectScope = "client",
            )
        }
        val loaded = port.read()

        assertEquals(pending.id, loaded.pending?.id)
        assertEquals(pending.stage, loaded.pending?.stage)
        assertEquals(pending.quote.reservation, loaded.pending?.quote?.reservation)
        assertEquals(pending.identityExposure, loaded.pending?.identityExposure)
        assertEquals(reel, loaded.currentReel)
        assertEquals("99", loaded.cursor)
        assertEquals("account", loaded.accountScope)
        assertEquals("client", loaded.projectScope)
    }

    @Test
    fun `playback checkpoint advances only the matching durable reel`() = runBlocking {
        val privateState = newStore()
        val port = PrivateStatePurchasePort(privateState)
        port.update {
            PurchaseRecord(
                currentReel = PurchasedReel(
                    id = "reel",
                    items = listOf(video("one"), video("two")),
                    status = ReelStatus.IN_PROGRESS,
                    nextIndex = 0,
                ),
            )
        }
        val checkpointScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val checkpoint = PrivateStatePlaybackCheckpoint(privateState, checkpointScope)
        val stored = CompletableDeferred<CheckpointResult>()

        checkpoint.save("reel", 2, stored::complete)
        assertEquals(CheckpointResult.Stored, stored.await())
        assertEquals(2, port.read().currentReel?.nextIndex)

        val stale = CompletableDeferred<CheckpointResult>()
        checkpoint.save("old-reel", 0, stale::complete)
        assertEquals(CheckpointResult.StaleReel, stale.await())
        assertEquals(2, port.read().currentReel?.nextIndex)
        assertNull((privateState.read() as? PrivateStateRead.Unavailable)?.failure)
        checkpointScope.cancel()
    }

    private fun newStore(): PrivateStateStore = PrivateStateStore(
        AdapterMemoryBackend(StateEnvelopeCodec.encode(PrivateStateV2())),
        EmptyLegacyStateSource,
    )

    private fun video(id: String) = MediaItem(
        id = id,
        kind = MediaKind.VIDEO,
        indexInPost = 0,
        countInPost = 1,
        displayUrl = "https://example.invalid/$id.mp4",
        width = 1920,
        height = 1080,
        author = Author("author", "author", "Author"),
        text = "",
        createdAtMs = 0,
    )
}

private class AdapterMemoryBackend(initial: ByteArray? = null) : EncryptedEnvelopeBackend {
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
