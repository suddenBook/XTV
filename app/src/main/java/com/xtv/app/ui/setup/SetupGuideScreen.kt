package com.xtv.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xtv.app.R
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private val Accent = Color(0xFF1D9BF0)

/**
 * Shown until a client id has been injected.
 *
 * The published APK carries no credentials at all, because X bills API usage to the **owner of the
 * developer app** and OAuth does not move that bill to whoever signs in — a shared build would spend
 * its author's credits for every user. So every install starts here, and the steps below are the
 * whole setup.
 *
 * Overscan-safe padding (48dp / 27dp) per the Android TV layout guidance: TV panels crop the edges.
 */
@Composable
fun SetupGuideScreen() {
    // Outer box owns the background and the overscan-safe inset; the inner column owns the reading
    // measure. The cap has to live on a child — `fillMaxSize()` fixes the width, so a `widthIn` applied
    // after it on the same modifier chain is a no-op.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E0E))
            .padding(PaddingValues(horizontal = 48.dp, vertical = 27.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
    Column(
        // The panel is 960dp wide; prose across the full width is ~120 characters per line, which is
        // unreadable from a couch.
        Modifier.widthIn(max = 640.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("X TV", style = MaterialTheme.typography.headlineMedium, color = Accent)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.setup_body),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(24.dp))
        Steps.forEachIndexed { index, step ->
            Text(
                "${index + 1}.  $step",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.setup_readme), style = MaterialTheme.typography.bodyMedium, color = Accent)
    }
    }
}

/** Command shapes, not prose — these stay untranslated on purpose. */
private val Steps = listOf(
    "console.x.com → Native App → Read → callback http://localhost:8080/callback",
    "npm i -g @xdevplatform/xurl  &&  xurl auth oauth2 --headless",
    "adb shell am start -n com.xtv.app/.MainActivity \\",
    "    --es client_id <id> --es refresh_token <token>",
)
