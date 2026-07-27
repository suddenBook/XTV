package com.xtv.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.xtv.app.R
import com.xtv.app.core.purchase.PurchaseOperation
import com.xtv.app.core.purchase.PurchaseSnapshot
import com.xtv.app.core.purchase.RateCard
import com.xtv.app.core.purchase.UsdMicros
import com.xtv.app.ui.common.XtvDialog
import com.xtv.app.ui.common.destructiveButtonColors
import com.xtv.app.ui.common.rememberInitialFocus
import com.xtv.app.ui.theme.XtvPalette
import com.xtv.app.ui.theme.XtvSpacing
import com.xtv.app.ui.theme.XtvText

private enum class Confirm { CREDENTIALS, EVERYTHING }

/**
 * What X's project Post count is worth in dollars.
 *
 * For this request shape only Post reads are billed, so the figure is exactly the count times the
 * Post rate. It was previously quoted at a hundred posts and divided back down, to work around a
 * model that also charged a User read per Post and its attachments as Media reads; a real Console
 * statement showed those are deduplicated away and never billed. Reading it from the rate card
 * rather than hard-coding a constant means a price revision moves this line with it.
 */
internal fun Int.projectEstimate(): UsdMicros =
    if (this <= 0) UsdMicros.ZERO else RateCard.current().postRead * toLong()

/**
 * The one screen where money lives.
 *
 * Three tiles, from X's own meter: how many Posts this developer project has read, what that is
 * worth at the current rate, and the day X's period resets. Nothing here is a limit, because there
 * isn't one — the Developer Console's hard cap is the only thing that can stop a charge, and an app
 * keeping a second private ceiling only ever managed to disagree with the real one.
 *
 * The two erasing actions carry the destructive colour. They used to be three identical buttons in
 * a row, where "Refresh Post usage" and "Erase everything" were visually the same offer.
 */
@Composable
fun SettingsScreen(
    state: PurchaseSnapshot,
    onRefreshUsage: () -> Unit,
    onResetCredentials: () -> Unit,
    onResetEverything: () -> Unit,
    onBack: () -> Unit,
) {
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val idle = state.operation is PurchaseOperation.Idle
    val enabled = idle && confirm == null
    val refreshFocus = rememberInitialFocus(enabled = enabled)

    BackHandler(enabled = confirm == null) { onBack() }

    Box(Modifier.fillMaxSize().background(XtvPalette.Background)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    PaddingValues(
                        horizontal = XtvSpacing.ScreenH,
                        vertical = XtvSpacing.ScreenV,
                    ),
                )
                .focusGroup()
                // Same rule as the home screen: `canFocus` switches the subtree off, never on.
                // `true` on a focus group makes the group itself focusable and swallows the entry.
                .then(
                    if (confirm == null) {
                        Modifier
                    } else {
                        Modifier.focusProperties { canFocus = false }
                    },
                ),
        ) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = XtvPalette.Accent,
            )
            Spacer(Modifier.height(XtvSpacing.Section))

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(XtvSpacing.Gap),
            ) {
                Text(
                    stringResource(R.string.settings_usage_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = XtvText.Secondary,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onRefreshUsage,
                    enabled = enabled,
                    modifier = Modifier.focusRequester(refreshFocus),
                ) {
                    Text(stringResource(R.string.settings_usage_refresh))
                }
            }
            Spacer(Modifier.height(XtvSpacing.Heading))

            // Three facts, three tiles. They were three lines of small grey text stacked directly
            // on top of each other, which is how you make a reader stop distinguishing between
            // them; each is a different kind of quantity and none of them qualifies the others.
            val usage = state.projectUsage
            Row(horizontalArrangement = Arrangement.spacedBy(XtvSpacing.Gap)) {
                StatTile(
                    label = stringResource(R.string.settings_tile_posts),
                    value = usage?.let {
                        stringResource(R.string.settings_tile_posts_value, it.posts)
                    },
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.settings_tile_cost),
                    value = usage?.posts?.projectEstimate()?.formatUsd(),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.settings_tile_reset),
                    // Fetched and stored since the beginning, and shown here for the first time —
                    // while the old setup screen claimed the bearer token was needed to display it.
                    value = usage?.resetDay?.let {
                        stringResource(R.string.settings_tile_reset_value, it)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(XtvSpacing.Heading))
            Text(
                stringResource(R.string.settings_usage_note),
                style = MaterialTheme.typography.labelLarge,
                color = XtvText.Tertiary,
                modifier = Modifier.widthIn(max = 720.dp),
            )

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(XtvSpacing.Gap)) {
                Button(
                    onClick = { confirm = Confirm.CREDENTIALS },
                    enabled = enabled,
                    colors = destructiveButtonColors(),
                ) {
                    Text(stringResource(R.string.settings_reset_credentials))
                }
                Button(
                    onClick = { confirm = Confirm.EVERYTHING },
                    enabled = enabled,
                    colors = destructiveButtonColors(),
                ) {
                    Text(stringResource(R.string.settings_reset_everything))
                }
            }
        }
    }

    confirm?.let { level ->
        val everything = level == Confirm.EVERYTHING
        XtvDialog(
            title = stringResource(
                if (everything) {
                    R.string.settings_reset_everything_title
                } else {
                    R.string.settings_reset_credentials_title
                },
            ),
            body = stringResource(
                if (everything) {
                    R.string.settings_reset_everything_body
                } else {
                    R.string.settings_reset_credentials_body
                },
            ),
            dismissLabel = stringResource(R.string.exit_cancel),
            onDismiss = { confirm = null },
            confirmLabel = stringResource(R.string.settings_reset_confirm),
            onConfirm = {
                confirm = null
                if (everything) onResetEverything() else onResetCredentials()
            },
            destructive = true,
        )
    }
}

/**
 * One number and what it is, on its own plane.
 *
 * A dash rather than a zero when the value is missing: nothing has been fetched yet, which is not
 * the same claim as "none".
 */
@Composable
private fun StatTile(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = SurfaceDefaults.colors(
            containerColor = XtvPalette.SurfaceVariant,
            contentColor = XtvText.Primary,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(XtvSpacing.CardPadH)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = XtvText.Secondary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                value ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                color = if (value == null) XtvText.Tertiary else XtvText.Primary,
            )
        }
    }
}

