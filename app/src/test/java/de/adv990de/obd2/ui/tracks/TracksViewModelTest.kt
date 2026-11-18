package de.adv990de.obd2.ui.tracks

import androidx.lifecycle.ViewModel
import de.adv990de.obd2.data.TrackRepository
import de.adv990de.obd2.track.RecordingState
import de.adv990de.obd2.track.StartResult
import de.adv990de.obd2.track.TrackRecorder
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.TrackSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Nur erfundene Tracks, keine Koordinaten. */
@OptIn(ExperimentalCoroutinesApi::class)
class TracksViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun summary(id: Long, name: String = "Track $id", running: Boolean = false) = TrackSummary(
        id = id, name = name, source = TrackSource.RECORDED, startMs = 1_000L * id, endMs = 2_000L * id, sessionId = null,
        distanceM = 1_000L * id, ascentM = null, descentM = null, pointCount = 10, createdMs = 1_000L * id, running = running,
    )

    private class Fixture(
        val tracks: MutableStateFlow<List<TrackSummary>>,
        val recording: MutableStateFlow<RecordingState>,
        val repo: TrackRepository,
        val recorder: TrackRecorder,
        val vm: TracksViewModel,
    )

    private fun fixture(vararg initial: TrackSummary, activeId: Long? = null): Fixture {
        val tracks = MutableStateFlow(initial.toList())
        val recording = MutableStateFlow<RecordingState>(RecordingState.Idle)
        val repo = mockk<TrackRepository> {
            every { this@mockk.tracks } returns tracks
            every { activeTrackId } returns activeId
            coEvery { delete(any()) } answers {
                val ids = firstArg<Collection<Long>>().toSet()
                tracks.value = tracks.value.filter { it.id !in ids }
            }
            coEvery { rename(any(), any()) } returns Unit
        }
        val recorder = mockk<TrackRecorder> {
            every { state } returns recording
            coEvery { start(any()) } returns StartResult.Started
            coEvery { stop() } returns Unit
        }
        val vm = TracksViewModel(repo, recorder, CoroutineScope(testDispatcher + Job()))
        return Fixture(tracks, recording, repo, recorder, vm)
    }

    private fun TestScope.collect(vm: TracksViewModel) {
        backgroundScope.launch { vm.uiState.collect {} }
    }

    private fun TracksViewModel.leaveScreen() {
        val onCleared = ViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(this)
    }

    // NOTE: Tests innerhalb des Rueckgaengig-Fensters nutzen runCurrent(), nie advanceUntilIdle().

    // ── Liste ─────────────────────────────────────────────────────────────────

    @Test
    fun `before the first emission the list is unknown, not empty`() = runTest {
        val repo = mockk<TrackRepository> {
            every { tracks } returns flow { kotlinx.coroutines.awaitCancellation() }
            every { activeTrackId } returns null
        }
        val recorder = mockk<TrackRecorder> { every { state } returns MutableStateFlow(RecordingState.Idle) }
        val vm = TracksViewModel(repo, recorder, CoroutineScope(testDispatcher + Job()))
        collect(vm)
        runCurrent()

        assertNull(vm.uiState.value.tracks)
    }

    @Test
    fun `the list is what the repository gives without the running recording`() = runTest {
        val f = fixture(summary(3), summary(2, running = true), summary(1))
        collect(f.vm)
        runCurrent()

        assertEquals(listOf(3L, 1L), f.vm.uiState.value.tracks!!.map { it.id })
    }

    @Test
    fun `the state of the recorder is passed on`() = runTest {
        val f = fixture(summary(1))
        collect(f.vm)
        runCurrent()
        val state = RecordingState.Recording(trackId = 9, points = 5, distanceM = 120.0, startedMs = 1L, signal = true, writeFailed = false)

        f.recording.value = state
        runCurrent()

        assertEquals(state, f.vm.uiState.value.recording)
    }

    @Test
    fun `the recording state alone is available without observing the list`() = runTest {
        val f = fixture(summary(1))
        val state = RecordingState.Recording(trackId = 9, points = 5, distanceM = 120.0, startedMs = 1L, signal = true, writeFailed = false)

        f.recording.value = state

        assertEquals(state, f.vm.recording.value)
    }

    // ── Loeschen mit Rueckgaengig ─────────────────────────────────────────────

    @Test
    fun `deleting hides the track at once and undo brings it back`() = runTest {
        val f = fixture(summary(2), summary(1))
        collect(f.vm)
        runCurrent()

        f.vm.delete(2)
        runCurrent()
        assertEquals(listOf(1L), f.vm.uiState.value.tracks!!.map { it.id })
        assertTrue(f.vm.undoBatchId.value != null)

        f.vm.undoDelete()
        runCurrent()
        assertEquals(listOf(2L, 1L), f.vm.uiState.value.tracks!!.map { it.id })
        coVerify(exactly = 0) { f.repo.delete(any()) }
    }

    @Test
    fun `the deletion happens after the undo window`() = runTest {
        val f = fixture(summary(2), summary(1))
        collect(f.vm)
        runCurrent()

        f.vm.delete(2)
        runCurrent()
        coVerify(exactly = 0) { f.repo.delete(any()) }
        testScheduler.advanceTimeBy(10_001)
        runCurrent()

        coVerify(exactly = 1) { f.repo.delete(setOf(2L)) }
    }

    @Test
    fun `the running recording cannot be deleted`() = runTest {
        val f = fixture(summary(2, running = true), summary(1), activeId = 2)
        collect(f.vm)
        runCurrent()

        f.vm.delete(2)
        testScheduler.advanceTimeBy(11_000)
        runCurrent()

        assertNull(f.vm.undoBatchId.value)
        coVerify(exactly = 0) { f.repo.delete(any()) }
    }

    @Test
    fun `leaving the screen commits an open deletion at once`() = runTest {
        val f = fixture(summary(2), summary(1))
        collect(f.vm)
        runCurrent()
        f.vm.delete(2)
        runCurrent()

        f.vm.leaveScreen()
        runCurrent()

        coVerify(exactly = 1) { f.repo.delete(setOf(2L)) }
    }

    // ── Umbenennen ────────────────────────────────────────────────────────────

    @Test
    fun `renaming trims the name and hands it to the repository`() = runTest {
        val f = fixture(summary(1))

        f.vm.rename(1, "  Sonntagsrunde  ")
        runCurrent()

        coVerify(exactly = 1) { f.repo.rename(1, "Sonntagsrunde") }
        assertNull(f.vm.message.value)
    }

    @Test
    fun `a blank name is not saved`() = runTest {
        val f = fixture(summary(1))

        f.vm.rename(1, "   ")
        runCurrent()

        coVerify(exactly = 0) { f.repo.rename(any(), any()) }
    }

    @Test
    fun `a failing rename says so`() = runTest {
        val f = fixture(summary(1))
        coEvery { f.repo.rename(any(), any()) } throws IllegalStateException("disk")

        f.vm.rename(1, "Neu")
        runCurrent()

        assertEquals(TracksMessage.RenameFailed, f.vm.message.value)
    }

    @Test
    fun `a cancelled rename is not an error`() = runTest {
        val f = fixture(summary(1))
        coEvery { f.repo.rename(any(), any()) } throws CancellationException("gone")

        f.vm.rename(1, "Neu")
        runCurrent()

        assertNull(f.vm.message.value)
    }

    // ── Start und Stopp ───────────────────────────────────────────────────────

    @Test
    fun `starting hands over the name and says nothing when it works`() = runTest {
        val f = fixture()

        f.vm.startRecording("Runde")
        runCurrent()

        coVerify(exactly = 1) { f.recorder.start("Runde") }
        assertNull(f.vm.message.value)
    }

    @Test
    fun `a start without a name leaves the naming to the recorder`() = runTest {
        val f = fixture()

        f.vm.startRecording(null)
        runCurrent()

        coVerify(exactly = 1) { f.recorder.start(null) }
    }

    @Test
    fun `every refused start has its own message`() = runTest {
        val expected = mapOf(
            StartResult.NoPermission to TracksMessage.NoPermission,
            StartResult.NoLocationService to TracksMessage.NoLocationService,
            StartResult.Failed to TracksMessage.StartFailed,
        )
        for ((result, message) in expected) {
            val f = fixture()
            coEvery { f.recorder.start(any()) } returns result
            f.vm.startRecording(null)
            runCurrent()
            assertEquals(result.name, message, f.vm.message.value)
        }
    }

    @Test
    fun `a second start while recording says nothing`() = runTest {
        val f = fixture()
        coEvery { f.recorder.start(any()) } returns StartResult.AlreadyRunning

        f.vm.startRecording(null)
        runCurrent()

        assertNull(f.vm.message.value)
    }

    @Test
    fun `an exception from the recorder is a failed start, not a crash`() = runTest {
        val f = fixture()
        coEvery { f.recorder.start(any()) } throws IllegalStateException("boom")

        f.vm.startRecording(null)
        runCurrent()

        assertEquals(TracksMessage.StartFailed, f.vm.message.value)
    }

    @Test
    fun `stopping asks the recorder`() = runTest {
        val f = fixture()

        f.vm.stopRecording()
        runCurrent()

        coVerify(exactly = 1) { f.recorder.stop() }
    }

    @Test
    fun `a failing stop says so, a cancelled one does not`() = runTest {
        val f = fixture()
        coEvery { f.recorder.stop() } throws IllegalStateException("boom")
        f.vm.stopRecording()
        runCurrent()
        assertEquals(TracksMessage.StopFailed, f.vm.message.value)

        val g = fixture()
        coEvery { g.recorder.stop() } throws CancellationException("gone")
        g.vm.stopRecording()
        runCurrent()
        assertNull(g.vm.message.value)
    }

    @Test
    fun `a message stays until it is consumed`() = runTest {
        val f = fixture()
        coEvery { f.recorder.start(any()) } returns StartResult.NoPermission
        f.vm.startRecording(null)
        runCurrent()
        assertEquals(TracksMessage.NoPermission, f.vm.message.value)

        f.vm.consumeMessage()

        assertNull(f.vm.message.value)
    }
}
