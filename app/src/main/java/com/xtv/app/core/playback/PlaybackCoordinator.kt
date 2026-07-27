package com.xtv.app.core.playback

import com.xtv.app.core.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Serialized playback module interface.
 *
 * [PlaybackCommand.Open.reelId] must identify one immutable item list; callers must issue a new id
 * when the list changes. [dispatch] applies the command synchronously, while checkpoint completion
 * may update [state] later. A coordinator is terminal after [PlaybackCommand.Close].
 */
interface PlaybackCoordinator {
    val state: StateFlow<PlaybackSnapshot>
    fun dispatch(command: PlaybackCommand): DispatchResult
}

sealed interface PlaybackCommand {
    data class Open(
        val reelId: String,
        val items: List<MediaItem>,
        val startAt: StartAt = StartAt.Beginning,
        val notice: PlaybackNotice? = null,
    ) : PlaybackCommand

    data class Act(val action: PlaybackAction) : PlaybackCommand
    data object Close : PlaybackCommand
}

sealed interface StartAt {
    data object Beginning : StartAt
    data class Index(val nextIndex: Int) : StartAt
}

enum class PlaybackAction {
    NEXT,
    PREVIOUS,
    SEEK_BACK,
    SEEK_FORWARD,
    TOGGLE_PAUSE,
    PLAY,
    PAUSE,
    SHOW_INFO,
    BACKGROUND,
    FOREGROUND,
    REPLAY,
}

enum class PlaybackPhase { IDLE, LOADING, PLAYING, BUFFERING, PAUSED, COMPLETED, CLOSED }

sealed interface PlaybackNotice {
    data object PartialReel : PlaybackNotice
}

data class PlaybackSnapshot(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val reelId: String? = null,
    val current: MediaItem? = null,
    /** Index of the next unfinished item. Always in `0..size`; `size` means complete. */
    val nextIndex: Int = 0,
    val size: Int = 0,
    val played: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val failureStreak: Int = 0,
    val lastFailure: String? = null,
    val showInfo: Boolean = false,
    val notice: PlaybackNotice? = null,
    val warning: PlaybackWarning? = null,
    val keepScreenOn: Boolean = false,
    val generation: Long = 0,
)

sealed interface PlaybackWarning {
    data class CheckpointFailed(val detail: String) : PlaybackWarning
}

sealed interface DispatchResult {
    data object Accepted : DispatchResult
    data class Rejected(val reason: String) : DispatchResult
}

internal sealed interface PlayerSignal {
    val generation: Long

    data class FirstFrame(override val generation: Long) : PlayerSignal
    data class Buffering(override val generation: Long) : PlayerSignal
    data class Ready(override val generation: Long) : PlayerSignal
    data class Ended(override val generation: Long) : PlayerSignal
    data class Fatal(override val generation: Long, val detail: String) : PlayerSignal
}

internal interface PlaybackPlayer {
    fun setListener(listener: (PlayerSignal) -> Unit)
    fun load(generation: Long, item: MediaItem)
    fun setPlaying(playing: Boolean)
    /** Release heavyweight playback resources while retaining the current item and position. */
    fun setForeground(foreground: Boolean)
    fun seekBy(deltaMs: Long)
    fun stop()
    fun close()
}

sealed interface CheckpointResult {
    data object Stored : CheckpointResult
    data object StaleReel : CheckpointResult
    data class Failed(val detail: String) : CheckpointResult
}

fun interface PlaybackCheckpoint {
    /**
     * Enqueue one atomic checkpoint and report its eventual result. The callback may be synchronous;
     * the coordinator serializes it after the transition that requested the write.
     */
    fun save(
        reelId: String,
        nextIndex: Int,
        onComplete: (CheckpointResult) -> Unit,
    )
}

internal fun interface Cancellable {
    fun cancel()
}

internal interface PlaybackClock {
    val nowMs: Long
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}

internal class DefaultPlaybackCoordinator(
    private val player: PlaybackPlayer,
    private val checkpoint: PlaybackCheckpoint,
    private val clock: PlaybackClock,
) : PlaybackCoordinator {
    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    override val state: StateFlow<PlaybackSnapshot> = mutableState.asStateFlow()

    private var items: List<MediaItem> = emptyList()
    private var foreground = true
    private var wantsPlaying = true
    private var generation = 0L
    private var closed = false
    private var contentPhase = PlaybackPhase.LOADING
    private var sawFirstFrame = false
    private val pendingEvents = ArrayDeque<() -> Unit>()
    private var drainingEvents = false
    private var checkpointRevision = 0L

    private val firstFrameTimer = PausableTimer(clock, ::onFirstFrameTimeout)
    private val gifTimer = PausableTimer(clock, ::onGifTimeout)
    private val rebufferTimer = PausableTimer(clock, ::onRebufferTimeout)

    init {
        player.setListener { signal -> enqueueEvent { onPlayerSignal(signal) } }
    }

    override fun dispatch(command: PlaybackCommand): DispatchResult {
        var result: DispatchResult? = null
        enqueueEvent { result = dispatchNow(command) }
        return checkNotNull(result) { "a playback command was dispatched re-entrantly" }
    }

    private fun dispatchNow(command: PlaybackCommand): DispatchResult {
        if (closed) {
            return if (command == PlaybackCommand.Close) {
                DispatchResult.Accepted
            } else {
                DispatchResult.Rejected("playback is closed")
            }
        }
        return when (command) {
            is PlaybackCommand.Open -> open(command)
            is PlaybackCommand.Act -> act(command.action)
            PlaybackCommand.Close -> {
                closed = true
                cancelTimers()
                runCatching { player.setPlaying(false) }
                runCatching { player.close() }
                mutableState.value = mutableState.value.copy(
                    phase = PlaybackPhase.CLOSED,
                    keepScreenOn = false,
                )
                DispatchResult.Accepted
            }
        }
    }

    private fun open(command: PlaybackCommand.Open): DispatchResult {
        if (command.reelId.isBlank()) return DispatchResult.Rejected("reel id is blank")
        items = command.items.toList()
        val start = when (val at = command.startAt) {
            StartAt.Beginning -> 0
            is StartAt.Index -> at.nextIndex.coerceIn(0, items.size)
        }
        wantsPlaying = true
        generation += 1
        sawFirstFrame = false
        cancelTimers()
        contentPhase = PlaybackPhase.LOADING
        mutableState.value = PlaybackSnapshot(
            phase = if (start == items.size) PlaybackPhase.COMPLETED else PlaybackPhase.LOADING,
            reelId = command.reelId,
            current = items.getOrNull(start),
            nextIndex = start,
            size = items.size,
            notice = command.notice,
            generation = generation,
        )
        player.stop()
        saveCheckpoint()
        if (start < items.size) {
            loadCurrent()
        }
        return DispatchResult.Accepted
    }

    private fun onPlayerSignal(signal: PlayerSignal) {
        val snapshot = mutableState.value
        if (closed || signal.generation != snapshot.generation) return
        when (signal) {
            is PlayerSignal.FirstFrame -> {
                if (sawFirstFrame) {
                    if (contentPhase == PlaybackPhase.BUFFERING) {
                        rebufferTimer.cancel()
                        contentPhase = PlaybackPhase.PLAYING
                        applyEffectivePlayback()
                    }
                    return
                }
                sawFirstFrame = true
                firstFrameTimer.cancel()
                contentPhase = PlaybackPhase.PLAYING
                if (snapshot.current?.kind == com.xtv.app.core.model.MediaKind.GIF) {
                    gifTimer.reset(PlaybackTiming.holdCapMs(snapshot.current))
                }
                applyEffectivePlayback()
            }
            is PlayerSignal.Buffering -> {
                if (!sawFirstFrame) return
                contentPhase = PlaybackPhase.BUFFERING
                gifTimer.pause()
                rebufferTimer.reset(PlaybackTiming.REBUFFER_TIMEOUT_MS)
                applyEffectivePlayback()
            }
            is PlayerSignal.Ready -> {
                if (!sawFirstFrame) return
                rebufferTimer.cancel()
                contentPhase = PlaybackPhase.PLAYING
                applyEffectivePlayback()
            }
            is PlayerSignal.Ended -> advance(AdvanceReason.COMPLETED)
            is PlayerSignal.Fatal -> advance(AdvanceReason.FAILED, signal.detail)
        }
    }

    private fun act(action: PlaybackAction): DispatchResult {
        val snapshot = mutableState.value
        if (action == PlaybackAction.BACKGROUND) {
            foreground = false
            if (snapshot.phase != PlaybackPhase.IDLE) applyEffectivePlayback()
            player.setForeground(false)
            return DispatchResult.Accepted
        }
        if (action == PlaybackAction.FOREGROUND) {
            foreground = true
            player.setForeground(true)
            if (snapshot.phase != PlaybackPhase.IDLE) applyEffectivePlayback()
            return DispatchResult.Accepted
        }
        if (snapshot.phase == PlaybackPhase.IDLE) {
            return DispatchResult.Rejected("no reel is open")
        }
        when (action) {
            PlaybackAction.NEXT -> {
                if (snapshot.phase == PlaybackPhase.COMPLETED) {
                    player.setPlaying(false)
                    player.stop()
                } else {
                    advance(AdvanceReason.SKIPPED)
                }
            }
            PlaybackAction.PREVIOUS -> {
                if (items.isEmpty()) return DispatchResult.Accepted
                val target = when {
                    snapshot.phase == PlaybackPhase.COMPLETED -> (items.size - 1).coerceAtLeast(0)
                    snapshot.nextIndex > 0 -> snapshot.nextIndex - 1
                    else -> return DispatchResult.Accepted
                }
                moveTo(target, countSkip = snapshot.phase != PlaybackPhase.COMPLETED)
            }
            PlaybackAction.SEEK_BACK -> {
                if (snapshot.phase != PlaybackPhase.COMPLETED) player.seekBy(-SEEK_MS)
            }
            PlaybackAction.SEEK_FORWARD -> {
                if (snapshot.phase != PlaybackPhase.COMPLETED) player.seekBy(SEEK_MS)
            }
            PlaybackAction.TOGGLE_PAUSE -> {
                if (snapshot.phase == PlaybackPhase.COMPLETED) return DispatchResult.Accepted
                wantsPlaying = !wantsPlaying
                applyEffectivePlayback()
            }
            PlaybackAction.PLAY -> {
                if (snapshot.phase == PlaybackPhase.COMPLETED) return DispatchResult.Accepted
                wantsPlaying = true
                applyEffectivePlayback()
            }
            PlaybackAction.PAUSE -> {
                if (snapshot.phase == PlaybackPhase.COMPLETED) return DispatchResult.Accepted
                wantsPlaying = false
                applyEffectivePlayback()
            }
            PlaybackAction.SHOW_INFO -> {
                mutableState.value = snapshot.copy(showInfo = true)
            }
            PlaybackAction.BACKGROUND, PlaybackAction.FOREGROUND -> error("handled above")
            PlaybackAction.REPLAY -> {
                if (items.isEmpty()) return DispatchResult.Accepted
                generation += 1
                cancelTimers()
                sawFirstFrame = false
                contentPhase = PlaybackPhase.LOADING
                wantsPlaying = true
                mutableState.value = PlaybackSnapshot(
                    phase = effectivePhase(),
                    reelId = snapshot.reelId,
                    current = items.first(),
                    nextIndex = 0,
                    size = items.size,
                    notice = snapshot.notice,
                    generation = generation,
                )
                player.stop()
                saveCheckpoint()
                loadCurrent()
            }
        }
        return DispatchResult.Accepted
    }

    private fun advance(reason: AdvanceReason, detail: String? = null) {
        val snapshot = mutableState.value
        if (snapshot.phase == PlaybackPhase.COMPLETED) return
        val next = (snapshot.nextIndex + 1).coerceAtMost(items.size)
        generation += 1
        cancelTimers()
        sawFirstFrame = false
        contentPhase = PlaybackPhase.LOADING
        val complete = next == items.size
        val nextPlayed = snapshot.played + if (reason == AdvanceReason.COMPLETED) 1 else 0
        val nextSkipped = snapshot.skipped + if (reason == AdvanceReason.SKIPPED) 1 else 0
        val nextFailed = snapshot.failed + if (reason == AdvanceReason.FAILED) 1 else 0
        val streak = if (reason == AdvanceReason.FAILED) snapshot.failureStreak + 1 else 0
        mutableState.value = snapshot.copy(
            phase = if (complete) PlaybackPhase.COMPLETED else effectivePhase(),
            current = items.getOrNull(next),
            nextIndex = next,
            played = nextPlayed,
            skipped = nextSkipped,
            failed = nextFailed,
            failureStreak = streak,
            lastFailure = if (reason == AdvanceReason.FAILED) detail else null,
            showInfo = false,
            warning = snapshot.warning,
            keepScreenOn = false,
            generation = generation,
        )
        player.setPlaying(false)
        player.stop()
        saveCheckpoint()
        if (!complete) loadCurrent()
    }

    private fun moveTo(target: Int, countSkip: Boolean) {
        val snapshot = mutableState.value
        generation += 1
        cancelTimers()
        sawFirstFrame = false
        contentPhase = PlaybackPhase.LOADING
        mutableState.value = snapshot.copy(
            phase = effectivePhase(),
            current = items[target],
            nextIndex = target,
            skipped = snapshot.skipped + if (countSkip) 1 else 0,
            failureStreak = 0,
            showInfo = false,
            keepScreenOn = false,
            generation = generation,
        )
        player.setPlaying(false)
        player.stop()
        saveCheckpoint()
        loadCurrent()
    }

    private fun loadCurrent() {
        val snapshot = mutableState.value
        val item = snapshot.current ?: return
        firstFrameTimer.reset(PlaybackTiming.FIRST_FRAME_TIMEOUT_MS)
        val failure = runCatching { player.load(generation, item) }.exceptionOrNull()
        if (failure != null) {
            enqueueEvent {
                advance(
                    AdvanceReason.FAILED,
                    failure.message ?: failure.javaClass.simpleName,
                )
            }
            return
        }
        applyEffectivePlayback()
    }

    private fun applyEffectivePlayback() {
        val snapshot = mutableState.value
        if (snapshot.phase == PlaybackPhase.COMPLETED || snapshot.phase == PlaybackPhase.CLOSED) {
            player.setPlaying(false)
            pauseTimers()
            mutableState.value = snapshot.copy(keepScreenOn = false)
            return
        }
        val effective = foreground && wantsPlaying
        player.setPlaying(effective)
        if (effective) {
            when (contentPhase) {
                PlaybackPhase.LOADING -> firstFrameTimer.resume()
                PlaybackPhase.PLAYING -> gifTimer.resume()
                PlaybackPhase.BUFFERING -> rebufferTimer.resume()
                else -> Unit
            }
        } else {
            pauseTimers()
        }
        mutableState.value = snapshot.copy(
            phase = effectivePhase(),
            keepScreenOn = effective && contentPhase == PlaybackPhase.PLAYING,
        )
    }

    private fun effectivePhase(): PlaybackPhase =
        if (!foreground || !wantsPlaying) PlaybackPhase.PAUSED else contentPhase

    private fun onFirstFrameTimeout() {
        enqueueEvent { advance(AdvanceReason.FAILED, "first frame timeout") }
    }

    private fun onGifTimeout() {
        enqueueEvent { advance(AdvanceReason.COMPLETED) }
    }

    private fun onRebufferTimeout() {
        enqueueEvent { advance(AdvanceReason.FAILED, "rebuffer timeout") }
    }

    private fun pauseTimers() {
        firstFrameTimer.pause()
        gifTimer.pause()
        rebufferTimer.pause()
    }

    private fun cancelTimers() {
        firstFrameTimer.cancel()
        gifTimer.cancel()
        rebufferTimer.cancel()
    }

    private fun saveCheckpoint() {
        val snapshot = mutableState.value
        val reelId = snapshot.reelId ?: return
        checkpointRevision += 1
        val revision = checkpointRevision
        try {
            checkpoint.save(reelId, snapshot.nextIndex) { result ->
                enqueueEvent { onCheckpointResult(reelId, revision, result) }
            }
        } catch (failure: Throwable) {
            enqueueEvent {
                onCheckpointResult(
                    reelId,
                    revision,
                    CheckpointResult.Failed(failure.message ?: failure.javaClass.simpleName),
                )
            }
        }
    }

    private fun onCheckpointResult(
        reelId: String,
        revision: Long,
        result: CheckpointResult,
    ) {
        if (closed) return
        if (mutableState.value.reelId != reelId || checkpointRevision != revision) return
        when (result) {
            CheckpointResult.Stored -> {
                mutableState.value = mutableState.value.copy(warning = null)
            }
            CheckpointResult.StaleReel -> Unit
            is CheckpointResult.Failed -> {
                mutableState.value = mutableState.value.copy(
                    warning = PlaybackWarning.CheckpointFailed(result.detail),
                )
            }
        }
    }

    /**
     * Player and timer adapters may call back synchronously while a transition is still issuing
     * side effects. Queueing under one monitor makes those callbacks the next complete event rather
     * than allowing them to observe a half-applied transition.
     */
    @Synchronized
    private fun enqueueEvent(event: () -> Unit) {
        pendingEvents.addLast(event)
        if (drainingEvents) return
        drainingEvents = true
        try {
            while (pendingEvents.isNotEmpty()) {
                pendingEvents.removeFirst().invoke()
            }
        } finally {
            drainingEvents = false
        }
    }

    private enum class AdvanceReason { COMPLETED, SKIPPED, FAILED }

    private companion object {
        const val SEEK_MS = 10_000L
    }
}

internal object PlaybackTiming {
    const val FIRST_FRAME_TIMEOUT_MS = 8_000L
    const val REBUFFER_TIMEOUT_MS = 10_000L
    const val GIF_MIN_MS = 6_000L
    const val GIF_MAX_MS = 15_000L
    private const val GIF_TARGET_LOOPS = 3

    fun holdCapMs(item: MediaItem): Long {
        val loop = item.durationMs ?: 0L
        return if (loop <= 0L) {
            GIF_MIN_MS
        } else {
            (loop * GIF_TARGET_LOOPS).coerceIn(GIF_MIN_MS, GIF_MAX_MS)
        }
    }
}

private class PausableTimer(
    private val clock: PlaybackClock,
    private val action: () -> Unit,
) {
    private var remainingMs = 0L
    private var startedAtMs = 0L
    private var handle: Cancellable? = null
    private var revision = 0L

    fun reset(durationMs: Long) {
        cancel()
        remainingMs = durationMs
    }

    fun resume() {
        if (handle != null || remainingMs <= 0L) return
        startedAtMs = clock.nowMs
        val scheduledRevision = revision
        handle = clock.schedule(remainingMs) {
            if (revision != scheduledRevision) return@schedule
            handle = null
            remainingMs = 0
            action()
        }
    }

    fun pause() {
        val active = handle ?: return
        revision += 1
        active.cancel()
        handle = null
        remainingMs = (remainingMs - (clock.nowMs - startedAtMs)).coerceAtLeast(0)
    }

    fun cancel() {
        revision += 1
        handle?.cancel()
        handle = null
        remainingMs = 0
    }
}
