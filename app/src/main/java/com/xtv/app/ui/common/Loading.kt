package com.xtv.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.xtv.app.R
import com.xtv.app.ui.theme.XtvPalette
import com.xtv.app.ui.theme.XtvSpacing
import kotlinx.coroutines.delay

/**
 * Cold start.
 *
 * This screen used to render nothing at all — the routing branch for the loading state was literally
 * `Unit` — so launching XTV showed the window background and no other evidence that anything had
 * happened until the encrypted envelope had been read and decrypted. On a TV, a black screen after
 * pressing an app is indistinguishable from an app that failed to open.
 *
 * The spinner waits before appearing. Reading local state is usually quick enough that a spinner
 * would only flash, and a flash of progress is its own kind of noise.
 */
@Composable
fun LoadingScreen() {
    var showSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_DELAY_MS)
        showSpinner = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(XtvPalette.Background)
            .padding(
                PaddingValues(
                    horizontal = XtvSpacing.ScreenH,
                    vertical = XtvSpacing.ScreenV,
                ),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = XtvPalette.Accent,
            )
            if (showSpinner) {
                Spacer(Modifier.height(XtvSpacing.Section))
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = XtvPalette.Border,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

private const val SPINNER_DELAY_MS = 400L
