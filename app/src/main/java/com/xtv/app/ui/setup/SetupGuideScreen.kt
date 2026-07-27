package com.xtv.app.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.xtv.app.MissingCredential
import com.xtv.app.R
import com.xtv.app.ui.common.XtvDialog
import com.xtv.app.ui.common.destructiveButtonColors
import com.xtv.app.ui.common.rememberInitialFocus
import com.xtv.app.ui.theme.XtvPalette
import com.xtv.app.ui.theme.XtvSpacing
import com.xtv.app.ui.theme.XtvText

/** What the screen actually has to say. Five internal states, two things a person can do. */
internal enum class SetupPresentation { NEEDS_SETUP, BROKEN }

/**
 * Five credential states collapse to two presentations.
 *
 * Everything short of an unreadable envelope is fixed by the same action — run the setup script
 * again from a computer, with all three values — so distinguishing them on screen would only be
 * narrating the state machine. The screen used to carry a separate paragraph for each, one of which
 * explained refresh-token rotation and candidate replacement to somebody holding a remote.
 *
 * The precise state is not thrown away; it moves to the diagnostics screen, where a reader who
 * wants it is already looking for that kind of thing.
 */
internal fun MissingCredential.setupPresentation(): SetupPresentation = when (this) {
    MissingCredential.PRIVATE_STATE -> SetupPresentation.BROKEN
    MissingCredential.CLIENT_ID,
    MissingCredential.BEARER,
    MissingCredential.SESSION,
    MissingCredential.PROVISIONING,
    -> SetupPresentation.NEEDS_SETUP
}

/**
 * The exact state, as one quiet line.
 *
 * Which of the three values failed to take does not change what the operator does next, so it is
 * not the headline — but they have just run a script and it is the first thing they will want to
 * know. This is the entire remainder of the five paragraphs it replaces.
 */
internal fun MissingCredential.detailRes(): Int = when (this) {
    MissingCredential.CLIENT_ID -> R.string.credential_state_client_id
    MissingCredential.BEARER -> R.string.credential_state_bearer
    MissingCredential.SESSION -> R.string.credential_state_session
    MissingCredential.PROVISIONING -> R.string.credential_state_provisioning
    MissingCredential.PRIVATE_STATE -> R.string.credential_state_private_state
}

/**
 * Overscan-safe setup, and the recovery path for a fail-closed envelope.
 *
 * Published builds contain no X credentials; an operator provisions all three values through the
 * DUMP-protected activity over USB or TLS ADB. Nothing on this screen can perform that — so it does
 * not pretend to. It used to print the four console steps and a two-line shell command, on a
 * television, where the command cannot be copied and the only thing it achieved was telling you
 * that a script exists. A QR hands the instructions to the device that can actually follow them.
 *
 * The wording has to stay true for an install that already holds a client id and a live session but
 * no bearer token, which is why it says there are no *usable* credentials rather than that setup has
 * never been done.
 */
@Composable
fun SetupGuideScreen(
    missing: MissingCredential = MissingCredential.CLIENT_ID,
    resetError: String? = null,
    onResetEverything: (() -> Unit)? = null,
) {
    var confirmReset by remember { mutableStateOf(false) }
    val presentation = missing.setupPresentation()

    // An unreadable envelope is the only state where clearing is the headline. Interrupted
    // provisioning keeps the escape hatch — it is how you move to a different developer project —
    // but quietly, below the instructions, because re-running the script is the usual answer.
    val canClear = onResetEverything != null &&
        (presentation == SetupPresentation.BROKEN || missing == MissingCredential.PROVISIONING)
    val clearFocus = rememberInitialFocus(missing, enabled = canClear && !confirmReset)

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                // A capped reading measure stays legible from a couch.
                Modifier.widthIn(max = 620.dp),
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = XtvPalette.Accent,
                )
                Spacer(Modifier.height(XtvSpacing.Section))
                Text(
                    stringResource(
                        when (presentation) {
                            SetupPresentation.NEEDS_SETUP -> R.string.setup_needed_title
                            SetupPresentation.BROKEN -> R.string.setup_broken_title
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = XtvText.Primary,
                )
                Spacer(Modifier.height(XtvSpacing.Heading))
                Text(
                    stringResource(
                        when (presentation) {
                            SetupPresentation.NEEDS_SETUP -> R.string.setup_needed_body
                            SetupPresentation.BROKEN -> R.string.setup_broken_body
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = XtvText.Secondary,
                )
                if (missing == MissingCredential.PROVISIONING) {
                    Spacer(Modifier.height(XtvSpacing.Heading))
                    Text(
                        stringResource(R.string.setup_resume_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = XtvText.Secondary,
                    )
                }
                if (presentation == SetupPresentation.NEEDS_SETUP) {
                    Spacer(Modifier.height(XtvSpacing.Row))
                    Text(
                        stringResource(missing.detailRes()),
                        style = MaterialTheme.typography.labelLarge,
                        color = XtvText.Tertiary,
                    )
                }
                resetError?.let {
                    Spacer(Modifier.height(XtvSpacing.Heading))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = XtvPalette.Warning,
                    )
                }
                if (canClear) {
                    Spacer(Modifier.height(XtvSpacing.Section))
                    Button(
                        onClick = { confirmReset = true },
                        colors = destructiveButtonColors(),
                        modifier = Modifier.focusRequester(clearFocus),
                    ) {
                        Text(stringResource(R.string.setup_clear))
                    }
                }
            }

            if (presentation == SetupPresentation.NEEDS_SETUP) {
                Spacer(Modifier.width(XtvSpacing.Section))
                QrPanel()
            }
        }
    }

    if (confirmReset && onResetEverything != null) {
        XtvDialog(
            title = stringResource(R.string.settings_reset_everything_title),
            body = stringResource(R.string.settings_reset_everything_body),
            dismissLabel = stringResource(R.string.exit_cancel),
            onDismiss = { confirmReset = false },
            confirmLabel = stringResource(R.string.settings_reset_confirm),
            onConfirm = {
                confirmReset = false
                onResetEverything()
            },
            destructive = true,
        )
    }
}

@Composable
private fun QrPanel() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.setup_qr),
            // The URL underneath is the label; announcing "QR code" adds nothing a reader can use.
            contentDescription = null,
            modifier = Modifier.size(200.dp),
        )
        Spacer(Modifier.height(XtvSpacing.Heading))
        Text(
            stringResource(R.string.setup_scan),
            style = MaterialTheme.typography.bodyMedium,
            color = XtvText.Secondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.repo_url),
            style = MaterialTheme.typography.labelLarge,
            color = XtvPalette.Accent,
        )
    }
}
