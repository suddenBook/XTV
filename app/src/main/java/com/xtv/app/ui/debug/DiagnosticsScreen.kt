package com.xtv.app.ui.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.xtv.app.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.xtv.app.core.diag.Diagnostics

private val Accent = Color(0xFF1D9BF0)

/**
 * The debugger, because a TV has none.
 *
 * Shows the recent request/parse history so "the reel is empty" can be attributed rather than
 * guessed at. The four causes that look identical from the couch — rate limited, credits exhausted,
 * session dead, upstream shape changed — are each distinguishable here.
 *
 * Reachable from the home card. Deliberately plain text, monospaced, and readable from a sofa.
 */
@Composable
fun DiagnosticsScreen(header: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    val entries = Diagnostics.snapshot()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E0E))
            .padding(PaddingValues(horizontal = 48.dp, vertical = 27.dp)),
    ) {
        Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge, color = Accent)
        Spacer(Modifier.height(6.dp))
        Text(header, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(16.dp))

        if (entries.isEmpty()) {
            Text(stringResource(R.string.history_empty), color = Color.White.copy(alpha = 0.5f))
            return@Column
        }

        LazyColumn {
            items(entries) { entry ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(
                        entry.time,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.width(100.dp),
                    )
                    Text(
                        entry.label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        color = Accent,
                        modifier = Modifier.width(160.dp),
                    )
                    Text(
                        entry.detail,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}
