package de.adv990de.obd2.track

import de.adv990de.obd2.data.TrackRepository
import de.adv990de.obd2.location.LocationSpeedProvider
import de.adv990de.obd2.location.TrackFix
import de.adv990de.obd2.location.positionUpdates
import de.adv990de.obd2.fakeAppPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Nur erfundene Koordinaten (um 50 N, 10 E). */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowSessionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        scope = CoroutineScope(testDispatcher + SupervisorJob())
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private val metresPerDegLat = 111_195.0

    private fun at(northM: Double, eastM: Double = 0.0) =
        TrackPoint(null, 50.0 + northM / metresPerDegLat, 10.0 + eastM / (metresPerDegLat * Math.cos(Math.toRadians(50.0))), null, null, null)

    private var clockMs = 1_000L

    private fun fix(northM: Double, accuracyM: Float? = 5f, eastM: Double = 0.0, speedKmh: Float? = null): TrackFix {
        val p = at(northM, eastM)
        clockMs += 1_000L
        return TrackFix(timeMs = clockMs, elapsedMs = clockMs, lat = p.lat, lon = p.lon, altitudeM = null, accuracyM = accuracyM, speedKmh = speedKmh)
    }

    private fun northThen(bearingDeg: Double, northM: Double = 1_000.0, thenM: Double = 1_000.0): List<TrackPoint> {
        val r = Math.toRadians(bearingDeg)
        return List(11) { at(it * northM / 10, 0.0) } + List(10) { at(northM + (it + 1) * thenM / 10 * Math.cos(r), (it + 1) * thenM / 10 * Math.sin(r)) }
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
        roadbookEnabled: Boolean = true,
        roadbookSensitivity: Int = 2,
        loadPoints: suspend () -> List<TrackPoint> = { points },
    ): FollowSession {
        val repo = mockk<TrackRepository> {
            every { this@mockk.tracks } returns MutableStateFlow(tracks)
            coEvery { points(any()) } coAnswers { loadPoints() }
        }
        return FollowSession(repo, location, fakeAppPreferences(roadbookEnabled = roadbookEnabled, roadbookSensitivity = roadbookSensitivity), scope)
            .also { s ->
                s.computeDispatcher = testDispatcher
                if (id != null) s.start(id)
            }
    }

    private suspend fun FollowSession.loaded(): FollowTrackState = track.first { it != null && it !is FollowTrackState.Loading }!!

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
    fun `an unknown track is not found and is not followed`() = runTest {
        val unknown = vm(id = 9)
        runCurrent()
        assertEquals(FollowTrackState.NotFound, unknown.loaded())
        assertNull("a track that cannot be followed is no session for the button", unknown.info.value)
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
    fun `the direction starts forward and a new track starts forward again`() = runTest {
        val v = vm()
        runCurrent()
        assertEquals(FollowDirection.FORWARD, v.direction.value)
        v.setDirection(FollowDirection.REVERSE)
        assertEquals(FollowDirection.REVERSE, v.direction.value)
        v.start(6)
        assertEquals(FollowDirection.FORWARD, v.direction.value)
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

    // ── Sitzung ───────────────────────────────────────────────────────────────

    @Test
    fun `no session exists before a start and the reading is searching`() = runTest {
        val location = FakeLocation()
        val v = vm(id = null, location = location)
        runCurrent()
        assertNull(v.track.value)
        assertNull(v.info.value)
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        assertEquals(FollowReading.Searching, v.reading.value)
        assertEquals("no session, no position updates", 0, location.registrations)
        job.cancel()
    }

    @Test
    fun `the session knows whom it follows once the track is ready`() = runTest {
        val v = vm()
        runCurrent()
        v.loaded()
        assertEquals(FollowInfo(5, "Track 5"), v.info.value)
    }

    @Test
    fun `starting the same track again keeps progress and direction`() = runTest {
        val v = vm()
        runCurrent()
        v.loaded()
        v.setDirection(FollowDirection.REVERSE)
        val before = v.track.value
        v.start(5)
        runCurrent()
        assertEquals(FollowDirection.REVERSE, v.direction.value)
        assertTrue("the loaded track stays the same instance", before === v.track.value)
    }

    @Test
    fun `starting another track begins again`() = runTest {
        val v = vm(tracks = listOf(summary(5), summary(6)))
        runCurrent()
        v.loaded()
        v.setDirection(FollowDirection.REVERSE)
        v.start(6)
        assertEquals(FollowTrackState.Loading, v.track.value)
        runCurrent()
        assertEquals("Track 6", (v.loaded() as FollowTrackState.Ready).name)
        assertEquals(FollowDirection.FORWARD, v.direction.value)
    }

    @Test
    fun `a track that failed to load is tried again by a new start`() = runTest {
        var fail = true
        val v = vm(loadPoints = { if (fail) throw java.io.IOException("disk") else List(11) { at(it * 100.0) } })
        runCurrent()
        assertEquals(FollowTrackState.Failed, v.loaded())
        fail = false
        v.start(5)
        runCurrent()
        assertTrue(v.loaded() is FollowTrackState.Ready)
    }

    @Test
    fun `stopping ends the session and the listener`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        location.emit(fix(250.0))
        runCurrent()
        assertTrue(v.reading.value is FollowReading.Following)
        v.setDirection(FollowDirection.REVERSE)
        v.stop()
        runCurrent()
        assertNull(v.track.value)
        assertNull(v.info.value)
        assertEquals(FollowDirection.FORWARD, v.direction.value)
        assertEquals(FollowReading.Searching, v.reading.value)
        assertEquals("the position updates were given back", location.registrations, location.unregistrations)
        job.cancel()
    }

    @Test
    fun `the progress on the line survives all collectors going away`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location)
        runCurrent()
        var job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        location.emit(fix(250.0))
        runCurrent()
        job.cancel()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, location.unregistrations)
        job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        // Ein kleiner Schritt zurueck senkt den Fortschritt nicht — das gilt nur fuer denselben Verfolger.
        location.emit(fix(240.0))
        runCurrent()
        assertEquals(250.0, (v.reading.value as FollowReading.Following).result.alongM, 2.0)
        job.cancel()
    }

    // ── Geschwindigkeit, Zoom, Roadbook ───────────────────────────────────────

    @Test
    fun `a reading carries the speed and the span of the map`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        location.emit(fix(250.0, speedKmh = 60f))
        runCurrent()
        val r = v.reading.value as FollowReading.Following
        assertEquals(60f, r.speedKmh)
        assertEquals(autoZoomTargetSpanM(60f), r.autoSpanM!!, 0.001)
        job.cancel()
    }

    @Test
    fun `the roadbook shows shortly before a turn and not far away`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location, points = northThen(90.0))
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        location.emit(fix(600.0, speedKmh = 0f))
        runCurrent()
        assertNull("400 m before the corner is too far", (v.reading.value as FollowReading.Following).hud)
        location.emit(fix(900.0, speedKmh = 0f))
        runCurrent()
        val hud = (v.reading.value as FollowReading.Following).hud
        assertNotNull(hud)
        assertEquals(1_000.0, hud!!.maneuver.apexAlongM, 40.0)
        assertTrue(hud.maneuver.isRight)
        job.cancel()
    }

    @Test
    fun `the roadbook is off when the setting is off`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location, points = northThen(90.0), roadbookEnabled = false)
        runCurrent()
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        location.emit(fix(900.0, speedKmh = 0f))
        runCurrent()
        assertNull((v.reading.value as FollowReading.Following).hud)
        job.cancel()
    }

    @Test
    fun `the sensitivity setting decides which turns the roadbook shows`() = runTest {
        val onlySharp = FakeLocation()
        val sharp = vm(location = onlySharp, points = northThen(70.0), roadbookSensitivity = 1)
        runCurrent()
        val j1 = backgroundScope.launch { sharp.reading.collect { } }
        runCurrent()
        onlySharp.emit(fix(900.0, speedKmh = 0f))
        runCurrent()
        assertNull("a 70 degree turn is below the sharp-only threshold", (sharp.reading.value as FollowReading.Following).hud)
        j1.cancel()

        val standardLocation = FakeLocation()
        val standard = vm(location = standardLocation, points = northThen(70.0), roadbookSensitivity = 2)
        runCurrent()
        val j2 = backgroundScope.launch { standard.reading.collect { } }
        runCurrent()
        standardLocation.emit(fix(900.0, speedKmh = 0f))
        runCurrent()
        assertNotNull((standard.reading.value as FollowReading.Following).hud)
        j2.cancel()
    }

    @Test
    fun `the roadbook is reversed with the line`() = runTest {
        val location = FakeLocation()
        val v = vm(location = location, points = northThen(90.0))
        runCurrent()
        v.setDirection(FollowDirection.REVERSE)
        val job = backgroundScope.launch { v.reading.collect { } }
        runCurrent()
        // Rueckwaerts startet man am Ende (Osten) und faehrt nach Westen; die Ecke liegt nach 1000 m, 100 m davor steht man bei Osten = 100.
        location.emit(fix(1_000.0, eastM = 100.0, speedKmh = 0f))
        runCurrent()
        val hud = (v.reading.value as FollowReading.Following).hud
        assertNotNull(hud)
        assertTrue("right becomes left when driven the other way", !hud!!.maneuver.isRight)
        job.cancel()
    }
}
