package com.xtv.app.ui.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.xtv.app.R
import com.xtv.app.core.diag.Diagnostics
import com.xtv.app.core.purchase.RateCard
import com.xtv.app.ui.common.rememberInitialFocus
import com.xtv.app.ui.theme.XtvPalette
import com.xtv.app.ui.theme.XtvSpacing
import com.xtv.app.ui.theme.XtvText
import kotlinx.coroutines.launch

/**
 * What the app recently did, for the one person who wants to know.
 *
 * Split out of what used to be a single "Settings & diagnostics" screen, where a forty-line
 * monospace log occupied most of the space above two irreversible buttons. They answer different
 * questions — "change something" and "why did that fail" — and only one of them is worth a
 * permanent entry on the home screen. This one appears only once there is something to show.
 *
 * The dated rate card is here because it is the only build fact that explains a price: if the
 * console changes what a Post read costs, every offer this version quoted was computed from the
 * version named on this line.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val entries = remember { Diagnostics.snapshot() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val listFocus = rememberInitialFocus(enabled = entries.isNotEmpty())

    Column(
        Modifier
            .fillMaxSize()
            .background(XtvPalette.Background)
            .padding(
                PaddingValues(
                    horizontal = XtvSpacing.ScreenH,
                    vertical = XtvSpacing.ScreenV,
                ),
            ),
    ) {
        Text(
            stringResource(R.string.diagnostics_title),
            style = MaterialTheme.typography.headlineMedium,
            color = XtvPalette.Accent,
        )
        Spacer(Modifier.height(XtvSpacing.Heading))
        Text(
            stringResource(R.string.diagnostics_rate_card, RateCard.current().version),
            style = MaterialTheme.typography.labelLarge,
            color = XtvText.Tertiary,
        )

        Spacer(Modifier.height(XtvSpacing.Section))
        Text(
            stringResource(R.string.diagnostics_log_label),
            style = MaterialTheme.typography.bodyMedium,
            color = XtvText.Secondary,
        )
        Spacer(Modifier.height(XtvSpacing.Heading))

        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.diagnostics_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = XtvText.Tertiary,
            )
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .focusRequester(listFocus)
                // One row per press. The version this replaces jumped four at a time, which on a
                // list whose whole purpose is reading consecutive lines loses your place.
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val target = when (event.key) {
                        Key.DirectionDown ->
                            if (listState.canScrollForward) {
                                listState.firstVisibleItemIndex + 1
                            } else {
                                return@onKeyEvent false
                            }
                        Key.DirectionUp ->
                            if (listState.firstVisibleItemIndex > 0) {
                                listState.firstVisibleItemIndex - 1
                            } else {
                                // Let focus leave upward rather than trapping the remote here.
                                return@onKeyEvent false
                            }
                        else -> return@onKeyEvent false
                    }
                    scope.launch {
                        listState.animateScrollToItem(target.coerceIn(0, entries.lastIndex))
                    }
                    true
                }
                .focusable(),
        ) {
            items(entries) { entry ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    LogCell(entry.time, XtvText.Tertiary, Modifier.width(104.dp))
                    LogCell(entry.label, XtvPalette.Accent, Modifier.width(168.dp))
                    LogCell(entry.detail, XtvText.Secondary, Modifier)
                }
            }
        }
    }
}

@Composable
private fun LogCell(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        color = color,
        modifier = modifier,
    )
}
