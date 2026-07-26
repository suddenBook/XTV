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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xtv.app.R
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private val Accent = Color(0xFF1D9BF0)

/**
 * How much of the timeline one reel buys.
 *
 * The unit is **posts read**, because that is the unit X bills. Item counts are estimates from the
 * measured video share of this timeline (~60%), and are labelled "about" for exactly that reason.
 */
enum class ReelSize(val labelRes: Int, val posts: Int) {
    SHORT(R.string.size_short, 30),
    STANDARD(R.string.size_standard, 60),
    LONG(R.string.size_long, 100);

    val estimatedItems: Int get() = (posts * 0.6).toInt()
    val maxCostUsd: Double get() = posts * 0.005
}

data class HomeState(
    val resumeRemaining: Int?,
    val resumeTotal: Int?,
    val spentText: String,
    val capText: String,
    val budgetExceeded: Boolean,
    /** True when the spend figure came from X's usage meter rather than the local tally. */
    val spendAuthoritative: Boolean = false,
    val note: String? = null,
)

/**
 * The front door.
 *
 * **Opening the app never spends.** Every fetch is behind a press here — the card states what it
 * will cost before the money leaves. That is why the primary action says "about 36 items" rather
 * than a real count: knowing the real count would already require the purchase.
 *
 * There is no navigation rail. With a single source there is nothing to choose between, and a rail
 * would be ceremonial chrome plus one more place for D-pad focus to get lost.
 *
 * Layout note: the overscan inset lives on an inner container, never on the root. Padding the root
 * makes a full-screen scrim stop short of the panel edges, which is how the exit dialog ended up
 * with an uncovered border.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onBuild: (ReelSize) -> Unit,
    onResume: () -> Unit,
    onOpenGrid: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onExitApp: () -> Unit = {},
) {
    var confirmExit by remember { mutableStateOf(false) }
    var showBudgetWarning by remember(state.budgetExceeded) { mutableStateOf(state.budgetExceeded) }
    // Home is the root, so Back here is the way out of the app — with a confirmation, because a
    // stray Back on a remote should not dump you to the launcher mid-evening.
    BackHandler(enabled = !confirmExit) { confirmExit = true }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(5) { runCatching { firstFocus.requestFocus() }; delay(60) } }

    Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0E))) {
        Box(Modifier.fillMaxSize().padding(PaddingValues(horizontal = 48.dp, vertical = 27.dp))) {
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    // While the dialog is up the content stays visible but leaves the focus graph
                    // entirely. `focusGroup()` alone only groups — it does not stop focus search
                    // from descending into the group, which is how LEFT from the leftmost dialog
                    // button landed on a card underneath. Making the group unfocusable is what
                    // makes the dialog actually modal.
                    .focusGroup()
                    .focusProperties { canFocus = !confirmExit && !showBudgetWarning },
            ) {
                Text("X TV", style = MaterialTheme.typography.headlineMedium, color = Accent)
                Spacer(Modifier.height(24.dp))

                val hasReel = state.resumeRemaining != null && state.resumeRemaining > 0
                if (hasReel) {
                    // Resuming is free, and saying so removes the hesitation about pressing it.
                    PrimaryCard(
                        title = stringResource(R.string.home_resume_title),
                        subtitle = stringResource(R.string.home_resume_subtitle, state.resumeRemaining ?: 0, state.resumeTotal ?: 0),
                        onClick = onResume,
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                    Spacer(Modifier.height(14.dp))
                }

                // Free actions sit with the reel they act on, above the paid options — nothing here
                // costs anything, so it should not be the thing you scroll past money to reach.
                SecondaryRow(onOpenGrid, onOpenDiagnostics, gridEnabled = hasReel)

                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(if (hasReel) R.string.home_build_new else R.string.home_build_first),
                    style = if (hasReel) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.titleLarge,
                    color = if (hasReel) Color.White.copy(alpha = 0.5f) else Color.White,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ReelSize.entries.forEachIndexed { index, size ->
                        SizeCard(
                            size = size,
                            enabled = !state.budgetExceeded,
                            onClick = { onBuild(size) },
                            modifier = if (index == 1 && !hasReel) Modifier.focusRequester(firstFocus)
                            else Modifier,
                        )
                    }
                }


            }

            // Always visible, never in the way. Explicitly an estimate: the real number lives in the
            // developer console, and implying otherwise would be a lie about someone's money.
            Column(Modifier.align(Alignment.BottomStart)) {
                state.note?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                }
                // Only the money spent. The ceiling is a tripwire, not a goal, so it stays out of
                // sight until it actually fires — a permanent "x / 20" invites treating 20 as a target.
                Text(
                    stringResource(R.string.home_spend, state.spentText) +
                        if (state.spendAuthoritative) "" else "  (${stringResource(R.string.home_spend_note)})",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.45f),
                )
            }
        }

        // Outside the padded container, so the scrim reaches the panel edges.
        if (confirmExit) {
            ExitConfirm(onConfirm = onExitApp, onDismiss = { confirmExit = false })
        }
        // Only ever appears once the ceiling is actually crossed.
        if (showBudgetWarning) {
            BudgetExceeded(state) { showBudgetWarning = false }
        }
    }
}

/**
 * Free actions. Uses [Card], like the paid options below, so focus reads as a border and lift. A
 * `Surface` fills solid white when focused, which on a TV looks like the button has already been
 * pressed rather than merely selected.
 */
@Composable
private fun SecondaryRow(onOpenGrid: () -> Unit, onOpenDiagnostics: () -> Unit, gridEnabled: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(onClick = { if (gridEnabled) onOpenGrid() }, modifier = Modifier.width(240.dp)) {
            Text(
                stringResource(R.string.home_open_grid),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = if (gridEnabled) 0.9f else 0.35f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
        // Visible, not hidden behind a long-press: when the app shows nothing, this screen is the
        // only way to find out why, and an undiscoverable debugger is no debugger.
        Card(onClick = onOpenDiagnostics, modifier = Modifier.width(240.dp)) {
            Text(
                stringResource(R.string.home_history),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun SizeCard(size: ReelSize, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = { if (enabled) onClick() }, modifier = modifier.width(220.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(size.labelRes), style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(6.dp))
            // "about", because the exact yield is only knowable after paying for the posts.
            Text(
                stringResource(R.string.size_estimate, size.estimatedItems),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.size_cost, "$%.2f".format(size.maxCostUsd)),
                style = MaterialTheme.typography.labelLarge, color = Accent,
            )
        }
    }
}

@Composable
private fun PrimaryCard(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.width(680.dp)) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 22.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun BudgetExceeded(state: HomeState, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    BackHandler { onDismiss() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(520.dp).background(Color(0xFF1E1E1E)).padding(28.dp)) {
            Text(stringResource(R.string.home_budget_exceeded),
                style = MaterialTheme.typography.titleLarge, color = Color(0xFFFFB300))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.home_budget_exceeded_body, state.spentText, state.capText),
                color = Color.White.copy(alpha = 0.75f))
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) {
                Text(stringResource(R.string.home_budget_ok))
            }
        }
    }
    LaunchedEffect(Unit) { repeat(6) { runCatching { focus.requestFocus() }; delay(50) } }
}

/**
 * Full-screen "leave?" overlay — same shape as the sibling PHTV/91TV apps.
 *
 * Focus starts on Cancel, so the destructive option is never one blind press away. Containment is
 * handled by the caller taking its content out of the focus graph; see [HomeScreen].
 */
@Composable
private fun ExitConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    BackHandler { onDismiss() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(440.dp).background(Color(0xFF1E1E1E)).padding(28.dp)) {
            Text(stringResource(R.string.exit_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.exit_body), color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onConfirm) { Text(stringResource(R.string.exit_confirm)) }
                Button(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) { Text(stringResource(R.string.exit_cancel)) }
            }
        }
    }
    // Retry until Cancel actually takes focus: the dialog appears over an already-laid-out screen.
    LaunchedEffect(Unit) { repeat(6) { runCatching { cancelFocus.requestFocus() }; delay(50) } }
}
