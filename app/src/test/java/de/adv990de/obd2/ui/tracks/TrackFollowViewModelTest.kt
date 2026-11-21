package de.adv990de.obd2.ui.tracks

import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.data.TrackRepository
import de.adv990de.obd2.location.LocationSpeedProvider
import de.adv990de.obd2.location.TrackFix
import de.adv990de.obd2.location.positionUpdates
import de.adv990de.obd2.track.FollowDirection
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Nur erfundene Koordinaten (um 50 N, 10 E). */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackFollowViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val metresPerDegLat = 111_195.0

    private fun at(northM: Double, eastM: Double = 0.0) =
        TrackPoint(null, 50.0 + northM / metresPerDegLat, 10.0 + eastM / (metresPerDegLat * Math.cos(Math.toRadians(50.0))), null, null, null)

    private fun fix(northM: Double, accuracyM: Float? = 5f, eastM: Double = 0.0): TrackFix {
        val p = at(northM, eastM)
        return TrackFix(timeMs = 1_000L, elapsedMs = 1_000L, lat = p.lat, lon = p.lon, altitudeM = null, accuracyM = accuracyM, speedKmh = null)
    }

    private fun summary(id: Long) = TrackSummary(
        id = id, name = "Track $id", source = TrackSource.RECORDED, startMs = 1_000L, endMs = 61_000L, sessionId = null,
        distanceM = 1_000, ascentM = 0, descentM = 0, pointCount = 11, createdMs = 1_000L, running = false,
    )

    /** Ein Ortungsdienst, dessen Fixe der Test von Hand einspielt. */
    private class FakeLocation(var granted: Boolean = true, var registrable: Boolean = true) : LocationSpeedProvider {
        var listener: ((TrackFix?) -> Unit)? = null
        var registrations = 0
        var unregistrations = 0
        var lastIntervalMs = 0L
        override fun hasPermission() = granted
        override fun startUpdates(onSpeed: (Float?) -> Unit): (() -> Unit)? = null
        override fun startPositionUpdates(minIntervalMs: Long, onFix: (TrackFix?) -> Unit): (() -> Unit)? {
            if (!registrable) return null
            registrations++
            lastIntervalMs = minIntervalMs
            listener = onFix
            return { unregistrations++; listener = null }
        }
        fun emit(fix: TrackFix?) = listener!!.invoke(fix)
    }

    private fun vm(
        id: Long? = 5,
        location: FakeLocation = FakeLocation(),
        points: List<TrackPoint> = List(11) { at(it * 100.0) },
        tracks: List<TrackSummary> = listOf(summary(5)),
        handle: SavedStateHandle = SavedStateHandle(if (id == null) emptyMap() else mapOf(TRACK_ID_ARG to id)),
        loadPoints: suspend () -> List<TrackPoint> = { points },
    ): TrackFollowViewModel {
        val repo = mockk<TrackRepository> {
            every { this@mockk.tracks } returns MutableStateFlow(tracks)
            coEvery { points(any()) } coAnswers { loadPoints() }
        }
        return TrackFollowViewModel(repo, location, handle)
    }

    private suspend fun TrackFollowViewModel.loaded(): FollowTrackState = track.first { it !is FollowTrackState.Loading }

    // ── Laden ─────────────────────────────────────────────────────────────────

    @Test
    fun `a known track becomes ready with all its points for the line and fewer for drawing`() = runTest {
        val v = vm(points = List(3_000) { at(it * 1.0) }, tracks = listOf(summary(5)))
        runCurrent()
        val state = v.loaded() as FollowTrackState.Ready
        assertEquals("Track 5", state.name)
        assertEquals("the line follows every point", 3_000, state.points.size)
        assertTrue("drawing is thinned", state.drawn.size <= TRACK_DRAW_POINTS)
        assertEquals(50.0, state.bounds.minLat, 1e-9)
    }

    @Test
    fun `an unknown track and a missing argument are not found`() = runTest {
        val unknown = vm(id = 9)
        runCurrent()
        assertEquals(FollowTrackState.NotFound, unknown.loaded())
        val none = vm(id = null)
        runCurrent()
        assertEquals(FollowTrackState.NotFound, none.loaded())
    }

    @Test
    fun `a track without two different points is too short`() = runTest {
        val v = vm(points = listOf(at(0.0), at(0.0)))
        runCurrent()
        assertEquals(FollowTrackState.TooShort("Track 5"), v.loaded())
        val empty = vm(points = emptyList())
        runCurrent()
        assertEquals(FollowTrackState.TooShort("Track 5"), empty.loaded())
    }

    @Test
    fun `a failing read is failed`() = runTest {
        val v = vm(loadPoints = { throw java.io.IOException("disk") })
        runCurrent()
        assertEquals(FollowTrackState.Failed, v.loaded())
    }

    // ── Richtung ──────────────────────────────────────────────────────────────

    @Test
    fun `the direction starts forward and is kept in the saved state`() = runTest {
        val handle = SavedStateHandle(mapOf(TRACK_ID_ARG to 5L))
        val v = vm(handle = handle)
        assertEquals(FollowDirection.FORWARD, v.direction.value)
        v.setDirection(FollowDirection.REVERSE)
        assertEquals(FollowDirection.REVERSE, v.direction.value)
        assertEquals(true, handle.get<Boolean>(FOLLOW_REVERSE_KEY))
        v.setDirection(FollowDirection.FORWARD)
        assertEquals(false, handle.get<Boolean>(FOLLOW_REVERSE_KEY))

        val restored = vm(handle = SavedStateHandle(mapOf(TRACK_ID_ARG to 5L, FOLLOW_REVERSE_KEY to true)))
        assertEquals(FollowDirection.REVERSE, restored.direction.value)
    }

    // ── Position ──────────────────────────────────────────────────────────────

    @Test
    fun `a fix on the track gives distance progress and rest`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        assertEquals(FollowReading.Searching, v.reading.value)

        location.emit(fix(250.0))
        runCurrent()
        val r = (v.reading.value as FollowReading.Following).result
        assertEquals(0.0, r.distanceToTrackM, 1.0)
        assertEquals(250.0, r.alongM, 2.0)
        assertEquals(750.0, r.remainingM, 2.0)
        job.cancel()
    }

    @Test
    fun `the reverse direction counts from the other end`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location)
        runCurrent()
        v.setDirection(FollowDirection.REVERSE)
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        location.emit(fix(250.0))
        runCurrent()
        val r = (v.reading.value as FollowReading.Following).result
        assertEquals(750.0, r.alongM, 2.0)
        job.cancel()
    }

    @Test
    fun `changing the direction starts the tracker over`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        location.emit(fix(250.0))
        runCurrent()
        v.setDirection(FollowDirection.REVERSE)
        runCurrent()
        assertEquals("a new tracker waits for its first fix", FollowReading.Searching, v.reading.value)
        location.emit(fix(250.0))
        runCurrent()
        assertEquals(750.0, (v.reading.value as FollowReading.Following).result.alongM, 2.0)
        job.cancel()
    }

    @Test
    fun `a fix with poor accuracy is ignored and one at the limit counts`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        location.emit(fix(250.0, accuracyM = FOLLOW_MAX_ACCURACY_M + 1f))
        runCurrent()
        assertEquals(FollowReading.Searching, v.reading.value)
        location.emit(fix(250.0, accuracyM = FOLLOW_MAX_ACCURACY_M))
        runCurrent()
        assertTrue(v.reading.value is FollowReading.Following)
        location.emit(fix(300.0, accuracyM = null))
        runCurrent()
        assertEquals(300.0, (v.reading.value as FollowReading.Following).result.alongM, 2.0)
        job.cancel()
    }

    @Test
    fun `a poor fix keeps the last good reading`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        location.emit(fix(250.0))
        runCurrent()
        location.emit(fix(600.0, accuracyM = 500f))
        runCurrent()
        assertEquals(250.0, (v.reading.value as FollowReading.Following).result.alongM, 2.0)
        job.cancel()
    }

    @Test
    fun `a switched off provider means searching again`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        location.emit(fix(250.0))
        runCurrent()
        location.emit(null)
        runCurrent()
        assertEquals(FollowReading.Searching, v.reading.value)
        job.cancel()
    }

    @Test
    fun `a provider that cannot be registered means searching`() = runTest {
        val location = FakeLocation(registrable = false)
        val v = vm(location = location)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        assertEquals(FollowReading.Searching, v.reading.value)
        job.cancel()
    }

    // ── Berechtigung ──────────────────────────────────────────────────────────

    @Test
    fun `without permission nothing is registered and the page says so`() = runTest {
        val location = FakeLocation(granted = false)
        val v = vm(location = location)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        assertEquals(FollowReading.NoPermission, v.reading.value)
        assertEquals(0, location.registrations)
        job.cancel()
    }

    @Test
    fun `a permission granted later starts the position updates`() = runTest {
        val location = FakeLocation(granted = false)
        val v = vm(location = location)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        location.granted = true
        v.refreshPermission()
        runCurrent()
        assertEquals(1, location.registrations)
        assertEquals(FollowReading.Searching, v.reading.value)
        job.cancel()
    }

    @Test
    fun `refreshing without a change does not start a second listener`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        v.refreshPermission()
        v.refreshPermission()
        runCurrent()
        assertEquals(1, location.registrations)
        job.cancel()
    }

    // ── Lebensdauer des Listeners ─────────────────────────────────────────────

    @Test
    fun `the listener runs once per second at most and only while someone collects`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location)
        runCurrent()
        assertEquals("nobody collects yet", 0, location.registrations)
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        assertEquals(1, location.registrations)
        assertEquals(FOLLOW_MIN_INTERVAL_MS, location.lastIntervalMs)

        job.cancel()
        runCurrent()
        assertEquals("still there during the grace period", 0, location.unregistrations)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, location.unregistrations)
    }

    @Test
    fun `without a loaded track no listener is registered`() = runTest {
        val location = FakeLocation()
        val v = vm(id = 9, location = location)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        assertEquals(FollowReading.Searching, v.reading.value)
        assertEquals(0, location.registrations)
        job.cancel()
    }

    // ── Positionsfluss ────────────────────────────────────────────────────────

    @Test
    fun `the position flow registers on collect and unregisters on cancel`() = runTest {
        val location = FakeLocation()
        val seen = mutableListOf<TrackFix?>()
        val job = backgroundScope.launch { location.positionUpdates(1_000L).collect { seen += it } }
        runCurrent()
        assertEquals(1, location.registrations)
        location.emit(fix(10.0))
        location.emit(null)
        runCurrent()
        assertEquals(2, seen.size)
        assertNull(seen[1])
        job.cancel()
        runCurrent()
        assertEquals(1, location.unregistrations)
    }

    @Test
    fun `the position flow reports a failed registration once as null`() = runTest {
        val location = FakeLocation(registrable = false)
        val seen = mutableListOf<TrackFix?>()
        val job = backgroundScope.launch { location.positionUpdates(1_000L).collect { seen += it } }
        runCurrent()
        assertEquals(listOf<TrackFix?>(null), seen)
        job.cancel()
    }

    @Test
    fun `the abort of a load is not turned into failed`() = runTest {
        val v = vm(loadPoints = { throw CancellationException("gone") })
        runCurrent()
        assertFalse(v.track.value is FollowTrackState.Failed)
    }
}
