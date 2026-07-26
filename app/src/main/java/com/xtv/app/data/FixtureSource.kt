package com.xtv.app.data

import android.content.Context
import android.util.Log
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.PageResult
import kotlinx.serialization.json.Json

/**
 * Loads a captured API response from `assets/fixtures/` so the player can be exercised without
 * spending money or holding a token.
 *
 * The asset only exists in debug builds (`app/src/debug/assets/`, git-ignored because it is an
 * unsanitised capture of a real timeline). A release build, or a debug build on a fresh clone,
 * simply finds nothing and gets an empty list.
 */
object FixtureSource {
    private const val TAG = "XTV-FIXTURE"

    fun load(context: Context, name: String = "following_raw.json"): List<MediaItem> = try {
        val text = context.assets.open("fixtures/$name").bufferedReader().use { it.readText() }
        when (val result = Normalizer.parse(Json.parseToJsonElement(text))) {
            is PageResult.Ok -> {
                Log.i(TAG, "loaded $name: ${result.items.size} items from ${result.stats.postsSeen} posts")
                result.items
            }
            else -> {
                Log.w(TAG, "fixture $name did not parse as a page: $result")
                emptyList()
            }
        }
    } catch (t: Throwable) {
        // Missing asset is the normal case outside a configured debug build — not an error.
        Log.i(TAG, "no fixture $name available (${t.javaClass.simpleName})")
        emptyList()
    }
}
