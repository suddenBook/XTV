package com.xtv.app.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.xtv.app.ui.theme.XtvPalette
import com.xtv.app.ui.theme.XtvSpacing
import com.xtv.app.ui.theme.XtvText

/**
 * The app's one modal.
 *
 * Four near-identical copies of this used to live in three files, with widths hard-coded to 440,
 * 560, 600 and 600 dp and each one re-implementing its own scrim, its own focus retry and its own
 * `Box` + `background` panel. One of them used a `Card(onClick = {})` as the panel, which is
 * focusable — so an empty backing plate competed with the buttons for the remote.
 *
 * The panel is a real [Surface]: shape and colour come from the theme, and a hairline border keeps
 * it separated from the scrim on a display with poor black levels.
 *
 * Focus always starts on the dismissing action. Every dialog in XTV guards something the viewer
 * cannot undo, so the safe choice is the one already under the cursor.
 */
@Composable
internal fun XtvDialog(
    title: String,
    body: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    destructive: Boolean = false,
) {
    val dismissFocus = rememberInitialFocus(title)
    BackHandler { onDismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .background(XtvPalette.Scrim.copy(alpha = 0.82f))
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = XtvSpacing.DialogMaxWidth),
            shape = MaterialTheme.shapes.large,
            colors = SurfaceDefaults.colors(
                containerColor = XtvPalette.Surface,
                contentColor = XtvText.Primary,
            ),
            border = Border(
                border = BorderStroke(1.dp, XtvPalette.Border),
                shape = MaterialTheme.shapes.large,
            ),
        ) {
            Column(Modifier.padding(XtvSpacing.DialogPad)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = XtvText.Primary,
                )
                Spacer(Modifier.height(XtvSpacing.Heading))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = XtvText.Secondary,
                )
                Spacer(Modifier.height(XtvSpacing.Section))
                Row(horizontalArrangement = Arrangement.spacedBy(XtvSpacing.Gap)) {
                    if (confirmLabel != null && onConfirm != null) {
                        Button(
                            onClick = onConfirm,
                            colors = if (destructive) {
                                destructiveButtonColors()
                            } else {
                                ButtonDefaults.colors()
                            },
                        ) {
                            Text(confirmLabel)
                        }
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(dismissFocus),
                    ) {
                        Text(dismissLabel)
                    }
                }
            }
        }
    }
}

/**
 * Reserved for actions with no undo. This is the only place red appears in XTV — recoverable
 * failures are explained in words, so that when the colour does show up it still means something.
 */
@Composable
internal fun destructiveButtonColors() = ButtonDefaults.colors(
    containerColor = XtvPalette.Danger.copy(alpha = 0.16f),
    contentColor = XtvPalette.Danger,
    focusedContainerColor = XtvPalette.Danger,
    focusedContentColor = XtvPalette.OnDanger,
)
