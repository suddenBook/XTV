package com.xtv.app.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.KeyEvent as AndroidKeyEvent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.xtv.app.R
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.playback.AndroidPlaybackSession
import com.xtv.app.core.playback.CheckpointResult
import com.xtv.app.core.playback.FixturePlaybackCheckpoint
import com.xtv.app.core.playback.PlaybackAction
import com.xtv.app.core.playback.PlaybackCheckpoint
import com.xtv.app.core.playback.PlaybackCommand
import com.xtv.app.core.playback.PlaybackNotice
import com.xtv.app.core.playback.PlaybackPhase
import com.xtv.app.core.playback.PlaybackWarning
import com.xtv.app.core.playback.StartAt
import com.xtv.app.ui.common.rememberInitialFocus
import com.xtv.app.ui.theme.XtvPalette
import com.xtv.app.ui.theme.XtvSpacing
import com.xtv.app.ui.theme.XtvText
import kotlinx.coroutines.delay

/** How long a seek acknowledgement stays up. Long enough to register, short enough not to nag. */
private const val SEEK_HINT_MS = 900L

/**
 * Waiting this long before admitting to a wait. Every item transition passes through LOADING, and a
 * spinner that flashes on each one is worse than no spinner at all.
 */
private const val SPINNER_DELAY_MS = 350L

/** The incomplete-batch notice has said its piece by now; it does not need to watch the whole reel. */
private const val PARTIAL_NOTICE_MS = 8_000L

/** Long enough to read a post's opening line as a clip starts, short enough to stay out of the way. */
private const val NEW_ITEM_INFO_MS = 3_000L

/**
 * TV presentation adapter for the deep playback module.
 *
 * [onProgress] receives the next unfinished index (`0..items.size`), not the item currently on
 * screen. Fixture callers must set [fixture] so these transitions cannot mutate the saved reel.
 *
 * The module is not touched by any of the feedback added here. It exposes phases and counts but no
 * position, and giving it one would mean a heartbeat inside a deliberately event-driven, fully
 * deterministic coordinator plus a rewrite of its fifteen tests — for a scrubber this app does not
 * need, since it plays a fixed list end to end rather than seeking around inside one video. What it
 * did need was an answer to "did that button do anything", and that is entirely a view concern.
 */
@Composable
fun ReelViewer(
    items: List<MediaItem>,
    onExit: () -> Unit,
    startIndex: Int = 0,
    onProgress: (Int) -> Unit = {},
    reelId: String? = null,
    fixture: Boolean = false,
    playbackCheckpoint: PlaybackCheckpoint? = null,
    partial: Boolean = false,
) {
    if (items.isEmpty()) {
        EmptyReel(onExit)
        return
    }

    val context = LocalContext.current
    val fallbackReelId = rememberReelId(items)
    val resolvedReelId = reelId ?: fallbackReelId
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestProgress by rememberUpdatedState(onProgress)
    val latestExit by rememberUpdatedState(onExit)
    val checkpoint = remember(fixture, playbackCheckpoint) {
        if (fixture) {
            FixturePlaybackCheckpoint
        } else if (playbackCheckpoint != null) {
            playbackCheckpoint
        } else {
            PlaybackCheckpoint { _, nextIndex, onComplete ->
                onComplete(
                    runCatching { latestProgress(nextIndex) }
                        .fold(
                            onSuccess = { CheckpointResult.Stored },
                            onFailure = { CheckpointResult.Failed(it.message ?: it.javaClass.simpleName) },
                        ),
                )
            }
        }
    }
    val session = remember(context, checkpoint) { AndroidPlaybackSession.create(context, checkpoint) }
    val state by session.coordinator.state.collectAsStateWithLifecycle()

    DisposableEffect(session, lifecycleOwner) {
        val binding = session.bind(lifecycleOwner)
        onDispose {
            binding.close()
            session.coordinator.dispatch(PlaybackCommand.Close)
        }
    }
    LaunchedEffect(session, resolvedReelId, items, startIndex, partial) {
        session.coordinator.dispatch(
            PlaybackCommand.Open(
                reelId = resolvedReelId,
                items = items,
                startAt = StartAt.Index(startIndex),
                notice = if (partial) PlaybackNotice.PartialReel else null,
            ),
        )
    }
    SideEffect {
        session.contentView.keepScreenOn = state.keepScreenOn
    }

    val focus = rememberInitialFocus(resolvedReelId)

    // Left and right used to do their work in complete silence: the coordinator forwards the seek
    // straight into the player and never reports a position back, so ten seconds of a two-minute
    // clip is a change most viewers cannot see. Pressing a button and getting nothing reads as a
    // broken remote.
    var seekHint by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(SEEK_HINT_MS)
            seekHint = null
        }
    }

    var showSpinner by remember { mutableStateOf(false) }
    val waiting = state.phase == PlaybackPhase.LOADING || state.phase == PlaybackPhase.BUFFERING
    LaunchedEffect(waiting, state.generation) {
        showSpinner = false
        if (waiting) {
            delay(SPINNER_DELAY_MS)
            showSpinner = true
        }
    }

    // Whose post is this? The question arrives at two moments — when a new clip starts, and when
    // you stop to look at one — and it used to be answerable at neither without pressing BACK,
    // which is also the key that leaves. `generation` increments on every item change the
    // coordinator makes, whether the previous clip ended by itself or the viewer skipped it, so one
    // key covers both.
    var showNewItemInfo by remember { mutableStateOf(false) }
    LaunchedEffect(state.generation) {
        showNewItemInfo = true
        delay(NEW_ITEM_INFO_MS)
        showNewItemInfo = false
    }

    var showPartial by remember(state.notice) {
        mutableStateOf(state.notice == PlaybackNotice.PartialReel)
    }
    LaunchedEffect(showPartial) {
        if (showPartial) {
            delay(PARTIAL_NOTICE_MS)
            showPartial = false
        }
    }

    BackHandler {
        when {
            state.phase == PlaybackPhase.COMPLETED -> latestExit()
            !state.showInfo ->
                session.coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.SHOW_INFO))
            else -> latestExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onKeyEvent false
                }
                if (event.nativeKeyEvent.repeatCount > 0) {
                    return@onKeyEvent when (event.key) {
                        Key.DirectionUp,
                        Key.DirectionDown,
                        Key.DirectionLeft,
                        Key.DirectionRight,
                        Key.DirectionCenter,
                        Key.Enter -> true
                        else -> false
                    }
                }
                val mediaAction = when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlaybackAction.TOGGLE_PAUSE
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> PlaybackAction.PLAY
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> PlaybackAction.PAUSE
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> PlaybackAction.NEXT
                    AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlaybackAction.PREVIOUS
                    else -> null
                }
                val action = mediaAction ?: when (event.key) {
                    Key.DirectionUp -> PlaybackAction.PREVIOUS
                    Key.DirectionDown -> PlaybackAction.NEXT
                    Key.DirectionLeft -> PlaybackAction.SEEK_BACK
                    Key.DirectionRight -> PlaybackAction.SEEK_FORWARD
                    Key.DirectionCenter, Key.Enter ->
                        if (state.phase == PlaybackPhase.COMPLETED) {
                            PlaybackAction.REPLAY
                        } else {
                            PlaybackAction.TOGGLE_PAUSE
                        }
                    else -> return@onKeyEvent false
                }
                if (state.phase != PlaybackPhase.COMPLETED) {
                    when (action) {
                        PlaybackAction.SEEK_BACK ->
                            seekHint = R.string.seek_back to ((seekHint?.second ?: 0L) + 1)
                        PlaybackAction.SEEK_FORWARD ->
                            seekHint = R.string.seek_forward to ((seekHint?.second ?: 0L) + 1)
                        else -> Unit
                    }
                }
                session.coordinator.dispatch(PlaybackCommand.Act(action))
                true
            }
            .focusable(),
    ) {
        if (state.phase == PlaybackPhase.COMPLETED) {
            EndCard(state.played, state.failed, onExit = latestExit)
            return@Box
        }

        AndroidView(factory = { session.contentView }, modifier = Modifier.fillMaxSize())

        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(48.dp),
                color = Color.White.copy(alpha = 0.85f),
                strokeWidth = 3.dp,
            )
        }

        if (state.phase == PlaybackPhase.PAUSED) {
            PauseBadge(Modifier.align(Alignment.TopEnd).padding(XtvSpacing.ScreenH))
        }

        seekHint?.let { (label, _) ->
            Text(
                stringResource(label),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        RoundedCornerShape(999.dp),
                    )
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }

        // Paused means you are looking at it, so the text stays for as long as you are. Resuming
        // takes it away again without a keypress of its own.
        val infoVisible = state.showInfo ||
            state.phase == PlaybackPhase.PAUSED ||
            showNewItemInfo
        state.current?.takeIf { infoVisible }?.let { current ->
            InfoOverlay(
                item = current,
                index = state.nextIndex,
                total = state.size,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        // One at a time, worst first. Stacking warnings over moving video is how you get a screen
        // nobody reads; the batch notice also stands down after a while, because it describes how
        // this batch was fetched, not something that is still going wrong.
        val warning = when {
            state.failureStreak >= ReelPolicy.FAILURE_STREAK_WARNING ->
                pluralStringResource(
                    R.plurals.reel_failures,
                    state.failureStreak,
                    state.failureStreak,
                )
            state.warning is PlaybackWarning.CheckpointFailed ->
                stringResource(R.string.reel_checkpoint_warning)
            showPartial -> stringResource(R.string.reel_partial_warning)
            else -> null
        }
        warning?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = XtvPalette.Warning,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(XtvSpacing.ScreenH)
                    .background(
                        Color.Black.copy(alpha = 0.55f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun rememberReelId(items: List<MediaItem>): String = remember(items) {
    if (items.isEmpty()) {
        "empty"
    } else {
        "reel:${items.first().id}:${items.last().id}:${items.size}"
    }
}

/** Two bars, drawn. The glyph this replaces rendered at whatever the system font felt like. */
@Composable
private fun PauseBadge(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) {
            Box(
                Modifier
                    .width(7.dp)
                    .height(28.dp)
                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun InfoOverlay(
    item: MediaItem,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = XtvSpacing.ScreenH, vertical = XtvSpacing.ScreenV),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "@${item.author.username}",
                style = MaterialTheme.typography.titleMedium,
                color = XtvPalette.Accent,
            )
            Spacer(Modifier.width(XtvSpacing.Gap))
            Text(
                "${index + 1} / $total",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.6f),
            )
            // How long this clip runs was already on every grid card and nowhere in the player.
            item.durationMs?.takeIf { it > 0 }?.let {
                Spacer(Modifier.width(XtvSpacing.Gap))
                Text(
                    ReelPolicy.formatDuration(it),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
            if (item.countInPost > 1) {
                Spacer(Modifier.width(XtvSpacing.Gap))
                Text(
                    "${item.indexInPost + 1}/${item.countInPost}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
        if (item.text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                item.text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * Skipped items are deliberately absent. The viewer pressed a button to skip them; being counted
 * back at is not information, it is bookkeeping. Failures stay, because those were not a choice.
 */
@Composable
private fun EndCard(played: Int, failed: Int, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.reel_done_title),
            style = MaterialTheme.typography.headlineMedium,
            color = XtvText.Primary,
        )
        Spacer(Modifier.height(XtvSpacing.Heading))
        Text(
            if (failed > 0) {
                pluralStringResource(
                    R.plurals.reel_done_played_with_failures,
                    played,
                    played,
                    failed,
                )
            } else {
                pluralStringResource(R.plurals.reel_done_played, played, played)
            },
            style = MaterialTheme.typography.titleMedium,
            color = XtvText.Secondary,
        )
        Spacer(Modifier.height(XtvSpacing.Section))
        Text(
            stringResource(R.string.reel_done_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = XtvPalette.Accent,
        )
    }
    BackHandler { onExit() }
}

@Composable
private fun EmptyReel(onExit: () -> Unit) {
    BackHandler { onExit() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.reel_empty),
            style = MaterialTheme.typography.titleLarge,
            color = XtvText.Primary,
        )
    }
}
