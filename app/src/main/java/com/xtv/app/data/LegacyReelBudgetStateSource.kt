package com.xtv.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xtv.app.core.budget.LegacyBudgetStateReader
import com.xtv.app.core.model.Author
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.MediaKind
import com.xtv.app.core.storage.CursorState
import com.xtv.app.core.storage.LegacyStateSource
import com.xtv.app.core.storage.PrivateStateV2
import com.xtv.app.core.storage.ReelState
import com.xtv.app.core.storage.ReelStatus
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.legacyReelStore by preferencesDataStore("xtv_reel")

/**
 * One-shot migration reader for the old reel store, and the eraser for the old budget store.
 *
 * Invalid reel JSON drops only that reel; the cursor still migrates. Clearing happens only after
 * [com.xtv.app.core.storage.PrivateStateStore] has written and reread the encrypted envelope.
 */
class LegacyReelBudgetStateSource(context: Context) : LegacyStateSource {
    private val appContext = context.applicationContext
    private val budget = LegacyBudgetStateReader(appContext)

    @Serializable
    private data class LegacyItem(
        val id: String,
        val kind: String,
        val indexInPost: Int,
        val countInPost: Int,
        val displayUrl: String,
        val posterUrl: String?,
        val width: Int,
        val height: Int,
        val durationMs: Long?,
        val authorId: String,
        val username: String,
        val name: String,
        val text: String,
        val createdAtMs: Long,
        val sensitive: Boolean,
    )

    override suspend fun read(): PrivateStateV2? {
        val prefs = appContext.legacyReelStore.data.first()
        // Stale budget preferences carry nothing worth migrating, but they must still report
        // present: clearing only runs on a migration pass that a non-null read triggers.
        val staleBudget = budget.hasState()
        if (prefs.asMap().isEmpty() && !staleBudget) return null

        val items = prefs[REEL]?.let { payload ->
            runCatching {
                legacyJson.decodeFromString<List<LegacyItem>>(payload).map { it.toDomain() }
            }.getOrNull()
        }
        val reel = items?.takeIf { it.isNotEmpty() }?.let { decoded ->
            val next = (prefs[POSITION] ?: 0).coerceIn(0, decoded.size)
            ReelState(
                reelId = legacyReelId(decoded.map { it.id }),
                itemsJson = MediaItemCodec.encode(decoded),
                nextIndex = next,
                savedAtMs = prefs[SAVED_AT] ?: 0,
                itemCount = decoded.size,
                status = if (next == decoded.size) {
                    ReelStatus.COMPLETED
                } else {
                    ReelStatus.READY
                },
            )
        }
        return PrivateStateV2(
            reel = reel,
            cursor = prefs[SINCE_ID]?.let(::CursorState),
        )
    }

    override suspend fun clear() {
        appContext.legacyReelStore.edit { it.clear() }
        budget.clear()
    }

    private fun LegacyItem.toDomain() = MediaItem(
        id = id,
        kind = MediaKind.valueOf(kind),
        indexInPost = indexInPost,
        countInPost = countInPost,
        displayUrl = displayUrl,
        posterUrl = posterUrl,
        width = width,
        height = height,
        durationMs = durationMs,
        author = Author(authorId, username, name),
        text = text,
        createdAtMs = createdAtMs,
        possiblySensitive = sensitive,
    )

    private companion object {
        val legacyJson = Json { ignoreUnknownKeys = true }
        val REEL = stringPreferencesKey("reel")
        val POSITION = intPreferencesKey("position")
        val SAVED_AT = longPreferencesKey("saved_at")
        val SINCE_ID = stringPreferencesKey("since_id")
    }
}

private fun legacyReelId(ids: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(ids.joinToString("|").encodeToByteArray())
    return "legacy-" + digest.take(8).joinToString("") { "%02x".format(it) }
}
