package com.xtv.app.ui.viewer

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.xtv.app.R
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.MediaKind
import kotlinx.coroutines.delay

private const val TAG = "XTV-REEL"
private val Accent = Color(0xFF1D9BF0)
private const val SEEK_MS = 10_000L

/** Why an item stopped being on screen — drives the end-card counts. */
private enum class Advance { COMPLETED, SKIPPED, FAILED }

/**
 * The reel: plays a fixed, finite list of photos, videos and GIFs end to end without further input.
 *
 * **Why a hand-rolled coordinator rather than a Media3 playlist with image items.** Media3 can carry
 * images in a playlist, but the display half still has to be written by hand, and every API involved
 * (`ContentFrame`, `PresentationState`, `setImageDurationMs`, `DefaultPreloadManager`) is
 * `@UnstableApi` — that would put the app's entire critical path on unstable surface. A coroutine
 * state machine also gets per-item dwell, pause/resume and "hold longer on this one" for free, each
 * of which is a fight against a playlist.
 *
 * **Why hard cuts.** A crossfade cannot dissolve the video layer: `SurfaceView` is a separate
 * composited window, so a Compose alpha animation has nothing to fade. Mixing in a `TextureView`
 * instead costs an extra copy and a frame of latency on weak SoCs. Cuts with the next item already
 * warmed look deliberate; blank frames and spinners look cheap.
 *
 * **D-pad.** UP/DOWN always move between items so the gesture means one thing regardless of what is
 * on screen. LEFT/RIGHT is type-dependent (seek on video, sibling photos within a post) which is
 * learnable precisely because the thing on screen changed. OK is *always* pause/resume — it can
 * never mean "show info", because in an ambient reel pause is the one control the user reaches for
 * without looking.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ReelViewer(
    items: List<MediaItem>,
    onExit: () -> Unit,
    startIndex: Int = 0,
    onProgress: (Int) -> Unit = {},
) {
    if (items.isEmpty()) {
        EmptyReel(onExit)
        return
    }

    val context = LocalContext.current
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, items.lastIndex)) }
    var paused by remember { mutableStateOf(false) }
    // Sound on by default: this reel is watched deliberately, not left running as wallpaper, and a
    // silent video reel is the wrong experience. M toggles it for the session.
    var muted by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    var played by remember { mutableIntStateOf(0) }
    var skipped by remember { mutableIntStateOf(0) }
    var failed by remember { mutableIntStateOf(0) }
    var failureStreak by remember { mutableIntStateOf(0) }

    // Bumped on every manual navigation so the dwell effect restarts even when the index is unchanged
    // (e.g. LEFT/RIGHT within a post, or seeking).
    var restartToken by remember { mutableIntStateOf(0) }

    val current = items[index]
    val latestExit by rememberUpdatedState(onExit)

    val player = remember { ExoPlayer.Builder(context).build() }
    val playerView = remember {
        // Inflated from XML because `surface_type` cannot be set in code, and this reel needs a
        // TextureView: see reel_player_view.xml for why a SurfaceView squashes landscape clips that
        // follow portrait ones.
        (LayoutInflater.from(context).inflate(R.layout.reel_player_view, null) as PlayerView).apply {
            useController = false
            // We own the D-pad in Compose; the view must not swallow keys to pop its own controller.
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // Pillarbox rather than stretch. Also set here so the intent survives an XML edit.
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setPlayer(player)
        }
    }

    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(paused) { player.playWhenReady = !paused }

    fun advance(reason: Advance) {
        when (reason) {
            Advance.COMPLETED -> { played++; failureStreak = 0 }
            Advance.SKIPPED -> { skipped++; failureStreak = 0 }
            Advance.FAILED -> { failed++; failureStreak++ }
        }
        if (index >= items.lastIndex) finished = true else index++
    }

    // --- the coordinator ----------------------------------------------------------------------
    // One effect per item. Photos are a timer; video and GIF hand off to the player and wait for it.
    // Persist the position as it moves, so a kill mid-reel resumes where it stopped.
    LaunchedEffect(index) { onProgress(index) }

    LaunchedEffect(index, restartToken, finished) {
        if (finished) return@LaunchedEffect
        val item = items[index]
        Log.d(TAG, "show ${item.kind} ${item.id} (${index + 1}/${items.size})")

        player.setMediaItem(Media3Item.fromUri(item.displayUrl))
        // GIFs are silent loops with no natural end, so they repeat and are ended by the cap below.
        // Real videos play once, to completion — no cap: if a creator posted two minutes, that is
        // the content, and cutting it short was solving a problem this reel does not have.
        player.repeatMode = if (item.kind == MediaKind.GIF) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.prepare()
        player.playWhenReady = !paused

        val cap = ReelPolicy.holdCapMs(item)
        var waited = 0L
        var sawFirstFrame = false
        var rebufferingFor = 0L

        while (true) {
            delay(100)
            if (!paused) waited += 100

            if (player.playbackState == Player.STATE_READY && player.currentPosition > 0) {
                sawFirstFrame = true
                rebufferingFor = 0
            } else if (sawFirstFrame && player.playbackState == Player.STATE_BUFFERING) {
                rebufferingFor += 100
            }

            // Never let one dead item end the evening: bail on a stall and keep moving.
            if (!sawFirstFrame && waited >= ReelPolicy.FIRST_FRAME_TIMEOUT_MS) {
                Log.w(TAG, "no first frame for ${item.id}, skipping")
                advance(Advance.FAILED); return@LaunchedEffect
            }
            if (rebufferingFor >= ReelPolicy.REBUFFER_TIMEOUT_MS) {
                Log.w(TAG, "rebuffer timeout on ${item.id}, skipping")
                advance(Advance.FAILED); return@LaunchedEffect
            }
            if (player.playbackState == Player.STATE_ENDED) {
                advance(Advance.COMPLETED); return@LaunchedEffect
            }
            if (cap != null && waited >= cap) {
                advance(Advance.COMPLETED); return@LaunchedEffect
            }
        }
    }

    // Player-level errors are terminal for the item; the loop above would otherwise wait out the
    // first-frame timeout for nothing.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "player error on ${items[index].id}: ${error.errorCodeName}")
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // No image preloading: the reel is video only, and warming a second ExoPlayer would cost a
    // scarce hardware decoder slot for little gain on clips this short. Plain HTTP warming of the
    // next URL is the cheap win if "next" ever feels slow — measure before adding it.

    // --- input --------------------------------------------------------------------------------
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(5) { runCatching { focus.requestFocus() }; delay(60) } }

    // Back must never toggle. Android TV requires that pressing it repeatedly always ends up at the
    // home screen; a reveal/hide pair traps the user in the player with no way out. So the first
    // press adds context, and any press after that leaves — the overlay is dismissed by moving on,
    // not by pressing Back again.
    BackHandler {
        when {
            finished -> latestExit()
            !showInfo -> showInfo = true
            else -> latestExit()
        }
    }

    fun step(delta: Int) {
        showInfo = false
        val next = index + delta
        if (next < 0) return
        if (next > items.lastIndex) { finished = true; return }
        skipped++
        index = next
    }

    /** Sibling media inside the same post — LEFT/RIGHT on a photo walks these. */
    fun stepWithinPost(delta: Int) {
        val postId = current.id.substringBefore(':')
        val target = index + delta
        if (target in items.indices && items[target].id.substringBefore(':') == postId) {
            index = target
        } else {
            step(delta)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (finished) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionUp -> { step(-1); true }
                    Key.DirectionDown -> { step(1); true }
                    // Every item is video now, so LEFT/RIGHT is unambiguously seek.
                    Key.DirectionLeft -> { player.seekTo((player.currentPosition - SEEK_MS).coerceAtLeast(0)); true }
                    Key.DirectionRight -> { player.seekTo(player.currentPosition + SEEK_MS); true }
                    Key.DirectionCenter, Key.Enter -> { paused = !paused; showInfo = paused; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (finished) {
            EndCard(played, skipped, failed, onExit = latestExit)
            return@Box
        }

        AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())

        if (paused) {
            Text(
                "❚❚",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp),
            )
        }

        if (showInfo) InfoOverlay(current, index, items.size, muted, Modifier.align(Alignment.BottomStart))

        if (failureStreak >= ReelPolicy.FAILURE_STREAK_WARNING) {
            Text(
                stringResource(R.string.reel_failures, failureStreak),
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFFFFB300),
                modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp),
            )
        }
    }
}

@Composable
private fun InfoOverlay(
    item: MediaItem,
    index: Int,
    total: Int,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("@${item.author.username}", style = MaterialTheme.typography.titleMedium, color = Accent)
            Spacer(Modifier.height(0.dp))
            Text(
                "   ${index + 1} / $total" +
                    (if (item.countInPost > 1) "   ·   ${item.indexInPost + 1}/${item.countInPost}" else "") +
                    (if (muted) "   ·   " + stringResource(R.string.reel_muted) else ""),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        if (item.text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            // No maxLines: the post text is the context for what is on screen, and a clipped
            // sentence is worse than a taller overlay. Posts cap out around 280 characters, so the
            // gradient panel simply grows to fit.
            Text(
                item.text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * The done-moment. The reel is a finite snapshot, so it ends and says so — it never silently
 * fetches more. "That's tonight's reel" rather than "you're caught up", because with a timeline
 * that outruns the viewer roughly eighty to one, caught up would be a lie.
 */
@Composable
private fun EndCard(played: Int, skipped: Int, failed: Int, onExit: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(5) { runCatching { focus.requestFocus() }; delay(60) } }
    Column(
        Modifier.fillMaxSize().focusRequester(focus).focusable(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.reel_done_title), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.reel_done_counts, played) +
                (if (skipped > 0) stringResource(R.string.reel_done_skipped, skipped) else "") +
                (if (failed > 0) stringResource(R.string.reel_done_failed, failed) else ""),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.reel_done_hint), style = MaterialTheme.typography.bodyMedium, color = Accent)
    }
    BackHandler { onExit() }
}

@Composable
private fun EmptyReel(onExit: () -> Unit) {
    BackHandler { onExit() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.reel_empty), style = MaterialTheme.typography.titleLarge, color = Color.White)
    }
}
