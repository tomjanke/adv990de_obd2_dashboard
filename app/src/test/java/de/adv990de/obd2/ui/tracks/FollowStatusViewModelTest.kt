package de.adv990de.obd2.ui.tracks

import de.adv990de.obd2.track.FollowInfo
import de.adv990de.obd2.track.FollowReading
import de.adv990de.obd2.track.FollowResult
import de.adv990de.obd2.track.FollowSession
import de.adv990de.obd2.track.FollowStatus
import de.adv990de.obd2.track.RecordingState
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackRecorder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Die Statuszeile des Nachfahrens: nur mit Sitzung, und aus der Position der Sitzung. Die Regeln selbst pruefen `FollowStatusTest`. */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowStatusViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Fixture(val info: MutableStateFlow<FollowInfo?>, val reading: MutableStateFlow<FollowReading>, val vm: FollowStatusViewModel)

    private fun fixture(info: FollowInfo? = FollowInfo(5, "Runde")): Fixture {
        val infoFlow = MutableStateFlow(info)
        val readingFlow = MutableStateFlow<FollowReading>(FollowReading.Searching)
        val session = mockk<FollowSession>(relaxed = true) {
            every { this@mockk.info } returns infoFlow
            every { reading } returns readingFlow
        }
        val recorder = mockk<TrackRecorder> { every { state } returns MutableStateFlow<RecordingState>(RecordingState.Idle) }
        return Fixture(infoFlow, readingFlow, FollowStatusViewModel(session, recorder))
    }

    private fun following(distanceM: Double, remainingM: Double = 5_000.0) = FollowReading.Following(
        position = TrackPoint(0L, 50.0, 8.0, null, null, null),
        result = FollowResult(distanceM, 100.0, remainingM, 5_100.0, 0, 0.0),
    )

    @Test
    fun `without a session there is no status`() = runTest(testDispatcher) {
        val f = fixture(info = null)
        backgroundScope.launch { f.vm.status.collect { } }
        runCurrent()
        assertNull(f.vm.status.value)
    }

    @Test
    fun `with a session and no position the status waits for the GPS`() = runTest(testDispatcher) {
        val f = fixture()
        backgroundScope.launch { f.vm.status.collect { } }
        runCurrent()
        assertEquals(FollowStatus.Waiting, f.vm.status.value)
    }

    @Test
    fun `the status follows the position of the session`() = runTest(testDispatcher) {
        val f = fixture()
        backgroundScope.launch { f.vm.status.collect { } }
        f.reading.value = following(3.0)
        runCurrent()
        assertEquals(FollowStatus.OnTrack(5_000.0), f.vm.status.value)
        f.reading.value = following(120.0)
        runCurrent()
        assertEquals(FollowStatus.OffTrack(120.0), f.vm.status.value)
    }

    @Test
    fun `the border between on and off the track has a dead zone across fixes`() = runTest(testDispatcher) {
        val f = fixture()
        backgroundScope.launch { f.vm.status.collect { } }
        f.reading.value = following(10.0)
        runCurrent()
        f.reading.value = following(27.0)
        runCurrent()
        assertEquals(FollowStatus.OnTrack(5_000.0), f.vm.status.value)
    }

    @Test
    fun `ending the session removes the status`() = runTest(testDispatcher) {
        val f = fixture()
        backgroundScope.launch { f.vm.status.collect { } }
        f.reading.value = following(3.0)
        runCurrent()
        f.info.value = null
        runCurrent()
        assertNull(f.vm.status.value)
    }

    @Test
    fun `a new session starts without the old on-track memory`() = runTest(testDispatcher) {
        val f = fixture()
        backgroundScope.launch { f.vm.status.collect { } }
        f.reading.value = following(3.0)
        runCurrent()
        f.info.value = FollowInfo(6, "Andere")
        f.reading.value = following(25.0)
        runCurrent()
        assertEquals(FollowStatus.OffTrack(25.0), f.vm.status.value)
    }

    @Test
    fun `the name arriving for the same track does not reset the status`() = runTest(testDispatcher) {
        val f = fixture(info = FollowInfo(5, null))
        backgroundScope.launch { f.vm.status.collect { } }
        f.reading.value = following(3.0)
        runCurrent()
        f.info.value = FollowInfo(5, "Runde")
        f.reading.value = following(25.0)
        runCurrent()
        assertEquals(FollowStatus.OnTrack(5_000.0), f.vm.status.value)
    }

    @Test
    fun `nothing reads the position without a collector`() = runTest(testDispatcher) {
        val f = fixture()
        runCurrent()
        // Der Fluss ist kalt: ohne Sammler bleibt der Startwert.
        assertNull(f.vm.status.value)
    }
}
