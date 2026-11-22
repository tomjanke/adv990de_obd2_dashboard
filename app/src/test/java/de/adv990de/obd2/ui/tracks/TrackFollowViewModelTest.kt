package de.adv990de.obd2.ui.tracks

import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.track.FollowDirection
import de.adv990de.obd2.track.FollowSession
import de.adv990de.obd2.track.FollowTrackState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Die Seite ist eine duenne Huelle um die Sitzung; deren Logik pruefen [de.adv990de.obd2.track.FollowSessionTest] und die Regel-Tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackFollowViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Fixture(val session: FollowSession, val track: MutableStateFlow<FollowTrackState?>, val vm: TrackFollowViewModel)

    private fun fixture(id: Long? = 5): Fixture {
        val track = MutableStateFlow<FollowTrackState?>(null)
        val session = mockk<FollowSession>(relaxed = true) {
            every { this@mockk.track } returns track
            every { direction } returns MutableStateFlow(FollowDirection.FORWARD)
        }
        val handle = SavedStateHandle(if (id == null) emptyMap() else mapOf(TRACK_ID_ARG to id))
        return Fixture(session, track, TrackFollowViewModel(session, handle))
    }

    @Test
    fun `opening the page starts the session for the track of the route`() = runTest {
        val f = fixture(id = 5)
        verify(exactly = 1) { f.session.start(5) }
    }

    @Test
    fun `a route without a valid id starts nothing and is not found`() = runTest {
        val f = fixture(id = null)
        runCurrent()
        verify(exactly = 0) { f.session.start(any()) }
        assertEquals(FollowTrackState.NotFound, f.vm.track.value)
    }

    @Test
    fun `until the session has a track the page is loading`() = runTest {
        val f = fixture()
        runCurrent()
        assertEquals(FollowTrackState.Loading, f.vm.track.value)
        f.track.value = FollowTrackState.TooShort("Runde")
        runCurrent()
        assertEquals(FollowTrackState.TooShort("Runde"), f.vm.track.value)
    }

    @Test
    fun `direction permission and stop go to the session`() = runTest {
        val f = fixture()
        f.vm.setDirection(FollowDirection.REVERSE)
        f.vm.refreshPermission()
        f.vm.stop()
        verify(exactly = 1) { f.session.setDirection(FollowDirection.REVERSE) }
        verify(exactly = 1) { f.session.refreshPermission() }
        verify(exactly = 1) { f.session.stop() }
    }
}
