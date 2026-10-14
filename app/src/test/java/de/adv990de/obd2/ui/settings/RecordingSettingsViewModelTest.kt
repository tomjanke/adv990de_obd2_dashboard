package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.fakeAppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------
    // Test 1: logOnlyDtc reflects AppPreferences flow
    // -------------------------------------------------------------------------
    @Test
    fun `logOnlyDtc reflects AppPreferences flow`() = runTest {
        val prefs = fakeAppPreferences(logOnlyDtcSessions = true)
        val vm = RecordingSettingsViewModel(prefs)

        val collected = mutableListOf<Boolean>()
        val job = launch { vm.logOnlyDtc.collect { collected.add(it) } }
        advanceUntilIdle()
        job.cancel()

        // stateIn emits the initial default (false) then the upstream value (true)
        assertEquals(true, collected.last())
        assertEquals(true, vm.logOnlyDtc.value)
    }

    // -------------------------------------------------------------------------
    // Test 3: toggleLogOnlyDtc delegates to AppPreferences with inverted value
    // State starts at false → toggle must call setLogOnlyDtcSessions(true)
    // -------------------------------------------------------------------------
    @Test
    fun `toggleLogOnlyDtc delegates to AppPreferences with inverted value`() = runTest {
        val prefs = mockk<AppPreferences> {
            every { logOnlyDtcSessions } returns flowOf(false)
            coEvery { setLogOnlyDtcSessions(any()) } just Runs
        }
        val vm = RecordingSettingsViewModel(prefs)

        val job = launch { vm.logOnlyDtc.collect {} }
        advanceUntilIdle()
        // logOnlyDtc.value is now false (upstream emitted false)

        vm.toggleLogOnlyDtc()
        advanceUntilIdle()
        job.cancel()

        coVerify { prefs.setLogOnlyDtcSessions(true) }
    }
}
