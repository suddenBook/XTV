package com.xtv.app.core.diag

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

private const val TAG = "XTV-DIAG"

/**
 * An in-memory ring of what the app recently did.
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
}
