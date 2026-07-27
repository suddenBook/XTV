package com.xtv.app.core.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.xtv.app.R
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.MediaKind

/**
 * Android adapter for the playback module. Callers get a plain [View], a lifecycle binding and the
 * module's small [PlaybackCoordinator] interface; Media3 and timer details do not leak out.
 */
@OptIn(UnstableApi::class)
class AndroidPlaybackSession private constructor(
    val coordinator: PlaybackCoordinator,
    val contentView: View,
) {
    fun bind(owner: LifecycleOwner): AutoCloseable {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.FOREGROUND))
                Lifecycle.Event.ON_PAUSE ->
                    coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.BACKGROUND))
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.BACKGROUND))
        }
        return AutoCloseable { owner.lifecycle.removeObserver(observer) }
    }

    companion object {
        fun create(
            context: Context,
            checkpoint: PlaybackCheckpoint = FixturePlaybackCheckpoint,
        ): AndroidPlaybackSession {
            val player = ExoPlayer.Builder(context).build()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            val inflationParent = FrameLayout(context)
            val view = LayoutInflater.from(context)
                .inflate(R.layout.reel_player_view, inflationParent, false) as PlayerView
            view.apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setPlayer(player)
            }
            val adapter = Media3PlaybackPlayer(player)
            val coordinator = DefaultPlaybackCoordinator(
                player = adapter,
                checkpoint = checkpoint,
                clock = AndroidPlaybackClock(),
            )
            return AndroidPlaybackSession(coordinator, view)
        }
    }
}

/** Debug fixtures use this adapter so a playback exercise cannot alter a saved real reel. */
data object FixturePlaybackCheckpoint : PlaybackCheckpoint {
    override fun save(
        reelId: String,
        nextIndex: Int,
        onComplete: (CheckpointResult) -> Unit,
    ) {
        onComplete(CheckpointResult.Stored)
    }
}

private class AndroidPlaybackClock(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : PlaybackClock {
    override val nowMs: Long get() = SystemClock.elapsedRealtime()

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val callback = Runnable(action)
        handler.postDelayed(callback, delayMs)
        return Cancellable { handler.removeCallbacks(callback) }
    }
}

@OptIn(UnstableApi::class)
private class Media3PlaybackPlayer(
    private val player: ExoPlayer,
) : PlaybackPlayer {
    private var signal: (PlayerSignal) -> Unit = {}
    private var media3Listener: Player.Listener? = null

    override fun setListener(listener: (PlayerSignal) -> Unit) {
        signal = listener
    }

    override fun load(generation: Long, item: MediaItem) {
        // Listener identity is generation identity. Removing the old listener means a queued
        // callback either carries its old captured generation (and is ignored by the coordinator)
        // or is suppressed by Media3; it can never be relabeled as the newly loaded item.
        media3Listener?.let(player::removeListener)
        media3Listener = listenerFor(generation).also(player::addListener)
        player.repeatMode =
            if (item.kind == MediaKind.GIF) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.setMediaItem(Media3Item.fromUri(item.displayUrl))
        player.prepare()
    }

    override fun setPlaying(playing: Boolean) {
        player.playWhenReady = playing
    }

    override fun setForeground(foreground: Boolean) {
        if (foreground) {
            if (player.mediaItemCount > 0 && player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
        } else {
            // pause() alone intentionally retains Media3's audio-focus grant. stop() releases the
            // focus and decoders while keeping the playlist and current position for prepare().
            player.pause()
            player.stop()
        }
    }

    override fun seekBy(deltaMs: Long) {
        player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0))
    }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    override fun close() {
        media3Listener?.let(player::removeListener)
        media3Listener = null
        player.release()
    }

    private fun listenerFor(generation: Long): Player.Listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            signal(PlayerSignal.FirstFrame(generation))
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> Unit
                Player.STATE_BUFFERING -> signal(PlayerSignal.Buffering(generation))
                Player.STATE_READY -> signal(PlayerSignal.Ready(generation))
                Player.STATE_ENDED -> signal(PlayerSignal.Ended(generation))
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            signal(PlayerSignal.Fatal(generation, error.errorCodeName))
        }
    }
}
