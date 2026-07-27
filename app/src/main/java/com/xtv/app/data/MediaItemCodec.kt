package com.xtv.app.data

import com.xtv.app.core.model.Author
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.MediaKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stable media snapshot codec used only inside the encrypted private-state envelope. */
object MediaItemCodec {
    @Serializable
    private data class StoredItem(
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
        val avatarUrl: String?,
        val text: String,
        val createdAtMs: Long,
        val sensitive: Boolean,
    )

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(items: List<MediaItem>): String =
        json.encodeToString(items.map { it.toStored() })

    fun decode(payload: String): List<MediaItem> =
        json.decodeFromString<List<StoredItem>>(payload).map { it.toDomain() }

    private fun MediaItem.toStored() = StoredItem(
        id = id,
        kind = kind.name,
        indexInPost = indexInPost,
        countInPost = countInPost,
        displayUrl = displayUrl,
        posterUrl = posterUrl,
        width = width,
        height = height,
        durationMs = durationMs,
        authorId = author.id,
        username = author.username,
        name = author.name,
        avatarUrl = author.avatarUrl,
        text = text,
        createdAtMs = createdAtMs,
        sensitive = possiblySensitive,
    )

    private fun StoredItem.toDomain() = MediaItem(
        id = id,
        kind = MediaKind.valueOf(kind),
        indexInPost = indexInPost,
        countInPost = countInPost,
        displayUrl = displayUrl,
        posterUrl = posterUrl,
        width = width,
        height = height,
        durationMs = durationMs,
        author = Author(authorId, username, name, avatarUrl),
        text = text,
        createdAtMs = createdAtMs,
        possiblySensitive = sensitive,
    )
}
