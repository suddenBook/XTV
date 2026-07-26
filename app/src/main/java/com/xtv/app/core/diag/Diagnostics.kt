package com.xtv.app.core.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

private const val TAG = "XTV-DIAG"

/**
 * An in-memory ring of what the app recently did, plus a way to get a raw response off the device.
 *
 * A TV has no devtools. When a reel comes back empty the cause could be rate limiting, an exhausted
 * prepaid balance, a dead session, or an upstream shape change — and all four look identical from
 * the couch. Logcat only helps if someone is already attached over adb at the moment it happens,
 * which is never when it actually happens.
 *
 * Records are kept in memory only. They quote endpoint paths and counts, never tokens and never
 * media URLs.
 */
object Diagnostics {

    private const val CAPACITY = 40
    private val entries = ConcurrentLinkedDeque<Entry>()

    data class Entry(
        val atMs: Long,
        val label: String,
        val detail: String,
    ) {
        val time: String
            get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(atMs))
    }

    fun record(label: String, detail: String) {
        entries.addFirst(Entry(System.currentTimeMillis(), label, detail))
        while (entries.size > CAPACITY) entries.pollLast()
        Log.i(TAG, "$label — $detail")
    }

    fun snapshot(): List<Entry> = entries.toList()

    /**
     * Writes the last raw response body to app-private external storage for `adb pull`.
     *
     * App-private (`getExternalFilesDir`) rather than shared storage: this is somebody's timeline,
     * and a debug convenience should not leave account media sitting in the gallery. Overwrites
     * rather than accumulating, for the same reason.
     */
    fun dumpBody(context: Context, body: String): String? = try {
        val file = File(context.getExternalFilesDir(null), "last-response.json")
        file.writeText(body)
        record("dump", "wrote ${body.length}B to ${file.absolutePath}")
        file.absolutePath
    } catch (t: Throwable) {
        Log.w(TAG, "dump failed: ${t.message}")
        null
    }
}
