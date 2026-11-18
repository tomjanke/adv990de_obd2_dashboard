package de.adv990de.obd2.ui.tracks

import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.data.TrackRepository
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.TrackSummary
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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

    private fun vm(id: String?, tracks: List<TrackSummary>, points: suspend () -> List<TrackPoint>): TrackDetailViewModel {
        val repo = mockk<TrackRepository> {
            every { this@mockk.tracks } returns MutableStateFlow(tracks)
            coEvery { points(any()) } coAnswers { points() }
        }
        val handle = SavedStateHandle(if (id == null) emptyMap() else mapOf(TRACK_ID_ARG to id.toLong()))
        return TrackDetailViewModel(repo, handle)
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
}
