package com.xtv.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xtv.app.core.model.MediaItem
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.reelStore by preferencesDataStore("xtv_reel")

/**
 * Persists the current reel so it survives Back, a restart, or the process being killed, plus the
 * newest post id so a later fetch does not pay for posts already bought.
 *
 * **Why DataStore and not Room.** The plan called for Room on the assumption of an unbounded "unseen
 * backlog" — tens of thousands of ids to query. Measurement removed that: this timeline outruns the
 * viewer roughly eighty to one, so there is no backlog to keep, only a fixed head-budget reel of a
 * few dozen items and a single cursor. That is a small JSON blob written once per session, and a
 * database for it would be ceremony. Room becomes justified again the moment durable per-item
 * history (star, hide, watch counts across months) lands — see task #6.
 *
 * The snapshot is deliberately frozen at build time: a refresh must never insert new items ahead of
 * the one being watched.
 */
class ReelStore(private val context: Context) {

    @Serializable
    private data class StoredItem(
        val id: String, val kind: String, val indexInPost: Int, val countInPost: Int,
        val displayUrl: String, val posterUrl: String?, val width: Int, val height: Int,
        val durationMs: Long?, val authorId: String, val username: String, val name: String,
        val text: String, val createdAtMs: Long, val sensitive: Boolean,
    )

    suspend fun saveReel(items: List<MediaItem>, newestPostId: String?) {
        val payload = json.encodeToString(items.map { it.toStored() })
        context.reelStore.edit { prefs ->
            prefs[REEL] = payload
            prefs[POSITION] = 0
            prefs[SAVED_AT] = System.currentTimeMillis()
            newestPostId?.let { prefs[SINCE_ID] = it }
        }
    }

    suspend fun loadReel(): Saved? {
        val prefs = context.reelStore.data.first()
        val payload = prefs[REEL] ?: return null
        val items = runCatching {
            json.decodeFromString<List<StoredItem>>(payload).map { it.toDomain() }
        }.getOrNull() ?: return null
        if (items.isEmpty()) return null
        return Saved(
            items = items,
            position = (prefs[POSITION] ?: 0).coerceIn(0, items.lastIndex),
            savedAtMs = prefs[SAVED_AT] ?: 0L,
        )
    }

    suspend fun savePosition(index: Int) {
        context.reelStore.edit { it[POSITION] = index }
    }

    /**
     * Newest post id from the last fetch. Passed as `since_id` so the next reel starts above it —
     * the point is not completeness (the gap is abandoned on purpose) but not paying twice.
     */
    suspend fun sinceId(): String? = context.reelStore.data.first()[SINCE_ID]

    suspend fun clearReel() {
        context.reelStore.edit { prefs ->
            prefs.remove(REEL)
            prefs.remove(POSITION)
        }
    }

    data class Saved(val items: List<MediaItem>, val position: Int, val savedAtMs: Long) {
        val remaining: Int get() = items.size - position
    }

    private fun MediaItem.toStored() = StoredItem(
        id, kind.name, indexInPost, countInPost, displayUrl, posterUrl, width, height,
        durationMs, author.id, author.username, author.name, text, createdAtMs, possiblySensitive,
    )

    private fun StoredItem.toDomain() = MediaItem(
        id = id,
        kind = com.xtv.app.core.model.MediaKind.valueOf(kind),
        indexInPost = indexInPost,
        countInPost = countInPost,
        displayUrl = displayUrl,
        posterUrl = posterUrl,
        width = width,
        height = height,
        durationMs = durationMs,
        author = com.xtv.app.core.model.Author(authorId, username, name),
        text = text,
        createdAtMs = createdAtMs,
        possiblySensitive = sensitive,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val REEL = stringPreferencesKey("reel")
        val POSITION = intPreferencesKey("position")
        val SAVED_AT = longPreferencesKey("saved_at")
        val SINCE_ID = stringPreferencesKey("since_id")
    }
}
