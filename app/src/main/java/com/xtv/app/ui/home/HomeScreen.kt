package com.xtv.app.ui.home

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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.xtv.app.R
import com.xtv.app.core.purchase.OfferId
import com.xtv.app.core.purchase.OfferToken
import com.xtv.app.core.purchase.PurchaseOperation
import com.xtv.app.core.purchase.PurchaseSnapshot
import com.xtv.app.core.purchase.ReelOffer
import com.xtv.app.core.purchase.ReelStatus
import com.xtv.app.ui.common.XtvDialog
import com.xtv.app.ui.common.rememberInitialFocus
import com.xtv.app.ui.notice.InlineNotice
import com.xtv.app.ui.notice.LoudNotice
import com.xtv.app.ui.notice.Notice
import com.xtv.app.ui.notice.NoticeWeight
import com.xtv.app.ui.theme.XtvPalette
import com.xtv.app.ui.theme.XtvSpacing
import com.xtv.app.ui.theme.XtvText

/**
 * The console.
 *
 * Deliberately without imagery. What this app plays is a private Following timeline, and the home
 * screen is the one that sits lit in a living room while nobody is using it; putting the content
 * there would make someone's timeline into ambient wallpaper. `FLAG_SECURE` stops a screenshot, not
 * a person walking in.
 *
 * Two rules shape the layout:
 *
 * **Nothing on this screen is dead.** An entry that means nothing in the current state is not
 * drawn — not greyed out. The screen used to disagree with itself about this: the resume card
 * vanished without a reel, the grid card stayed and went to 35% alpha, and the offer row was
 * replaced wholesale by a sentence. First launch showed a card labelled "See the whole reel" that
 * could never be pressed.
 *
 * **The row grid is fixed at three columns even when there are fewer than three things.** Absent
 * entries leave their column empty rather than letting the survivors stretch, so a card is the same
 * size in every state and the two rows stay aligned with each other. The previous 680 / 240+300 /
 * 240×3 widths could not line up at any screen size.
 */
@Composable
fun HomeScreen(
    state: PurchaseSnapshot,
    notice: Notice?,
    diagnosticsAvailable: Boolean,
    onDismissNotice: () -> Unit,
    onBuild: (OfferToken) -> Unit,
    onContinue: () -> Unit,
    onReplay: () -> Unit,
    onOpenGrid: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onExitApp: () -> Unit,
) {
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler(enabled = !confirmExit) { confirmExit = true }

    val reel = state.currentReel
    val busy = state.operation is PurchaseOperation.Running
    val loud = notice?.takeIf { it.weight == NoticeWeight.LOUD }

    // Only a dialog takes the screen out of play. A loud notice must NOT — it lives inside this
    // column, so blocking focus here would also block its own dismiss button and leave the viewer
    // with no way out but to quit the app.
    val interactive = !confirmExit

    // Never the offer cards. A remote sat on at the wrong moment should not be able to spend money,
    // and on first launch — no reel, so no resume card — the old first stop was the cheapest offer.
    // While a loud notice is up, nothing here claims focus: it belongs to the notice.
    val autoFocus = interactive && loud == null
    val primaryFocus = rememberInitialFocus(reel?.id, enabled = autoFocus && reel != null)
    val secondaryFocus = rememberInitialFocus(reel?.id, enabled = autoFocus && reel == null)

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
                // `canFocus` is only ever used to switch this subtree OFF. Setting it to `true` on
                // a focus group makes the group itself a focus target, so a DOWN press out of the
                // row above landed on a container that draws nothing — focus visibly disappeared,
                // and UP could not find its way back. The previous version hid this behind explicit
                // up/down/left/right wiring on every single card.
                .then(
                    if (interactive) Modifier else Modifier.focusProperties { canFocus = false },
                ),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = XtvPalette.Accent,
            )
            Spacer(Modifier.height(XtvSpacing.Section))

            if (reel != null) {
                val completed = reel.status == ReelStatus.COMPLETED
                PrimaryCard(
                    title = stringResource(
                        if (completed) R.string.home_replay else R.string.home_continue,
                    ),
                    // No "N of M left" while a batch is in progress. It counted the wrong thing:
                    // the index is a cursor, not a high-water mark — stepping back moves it back —
                    // so a subtraction reads as unwatched items that are not unwatched. The grid
                    // marks each item individually, which is the truthful version of the question.
                    detail = if (completed) {
                        pluralStringResource(R.plurals.home_replay_sub, reel.total, reel.total)
                    } else {
                        null
                    },
                    onClick = if (completed) onReplay else onContinue,
                    enabled = !busy,
                    modifier = Modifier.focusRequester(primaryFocus),
                )
                Spacer(Modifier.height(XtvSpacing.Row))
            }

            ThreeColumnRow(
                buildList {
                    // Grid is the only route to the content, so it exists exactly when content does.
                    reel?.let { current ->
                        add {
                            CompactCard(
                                label = pluralStringResource(
                                    R.plurals.home_all,
                                    current.total,
                                    current.total,
                                ),
                                onClick = onOpenGrid,
                                enabled = !busy,
                            )
                        }
                    }
                    add {
                        CompactCard(
                            label = stringResource(R.string.home_settings),
                            onClick = onOpenSettings,
                            enabled = !busy,
                            modifier = if (reel == null) {
                                Modifier.focusRequester(secondaryFocus)
                            } else {
                                Modifier
                            },
                        )
                    }
                    // Nothing has gone wrong yet, so there is nothing to diagnose.
                    if (diagnosticsAvailable) {
                        add {
                            CompactCard(
                                label = stringResource(R.string.home_diagnostics),
                                onClick = onOpenDiagnostics,
                                enabled = !busy,
                            )
                        }
                    }
                },
            )

            Spacer(Modifier.height(XtvSpacing.Section))

            if (loud != null) {
                // Takes the place of the offers rather than stacking above them: the message is
                // about a purchase that produced nothing, and re-offering the same thing under it
                // is both crowded and tone-deaf. Dismissing brings the offers back.
                LoudNotice(notice = loud, onDismiss = onDismissNotice)
            } else {
                Text(
                    stringResource(R.string.home_get_new),
                    style = MaterialTheme.typography.titleMedium,
                    color = XtvText.Secondary,
                )
                Spacer(Modifier.height(XtvSpacing.Heading))
                Offers(state = state, busy = busy, onBuild = { onBuild(it.token) })
            }
        }

        if (loud == null) {
            notice?.let {
                InlineNotice(
                    notice = it,
                    onExpire = onDismissNotice,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = XtvSpacing.ScreenH,
                            bottom = XtvSpacing.ScreenV,
                            end = XtvSpacing.ScreenH,
                        ),
                )
            }
        }
    }

    if (confirmExit) {
        XtvDialog(
            title = stringResource(R.string.exit_title),
            body = stringResource(R.string.exit_body),
            dismissLabel = stringResource(R.string.exit_cancel),
            onDismiss = { confirmExit = false },
            confirmLabel = stringResource(R.string.exit_confirm),
            onConfirm = onExitApp,
        )
    }
}

@Composable
private fun Offers(
    state: PurchaseSnapshot,
    busy: Boolean,
    onBuild: (ReelOffer) -> Unit,
) {
    // Pressing an offer used to change almost nothing on screen: the card stayed put and a
    // 0.7-alpha "Building…" appeared in the far corner, which from three metres is no feedback at
    // all. The row the viewer just pressed is now the thing that reports back.
    //
    // An empty offer list means the same thing as `busy` now that the set is fixed at three: a
    // request is in flight, or its result is still being acknowledged. Drawing nothing for that
    // second window would leave a hole under the heading after every purchase.
    if (busy || state.offers.isEmpty()) {
        WorkingRow()
        return
    }
    ThreeColumnRow(
        state.offers.map { offer ->
            { OfferCard(offer = offer, sizeName = offer.id.labelRes(), onClick = { onBuild(offer) }) }
        },
    )
}

@Composable
private fun WorkingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = XtvPalette.Accent,
            strokeWidth = 3.dp,
        )
        Text(
            stringResource(R.string.notice_working),
            style = MaterialTheme.typography.titleMedium,
            color = XtvText.Primary,
            modifier = Modifier.padding(start = XtvSpacing.Gap),
        )
    }
}

private fun OfferId.labelRes(): Int = when (this) {
    OfferId.SHORT -> R.string.size_short
    OfferId.STANDARD -> R.string.size_standard
    OfferId.LONG -> R.string.size_long
}

/**
 * A row of exactly three columns, however many things are in it.
 *
 * Holding the column count fixed is what keeps a card the same size whether the screen is showing
 * three offers or one, and keeps the secondary row aligned with the offer row underneath it. Absent
 * entries leave an empty column instead of letting the survivors stretch.
 */
@Composable
private fun ThreeColumnRow(cells: List<@Composable () -> Unit>) {
    Row(
        // No `focusRestorer` here. It exists to re-enter a lazy list where you left it, and on a
        // fixed row of three it swallowed the entry instead: pressing DOWN out of the row above
        // moved focus into the group, found nothing to restore, and left it parked on the container
        // — which draws nothing, so focus visibly vanished and UP could not bring it back. Plain
        // two-dimensional focus search handles a static row correctly on its own.
        Modifier.fillMaxWidth().focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(XtvSpacing.Gap),
    ) {
        cells.take(COLUMNS).forEach { cell ->
            Box(Modifier.weight(1f)) { cell() }
        }
        repeat(COLUMNS - cells.size.coerceIn(0, COLUMNS)) {
            Spacer(Modifier.weight(1f))
        }
    }
}

private const val COLUMNS = 3

@Composable
private fun PrimaryCard(
    title: String,
    detail: String?,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            // Same height as an offer card: both are "press this to start watching".
            .height(XtvSpacing.CardHeight)
            .focusProperties { canFocus = enabled },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = XtvSpacing.CardPadH, vertical = XtvSpacing.CardPadV),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = XtvText.Primary)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = XtvText.Secondary)
            }
        }
    }
}

/**
 * Secondary entries. Same grid as the offers, deliberately shallower and quieter, because a row of
 * three cards directly above another row of three cards otherwise reads as equal in importance.
 */
@Composable
private fun CompactCard(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { canFocus = enabled },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = XtvText.Secondary,
            modifier = Modifier.padding(
                horizontal = XtvSpacing.CompactPadH,
                vertical = XtvSpacing.CompactPadV,
            ),
        )
    }
}

/**
 * One offer.
 *
 * The price is a range. It used to be two separate figures — an expected charge and a conservative
 * reservation — printed side by side, which asks the viewer to arbitrate between two numbers the
 * app itself could not choose between. The range says the same thing without the arithmetic: the
 * bill lands somewhere in here and never above it.
 */
@Composable
private fun OfferCard(
    offer: ReelOffer,
    sizeName: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        // No `focusProperties` here. An offer is always selectable now, and setting `canFocus` to
        // `true` on a card makes it a focus target in its own right — the same mistake documented
        // on the containers above.
        modifier = modifier
            .fillMaxWidth()
            .height(XtvSpacing.CardHeight),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = XtvSpacing.CardPadH,
                    vertical = XtvSpacing.CardPadV,
                ),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(sizeName),
                style = MaterialTheme.typography.titleLarge,
                color = XtvText.Primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                pluralStringResource(
                    R.plurals.offer_videos,
                    offer.estimatedVideos,
                    offer.estimatedVideos,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = XtvText.Secondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.offer_price_range,
                    offer.charge.knownEstimate.formatUsd(),
                    offer.charge.reservation.formatUsd(),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = XtvPalette.Accent,
            )
        }
    }
}
