package de.adv990de.obd2.ui.tracks

import androidx.lifecycle.SavedStateHandle
import android.net.Uri
import de.adv990de.obd2.data.TrackExporter
import de.adv990de.obd2.data.TrackRepository
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.TrackSummary
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Nur erfundene Koordinaten. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun summary(id: Long) = TrackSummary(
        id = id, name = "Track $id", source = TrackSource.RECORDED, startMs = 1_000L, endMs = 61_000L, sessionId = null,
        distanceM = 2_500, ascentM = 40, descentM = 35, pointCount = 3, createdMs = 1_000L, running = false,
    )

    private fun point(i: Int) = TrackPoint(null, 50.0 + i * 0.0001, 10.0 + i * 0.0002, null, null, null)

    private val exporter = mockk<TrackExporter>()

    private fun vm(id: String?, tracks: List<TrackSummary>, points: suspend () -> List<TrackPoint>): TrackDetailViewModel {
        val repo = mockk<TrackRepository> {
            every { this@mockk.tracks } returns MutableStateFlow(tracks)
            coEvery { points(any()) } coAnswers { points() }
        }
        val handle = SavedStateHandle(if (id == null) emptyMap() else mapOf(TRACK_ID_ARG to id.toLong()))
        return TrackDetailViewModel(repo, exporter, handle)
    }

    /** Das Ausduennen laeuft auf einem echten Thread (`Dispatchers.Default`): `runCurrent()` allein wartet darauf nicht. */
    private suspend fun TrackDetailViewModel.loaded(): TrackDetailState {
        return state.first { it !is TrackDetailState.Loading }
    }

    @Test
    fun `a known track becomes ready with its numbers and bounds`() = runTest {
        val v = vm("5", listOf(summary(4), summary(5))) { List(3) { point(it) } }
        runCurrent()

        val state = v.loaded() as TrackDetailState.Ready
        assertEquals(5L, state.summary.id)
        assertEquals(3, state.points.size)
        assertEquals(50.0, state.bounds!!.minLat, 1e-9)
        assertEquals(50.0002, state.bounds!!.maxLat, 1e-9)
    }

    @Test
    fun `an unknown track is not found`() = runTest {
        val v = vm("9", listOf(summary(4))) { emptyList() }
        runCurrent()

        assertEquals(TrackDetailState.NotFound, v.state.value)
    }

    @Test
    fun `no id at all is not found`() = runTest {
        val v = vm(null, listOf(summary(4))) { emptyList() }
        runCurrent()

        assertEquals(TrackDetailState.NotFound, v.state.value)
    }

    @Test
    fun `a track without points is ready but empty, not an error`() = runTest {
        val v = vm("5", listOf(summary(5))) { emptyList() }
        runCurrent()

        val state = v.loaded() as TrackDetailState.Ready
        assertTrue(state.points.isEmpty())
        assertEquals(null, state.bounds)
    }

    @Test
    fun `a failing read is failed, a cancelled read is not`() = runTest {
        val failing = vm("5", listOf(summary(5))) { throw IllegalStateException("disk") }
        runCurrent()
        assertEquals(TrackDetailState.Failed, failing.state.value)

        val cancelled = vm("5", listOf(summary(5))) { throw CancellationException("gone") }
        runCurrent()
        assertEquals(TrackDetailState.Loading, cancelled.state.value)
    }

    @Test
    fun `a long track is thinned for drawing but the bounds come from all points`() = runTest {
        val all = List(10_000) { point(it) }
        val v = vm("5", listOf(summary(5))) { all }
        runCurrent()

        val state = v.loaded() as TrackDetailState.Ready
        assertTrue(state.points.size <= TRACK_DRAW_POINTS)
        assertEquals(all.first(), state.points.first())
        assertEquals(all.last(), state.points.last())
        assertEquals(all.last().lat, state.bounds!!.maxLat, 1e-9)
    }

    @Test
    fun `while reading it is loading`() = runTest {
        val v = vm("5", listOf(summary(5))) { List(3) { point(it) } }
        assertEquals(TrackDetailState.Loading, v.state.value)

        // runTest arbeitet am Ende die Aufgaben des Main-Dispatchers ab: die Arbeit auf dem echten Thread muss davor fertig sein, sonst endet sie nach `resetMain`.
        runCurrent()
        v.loaded()
    }

    @Test
    fun `the bounds include every extreme of the track even when the drawing drops it`() = runTest {
        // zehn Perioden einer Sinuskurve: die Zeichnung behaelt wenige Punkte, die Grenzen muessen aus allen kommen (Hoch- und Tiefpunkt)
        val all = List(10_000) { i -> TrackPoint(null, 50.0 + 0.003 * Math.sin(i / 10_000.0 * 2 * Math.PI * 10), 10.0 + i * 0.00001, null, null, null) }
        val v = vm("5", listOf(summary(5))) { all }
        runCurrent()

        val state = v.loaded() as TrackDetailState.Ready
        assertEquals(all.minOf { it.lat }, state.bounds!!.minLat, 1e-12)
        assertEquals(all.maxOf { it.lat }, state.bounds!!.maxLat, 1e-12)
    }

    // ── GPX-Export (Task 7) ───────────────────────────────────────────────────

    private fun summaryWithPoints(id: Long, pointCount: Int) = summary(id).copy(pointCount = pointCount)

    private suspend fun TrackDetailViewModel.ready() = also { loaded() }

    @Test
    fun `share exports the shown track and hands its uri to the feedback`() = runTest {
        val uri = mockk<Uri>()
        coEvery { exporter.exportToGpx(5) } returns uri
        val v = vm("5", listOf(summary(4), summary(5))) { List(3) { point(it) } }.ready()

        v.share()
        runCurrent()

        assertEquals(uri, v.exportFeedback.shareUri.value)
        coVerify(exactly = 1) { exporter.exportToGpx(5) }
    }

    @Test
    fun `a track without points says nothing to export and writes no file`() = runTest {
        val v = vm("5", listOf(summaryWithPoints(5, 0))) { emptyList() }.ready()

        v.share()
        runCurrent()
        v.saveTo(mockk())
        runCurrent()

        assertTrue(v.exportFeedback.nothingToExport.value)
        coVerify(exactly = 0) { exporter.exportToGpx(any()) }
        coVerify(exactly = 0) { exporter.saveGpx(any(), any()) }
    }

    @Test
    fun `saveTo writes into the chosen target and reports saved only afterwards`() = runTest {
        val target = mockk<Uri>()
        coEvery { exporter.saveGpx(target, 5) } returns Unit
        val v = vm("5", listOf(summary(5))) { List(3) { point(it) } }.ready()
        assertEquals(false, v.saved.value)

        v.saveTo(target)
        runCurrent()

        coVerify(exactly = 1) { exporter.saveGpx(target, 5) }
        assertEquals(true, v.saved.value)
        v.consumeSaved()
        assertEquals(false, v.saved.value)
    }

    @Test
    fun `a failing save shows the error and does not report saved`() = runTest {
        coEvery { exporter.saveGpx(any(), any()) } throws java.io.IOException("The chosen file could not be opened for writing")
        val v = vm("5", listOf(summary(5))) { List(3) { point(it) } }.ready()

        v.saveTo(mockk())
        runCurrent()

        assertEquals("The chosen file could not be opened for writing", v.exportFeedback.error.value)
        assertEquals(false, v.saved.value)
    }

    @Test
    fun `a failing share shows the error and publishes no uri`() = runTest {
        coEvery { exporter.exportToGpx(any()) } throws IllegalStateException("boom")
        val v = vm("5", listOf(summary(5))) { List(3) { point(it) } }.ready()

        v.share()
        runCurrent()

        assertEquals("boom", v.exportFeedback.error.value)
        assertEquals(null, v.exportFeedback.shareUri.value)
    }

    @Test
    fun `nothing is exported while the track is still loading, not found or failed`() = runTest {
        val notFound = vm("9", listOf(summary(4))) { emptyList() }
        runCurrent()
        notFound.share()
        notFound.saveTo(mockk())
        runCurrent()

        coVerify(exactly = 0) { exporter.exportToGpx(any()) }
        coVerify(exactly = 0) { exporter.saveGpx(any(), any()) }
        assertEquals(false, notFound.saved.value)
        assertEquals(null, notFound.exportFeedback.error.value)
    }

    @Test
    fun `a second tap while the first export runs is ignored`() = runTest {
        val release = kotlinx.coroutines.CompletableDeferred<Uri>()
        coEvery { exporter.exportToGpx(5) } coAnswers { release.await() }
        val v = vm("5", listOf(summary(5))) { List(3) { point(it) } }.ready()

        v.share()
        runCurrent()
        assertTrue(v.exporting.value)
        v.share()
        v.saveTo(mockk())
        runCurrent()
        release.complete(mockk())
        runCurrent()

        coVerify(exactly = 1) { exporter.exportToGpx(5) }
        coVerify(exactly = 0) { exporter.saveGpx(any(), any()) }
        assertEquals(false, v.exporting.value)
    }

    @Test
    fun `the name suggested for the file dialog is built from the track name`() = runTest {
        val v = vm("5", listOf(summary(5).copy(name = "Morgenrunde"))) { List(3) { point(it) } }.ready()

        val name = v.suggestedFileName()

        assertTrue(name, name.startsWith("track_Morgenrunde_") && name.endsWith(".gpx"))
    }

    @Test
    fun `without a loaded track the suggested name is still a valid file name`() = runTest {
        val v = vm("9", listOf(summary(4))) { emptyList() }
        runCurrent()

        val name = v.suggestedFileName()

        assertTrue(name, name.startsWith("track_unnamed_") && name.endsWith(".gpx"))
    }
}
