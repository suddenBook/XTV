package com.xtv.app.core.playback

import com.xtv.app.core.model.Author
import com.xtv.app.core.model.MediaItem
import com.xtv.app.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {

    @Test
    fun `finishing the final item retains the reel at its completed progress`() {
        val harness = PlaybackHarness()
        val reel = listOf(video("one"), video("two"))

        harness.coordinator.dispatch(PlaybackCommand.Open("tonight", reel, StartAt.Beginning))
        harness.player.firstFrame()
        harness.player.ended()
        harness.player.firstFrame()
        harness.player.ended()

        val state = harness.coordinator.state.value
        assertEquals(PlaybackPhase.COMPLETED, state.phase)
        assertEquals("tonight", state.reelId)
        assertEquals(2, state.nextIndex)
        assertEquals(2, state.size)
        assertEquals(2, state.played)
        assertEquals(listOf(0, 1, 2), harness.checkpoints.positions)
        assertFalse(harness.player.isPlaying)
    }

    @Test
    fun `a fatal player error advances immediately and stale terminal events cannot double advance`() {
        val harness = PlaybackHarness()
        harness.coordinator.dispatch(
            PlaybackCommand.Open("tonight", listOf(video("one"), video("two")), StartAt.Beginning),
        )
        val failedGeneration = harness.player.generation

        harness.player.fatal("decoder failed", failedGeneration)
        harness.player.ended(failedGeneration)

        val state = harness.coordinator.state.value
        assertEquals("two", state.current?.id)
        assertEquals(1, state.nextIndex)
        assertEquals(1, state.failed)
        assertEquals(0, state.played)
        assertEquals(listOf(0, 1), harness.checkpoints.positions)
    }

    @Test
    fun `home pauses timeout clocks and foreground restores the prior play intent`() {
        val harness = PlaybackHarness()
        harness.coordinator.dispatch(PlaybackCommand.Open("tonight", listOf(video("one")), StartAt.Beginning))
        harness.clock.advanceBy(7_000)

        harness.coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.BACKGROUND))
        assertFalse(harness.player.isPlaying)
        assertEquals(listOf(false), harness.player.foregroundTransitions)
        assertFalse(harness.coordinator.state.value.keepScreenOn)
        harness.clock.advanceBy(30_000)
        assertEquals(0, harness.coordinator.state.value.failed)

        harness.coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.FOREGROUND))
        assertTrue(harness.player.isPlaying)
        assertEquals(listOf(false, true), harness.player.foregroundTransitions)
        harness.clock.advanceBy(999)
        assertEquals(0, harness.coordinator.state.value.failed)
        harness.clock.advanceBy(1)
        assertEquals(1, harness.coordinator.state.value.failed)
    }

    @Test
    fun `foreground does not resume when the viewer paused before going home`() {
        val harness = PlaybackHarness()
        harness.coordinator.dispatch(PlaybackCommand.Open("tonight", listOf(video("one")), StartAt.Beginning))
        harness.player.firstFrame()
        harness.coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.TOGGLE_PAUSE))

        harness.coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.BACKGROUND))
        harness.coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.FOREGROUND))

        assertEquals(PlaybackPhase.PAUSED, harness.coordinator.state.value.phase)
        assertFalse(harness.player.isPlaying)
        assertFalse(harness.coordinator.state.value.keepScreenOn)
    }

    @Test
    fun `gif and rebuffer clocks only count effective playback time`() {
        val harness = PlaybackHarness()
        harness.coordinator.dispatch(
            PlaybackCommand.Open("loops", listOf(gif("gif", durationMs = 2_000)), StartAt.Beginning),
        )
        harness.player.firstFrame()
        harness.clock.advanceBy(3_000)
        harness.player.buffering()
        harness.clock.advanceBy(5_000)
        harness.coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.BACKGROUND))
        harness.clock.advanceBy(30_000)
        assertEquals(0, harness.coordinator.state.value.failed)
        harness.coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.FOREGROUND))
        harness.clock.advanceBy(4_999)
        assertEquals(0, harness.coordinator.state.value.failed)
        harness.player.ready()
        harness.clock.advanceBy(2_999)
        assertEquals(PlaybackPhase.PLAYING, harness.coordinator.state.value.phase)
        harness.clock.advanceBy(1)
        assertEquals(PlaybackPhase.COMPLETED, harness.coordinator.state.value.phase)
    }

    @Test
    fun `checkpoint failure is a warning and playback continues`() {
        val harness = PlaybackHarness(checkpointFailure = "disk full")
        harness.coordinator.dispatch(
            PlaybackCommand.Open("tonight", listOf(video("one"), video("two")), StartAt.Beginning),
        )
        harness.player.firstFrame()
        harness.player.ended()

        val state = harness.coordinator.state.value
        assertEquals("two", state.current?.id)
        assertEquals(PlaybackPhase.LOADING, state.phase)
        assertEquals(PlaybackWarning.CheckpointFailed("disk full"), state.warning)
    }

    @Test
    fun `next on the final item stops playback and replay starts the retained reel`() {
        val harness = PlaybackHarness()
        harness.coordinator.dispatch(PlaybackCommand.Open("tonight", listOf(video("one")), StartAt.Beginning))
        harness.player.firstFrame()

        harness.coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.NEXT))
        assertEquals(PlaybackPhase.COMPLETED, harness.coordinator.state.value.phase)
        assertEquals(1, harness.coordinator.state.value.nextIndex)
        assertEquals(1, harness.coordinator.state.value.skipped)
        assertFalse(harness.player.isPlaying)

        harness.coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.REPLAY))
        assertEquals(PlaybackPhase.LOADING, harness.coordinator.state.value.phase)
        assertEquals(0, harness.coordinator.state.value.nextIndex)
        assertEquals(0, harness.coordinator.state.value.skipped)
        assertEquals("one", harness.coordinator.state.value.current?.id)
    }

    @Test
    fun `close is idempotent and rejects later commands`() {
        val harness = PlaybackHarness()
        harness.coordinator.dispatch(PlaybackCommand.Open("tonight", listOf(video("one")), StartAt.Beginning))

        assertEquals(DispatchResult.Accepted, harness.coordinator.dispatch(PlaybackCommand.Close))
        assertEquals(DispatchResult.Accepted, harness.coordinator.dispatch(PlaybackCommand.Close))
        assertTrue(
            harness.coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.FOREGROUND)) is
                DispatchResult.Rejected,
        )
        assertEquals(PlaybackPhase.CLOSED, harness.coordinator.state.value.phase)
        assertFalse(harness.player.isPlaying)
    }

    @Test
    fun `opening at size restores the retained reel as completed`() {
        val harness = PlaybackHarness()
        val reel = listOf(video("one"), video("two"))

        harness.coordinator.dispatch(PlaybackCommand.Open("tonight", reel, StartAt.Index(2)))

        assertEquals(PlaybackPhase.COMPLETED, harness.coordinator.state.value.phase)
        assertEquals(2, harness.coordinator.state.value.nextIndex)
        assertEquals(2, harness.coordinator.state.value.size)
        assertFalse(harness.player.isPlaying)
    }

    @Test
    fun `a stale reel checkpoint cannot interrupt the active reel`() {
        val harness = PlaybackHarness(checkpointResult = CheckpointResult.StaleReel)
        harness.coordinator.dispatch(
            PlaybackCommand.Open("replacement", listOf(video("one"), video("two")), StartAt.Beginning),
        )
        harness.player.firstFrame()
        harness.player.ended()

        assertEquals("two", harness.coordinator.state.value.current?.id)
        assertEquals(1, harness.coordinator.state.value.nextIndex)
        assertNull(harness.coordinator.state.value.warning)
    }

    @Test
    fun `a synchronous player callback is serialized after the open transition`() {
        val harness = PlaybackHarness(fatalDuringLoad = true)

        harness.coordinator.dispatch(PlaybackCommand.Open("broken", listOf(video("one")), StartAt.Beginning))

        assertEquals(PlaybackPhase.COMPLETED, harness.coordinator.state.value.phase)
        assertEquals(1, harness.coordinator.state.value.nextIndex)
        assertEquals(1, harness.coordinator.state.value.failed)
        assertFalse(harness.player.isPlaying)
    }

    @Test
    fun `an older asynchronous checkpoint result cannot overwrite the latest result`() {
        val player = FakePlaybackPlayer()
        val checkpoints = DeferredCheckpoint()
        val coordinator: PlaybackCoordinator =
            DefaultPlaybackCoordinator(player, checkpoints, ManualPlaybackClock())
        coordinator.dispatch(
            PlaybackCommand.Open("tonight", listOf(video("one"), video("two")), StartAt.Beginning),
        )
        player.firstFrame()
        player.ended()

        checkpoints.complete(position = 1, CheckpointResult.Stored)
        checkpoints.complete(position = 0, CheckpointResult.Failed("late failure"))

        assertEquals(1, coordinator.state.value.nextIndex)
        assertNull(coordinator.state.value.warning)
    }

    @Test
    fun `a partial reel notice remains visible through completion and replay`() {
        val harness = PlaybackHarness()
        harness.coordinator.dispatch(
            PlaybackCommand.Open(
                reelId = "partial",
                items = listOf(video("one")),
                notice = PlaybackNotice.PartialReel,
            ),
        )
        harness.player.firstFrame()
        harness.player.ended()
        assertEquals(PlaybackNotice.PartialReel, harness.coordinator.state.value.notice)

        harness.coordinator.dispatch(PlaybackCommand.Act(PlaybackAction.REPLAY))

        assertEquals(PlaybackNotice.PartialReel, harness.coordinator.state.value.notice)
        assertEquals(0, harness.coordinator.state.value.nextIndex)
    }

    @Test
    fun `cancelled timer callback cannot fail a replacement generation`() {
        val player = FakePlaybackPlayer()
        val clock = UnreliablePlaybackClock()
        val coordinator: PlaybackCoordinator =
            DefaultPlaybackCoordinator(player, RecordingCheckpoint(), clock)
        coordinator.dispatch(
            PlaybackCommand.Open("first", listOf(video("one")), StartAt.Beginning),
        )
        clock.advanceBy(1_000)

        coordinator.dispatch(
            PlaybackCommand.Open("second", listOf(video("two")), StartAt.Beginning),
        )
        clock.advanceBy(7_000)

        assertEquals("second", coordinator.state.value.reelId)
        assertEquals("two", coordinator.state.value.current?.id)
        assertEquals(0, coordinator.state.value.failed)
    }

    @Test
    fun `late checkpoint callback cannot mutate a closed coordinator`() {
        val checkpoints = DeferredCheckpoint()
        val coordinator: PlaybackCoordinator = DefaultPlaybackCoordinator(
            FakePlaybackPlayer(),
            checkpoints,
            ManualPlaybackClock(),
        )
        coordinator.dispatch(
            PlaybackCommand.Open("reel", listOf(video("one")), StartAt.Beginning),
        )
        coordinator.dispatch(PlaybackCommand.Close)

        checkpoints.complete(0, CheckpointResult.Failed("late"))

        assertEquals(PlaybackPhase.CLOSED, coordinator.state.value.phase)
        assertNull(coordinator.state.value.warning)
    }

    private fun video(id: String) = MediaItem(
        id = id,
        kind = MediaKind.VIDEO,
        indexInPost = 0,
        countInPost = 1,
        displayUrl = "https://example.invalid/$id.mp4",
        width = 1920,
        height = 1080,
        author = Author("author", "author", "Author"),
        text = "",
        createdAtMs = 0,
    )

    private fun gif(id: String, durationMs: Long) = video(id).copy(
        kind = MediaKind.GIF,
        durationMs = durationMs,
    )
}

private class PlaybackHarness(
    checkpointFailure: String? = null,
    checkpointResult: CheckpointResult? = null,
    fatalDuringLoad: Boolean = false,
) {
    val player = FakePlaybackPlayer(fatalDuringLoad)
    val checkpoints = RecordingCheckpoint(
        result = checkpointResult ?: checkpointFailure?.let(CheckpointResult::Failed)
            ?: CheckpointResult.Stored,
    )
    val clock = ManualPlaybackClock()
    val coordinator: PlaybackCoordinator = DefaultPlaybackCoordinator(player, checkpoints, clock)
}

private class FakePlaybackPlayer(
    private val fatalDuringLoad: Boolean = false,
) : PlaybackPlayer {
    private var listener: (PlayerSignal) -> Unit = {}
    var generation = 0L
        private set
    var isPlaying = false
        private set
    val foregroundTransitions = mutableListOf<Boolean>()

    override fun setListener(listener: (PlayerSignal) -> Unit) {
        this.listener = listener
    }

    override fun load(generation: Long, item: MediaItem) {
        this.generation = generation
        if (fatalDuringLoad) listener(PlayerSignal.Fatal(generation, "synchronous failure"))
    }

    override fun setPlaying(playing: Boolean) {
        isPlaying = playing
    }

    override fun setForeground(foreground: Boolean) {
        foregroundTransitions += foreground
    }

    override fun seekBy(deltaMs: Long) = Unit

    override fun stop() {
        isPlaying = false
    }

    override fun close() {
        isPlaying = false
    }

    fun firstFrame() {
        listener(PlayerSignal.FirstFrame(generation))
    }

    fun buffering(generation: Long = this.generation) {
        listener(PlayerSignal.Buffering(generation))
    }

    fun ready(generation: Long = this.generation) {
        listener(PlayerSignal.Ready(generation))
    }

    fun ended(generation: Long = this.generation) {
        listener(PlayerSignal.Ended(generation))
    }

    fun fatal(detail: String, generation: Long = this.generation) {
        listener(PlayerSignal.Fatal(generation, detail))
    }
}

private class RecordingCheckpoint(
    private val result: CheckpointResult = CheckpointResult.Stored,
) : PlaybackCheckpoint {
    val positions = mutableListOf<Int>()

    override fun save(
        reelId: String,
        nextIndex: Int,
        onComplete: (CheckpointResult) -> Unit,
    ) {
        positions += nextIndex
        onComplete(result)
    }
}

private class DeferredCheckpoint : PlaybackCheckpoint {
    private val pending = mutableListOf<Pair<Int, (CheckpointResult) -> Unit>>()

    override fun save(
        reelId: String,
        nextIndex: Int,
        onComplete: (CheckpointResult) -> Unit,
    ) {
        pending += nextIndex to onComplete
    }

    fun complete(position: Int, result: CheckpointResult) {
        val request = pending.single { it.first == position }
        pending.remove(request)
        request.second(result)
    }
}

private class ManualPlaybackClock : PlaybackClock {
    override var nowMs: Long = 0
        private set
    private var nextId = 0L
    private val tasks = mutableListOf<Task>()

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val task = Task(++nextId, nowMs + delayMs, action)
        tasks += task
        return Cancellable { task.cancelled = true }
    }

    fun advanceBy(deltaMs: Long) {
        val target = nowMs + deltaMs
        while (true) {
            val next = tasks
                .filterNot { it.cancelled }
                .filter { it.atMs <= target }
                .minWithOrNull(compareBy<Task> { it.atMs }.thenBy { it.id })
                ?: break
            tasks.remove(next)
            nowMs = next.atMs
            next.action()
        }
        nowMs = target
    }

    private data class Task(
        val id: Long,
        val atMs: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )
}

/** Simulates a platform callback already queued when cancellation races with delivery. */
private class UnreliablePlaybackClock : PlaybackClock {
    override var nowMs: Long = 0
        private set
    private var nextId = 0L
    private val tasks = mutableListOf<Task>()

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        tasks += Task(++nextId, nowMs + delayMs, action)
        return Cancellable { /* Intentionally cannot retract an already queued callback. */ }
    }

    fun advanceBy(deltaMs: Long) {
        val target = nowMs + deltaMs
        while (true) {
            val next = tasks
                .filter { it.atMs <= target }
                .minWithOrNull(compareBy<Task> { it.atMs }.thenBy { it.id })
                ?: break
            tasks.remove(next)
            nowMs = next.atMs
            next.action()
        }
        nowMs = target
    }

    private data class Task(val id: Long, val atMs: Long, val action: () -> Unit)
}
