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
import com.xtv.app.MissingCredential
import com.xtv.app.R
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private val Accent = Color(0xFF1D9BF0)

/**
 * Shown until all three credentials are present.
 *
 * The published APK carries no credentials at all, because X bills API usage to the **owner of the
 * developer app** and OAuth does not move that bill to whoever signs in — a shared build would spend
 * its author's credits for every user. So every install starts here, and the steps below are the
 * whole setup.
 *
 * [missing] is not cosmetic. An install provisioned before the bearer became mandatory still holds a
 * client id and a live session, and telling that user "this install has no credentials yet" would be
 * both false and unactionable.
 *
 * Overscan-safe padding (48dp / 27dp) per the Android TV layout guidance: TV panels crop the edges.
 */
@Composable
fun SetupGuideScreen(missing: MissingCredential = MissingCredential.CLIENT_ID) {
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
            stringResource(
                when (missing) {
                    MissingCredential.CLIENT_ID -> R.string.setup_body
                    MissingCredential.BEARER -> R.string.setup_body_bearer
                }
            ),
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

/**
 * Command shapes, not prose — these stay untranslated on purpose.
 *
 * One entry per step, continuation lines inside the entry. Splitting the adb command across two
 * entries numbered both halves of it, which read as two separate things to run.
 */
private val Steps = listOf(
    "console.x.com → Native App → Read → callback http://localhost:8080/callback",
    "console.x.com → Keys and tokens → copy the Client ID and the Bearer Token",
    "npm i -g @xdevplatform/xurl  &&  xurl auth oauth2 --headless",
    "adb shell am start -n com.xtv.app/.MainActivity \\\n" +
        "        --es client_id <id> --es refresh_token <token> \\\n" +
        "        --es bearer <app-only bearer token>",
)
