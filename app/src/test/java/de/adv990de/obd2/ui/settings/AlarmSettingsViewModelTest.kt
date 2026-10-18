package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.alarm.AlarmSettings
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.fakeAppPreferences
import io.mockk.coEvery
import io.mockk.every
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmSettingsViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Die strenge Attrappe mit den sieben Schreibzugriffen, die jeden Aufruf mit Schlüssel und Wert in [writes] festhalten — so zeigt ein Test genau, WAS geschrieben wurde. */
    private fun recordingPrefs(writes: MutableList<String>, alarmSettings: AlarmSettings = AlarmSettings()): AppPreferences =
        fakeAppPreferences(alarmSettings = alarmSettings).also { prefs ->
            coEvery { prefs.setAlarmVoltageEnabled(any()) } coAnswers { writes += "voltageEnabled=${firstArg<Boolean>()}" }
            coEvery { prefs.setAlarmCoolantEnabled(any()) } coAnswers { writes += "coolantEnabled=${firstArg<Boolean>()}" }
            coEvery { prefs.setAlarmFaultsEnabled(any()) } coAnswers { writes += "faultsEnabled=${firstArg<Boolean>()}" }
            coEvery { prefs.setAlarmVoltageNoticeDeciV(any()) } coAnswers { writes += "voltageNotice=${firstArg<Int>()}" }
            coEvery { prefs.setAlarmVoltageWarningDeciV(any()) } coAnswers { writes += "voltageWarning=${firstArg<Int>()}" }
            coEvery { prefs.setAlarmCoolantNoticeC(any()) } coAnswers { writes += "coolantNotice=${firstArg<Int>()}" }
            coEvery { prefs.setAlarmCoolantWarningC(any()) } coAnswers { writes += "coolantWarning=${firstArg<Int>()}" }
        }

    // --- Zustand ---

    @Test
    fun `the settings are the stored ones from the first moment - never a flash of the defaults`() = runTest {
        val stored = AlarmSettings(voltageEnabled = false, coolantNoticeC = 98)
        val vm = AlarmSettingsViewModel(fakeAppPreferences(alarmSettings = stored))

        assertEquals(stored, vm.settings.value)
    }

    @Test
    fun `without stored settings the defaults apply - all three alarms on`() = runTest {
        val vm = AlarmSettingsViewModel(fakeAppPreferences())

        assertEquals(AlarmSettings(), vm.settings.value)
    }

    @Test
    fun `the settings follow the stored ones`() = runTest {
        val stored = MutableStateFlow(AlarmSettings())
        val prefs = fakeAppPreferences().also { every { it.alarmSettings } returns stored }
        val vm = AlarmSettingsViewModel(prefs)
        val seen = mutableListOf<AlarmSettings>()
        val job = launch { vm.settings.collect { seen += it } }
        advanceUntilIdle()

        stored.value = AlarmSettings(faultsEnabled = false, voltageWarningDeciV = 118)
        advanceUntilIdle()
        job.cancel()

        assertEquals(AlarmSettings(faultsEnabled = false, voltageWarningDeciV = 118), seen.last())
    }

    // --- Schreibzugriffe: jeder Setter schreibt genau seinen Wert und nichts anderes ---

    @Test
    fun `setVoltageEnabled writes only the voltage switch`() = runTest {
        val writes = mutableListOf<String>()
        AlarmSettingsViewModel(recordingPrefs(writes)).setVoltageEnabled(false)
        advanceUntilIdle()
        assertEquals(listOf("voltageEnabled=false"), writes)
    }

    @Test
    fun `setCoolantEnabled writes only the coolant switch`() = runTest {
        val writes = mutableListOf<String>()
        AlarmSettingsViewModel(recordingPrefs(writes)).setCoolantEnabled(false)
        advanceUntilIdle()
        assertEquals(listOf("coolantEnabled=false"), writes)
    }

    @Test
    fun `setFaultsEnabled writes only the fault switch`() = runTest {
        val writes = mutableListOf<String>()
        AlarmSettingsViewModel(recordingPrefs(writes)).setFaultsEnabled(false)
        advanceUntilIdle()
        assertEquals(listOf("faultsEnabled=false"), writes)
    }

    @Test
    fun `a switch turned on again is written as on`() = runTest {
        val writes = mutableListOf<String>()
        AlarmSettingsViewModel(recordingPrefs(writes, AlarmSettings(voltageEnabled = false))).setVoltageEnabled(true)
        advanceUntilIdle()
        assertEquals(listOf("voltageEnabled=true"), writes)
    }

    @Test
    fun `setVoltageNoticeDeciV writes only the voltage notice threshold`() = runTest {
        val writes = mutableListOf<String>()
        AlarmSettingsViewModel(recordingPrefs(writes)).setVoltageNoticeDeciV(128)
        advanceUntilIdle()
        assertEquals(listOf("voltageNotice=128"), writes)
    }

    @Test
    fun `setVoltageWarningDeciV writes only the voltage warning threshold`() = runTest {
        val writes = mutableListOf<String>()
        AlarmSettingsViewModel(recordingPrefs(writes)).setVoltageWarningDeciV(118)
        advanceUntilIdle()
        assertEquals(listOf("voltageWarning=118"), writes)
    }

    @Test
    fun `setCoolantNoticeC writes only the coolant notice threshold`() = runTest {
        val writes = mutableListOf<String>()
        AlarmSettingsViewModel(recordingPrefs(writes)).setCoolantNoticeC(92)
        advanceUntilIdle()
        assertEquals(listOf("coolantNotice=92"), writes)
    }

    @Test
    fun `setCoolantWarningC writes only the coolant warning threshold`() = runTest {
        val writes = mutableListOf<String>()
        AlarmSettingsViewModel(recordingPrefs(writes)).setCoolantWarningC(110)
        advanceUntilIdle()
        assertEquals(listOf("coolantWarning=110"), writes)
    }
}
