package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.crash.CrashReportingController
import de.adv990de.obd2.crash.CrashReportingStatus
import de.adv990de.obd2.data.AppPreferences
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** QS B33: der Schalter "Absturzberichte senden" und die Statuszeile der App-Info. */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashReportingSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun prefs(enabled: MutableStateFlow<Boolean>): AppPreferences = mockk {
        every { crashReportsEnabled } returns enabled
        coEvery { setCrashReportsEnabled(any()) } answers { enabled.value = firstArg(); Unit }
    }

    private fun controller(available: Boolean): CrashReportingController =
        mockk { every { isAvailable } returns available }

    @Test
    fun `available mirrors the controller - the row is only offered when a DSN is built in`() {
        assertTrue(CrashReportingSettingsViewModel(prefs(MutableStateFlow(true)), controller(true)).available)
        assertFalse(CrashReportingSettingsViewModel(prefs(MutableStateFlow(true)), controller(false)).available)
    }

    @Test
    fun `enabled reflects the preference`() = runTest {
        val vm = CrashReportingSettingsViewModel(prefs(MutableStateFlow(false)), controller(true))
        val job = launch { vm.enabled.collect { } }
        advanceUntilIdle()

        assertEquals(false, vm.enabled.value)
        job.cancel()
    }

    @Test
    fun `setEnabled writes the preference`() = runTest {
        val enabled = MutableStateFlow(true)
        val p = prefs(enabled)
        val vm = CrashReportingSettingsViewModel(p, controller(true))

        vm.setEnabled(false)
        advanceUntilIdle()

        coVerify { p.setCrashReportsEnabled(false) }
        assertEquals(false, enabled.value)
    }

    @Test
    fun `status is NotIncluded without a DSN, whatever the switch says`() = runTest {
        val vm = CrashReportingSettingsViewModel(prefs(MutableStateFlow(true)), controller(false))
        val job = launch { vm.status.collect { } }
        advanceUntilIdle()

        assertEquals(CrashReportingStatus.NotIncluded, vm.status.value)
        job.cancel()
    }

    @Test
    fun `status follows the switch when a DSN is built in`() = runTest {
        val enabled = MutableStateFlow(true)
        val vm = CrashReportingSettingsViewModel(prefs(enabled), controller(true))
        val job = launch { vm.status.collect { } }
        advanceUntilIdle()
        assertEquals(CrashReportingStatus.On, vm.status.value)

        enabled.value = false
        advanceUntilIdle()

        assertEquals(CrashReportingStatus.Off, vm.status.value)
        job.cancel()
    }
}
