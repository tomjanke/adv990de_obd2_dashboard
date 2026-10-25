package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.logbook.LogType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Die Seite "Wartungsintervalle": die Intervalle aus dem Speicher, Schreiben mit Pruefung, nichts vorbelegt. */
@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceIntervalsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class Fixture(val vm: MaintenanceIntervalsViewModel, val intervals: MutableStateFlow<Map<LogType, Int>>, val prefs: de.adv990de.obd2.data.AppPreferences)

    private fun fixture(initial: Map<LogType, Int> = emptyMap()): Fixture {
        val intervals = MutableStateFlow(initial)
        val prefs = fakeAppPreferences()
        every { prefs.maintenanceIntervals } returns intervals
        coEvery { prefs.setMaintenanceInterval(any(), any()) } coAnswers {
            val type = firstArg<LogType>()
            val km = secondArg<Int>()
            intervals.value = if (km == 0) intervals.value - type else intervals.value + (type to km)
        }
        return Fixture(MaintenanceIntervalsViewModel(prefs), intervals, prefs)
    }

    private fun TestScope.collect(vm: MaintenanceIntervalsViewModel) {
        backgroundScope.launch { vm.intervals.collect {} }
    }

    @Test
    fun `nothing is set by default - there is no preset value`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()

        assertEquals(emptyMap<LogType, Int>(), f.vm.intervals.value)
    }

    @Test
    fun `the stored intervals are shown and follow changes`() = runTest {
        val f = fixture(mapOf(LogType.CHAIN_SMALL to 1_000))
        collect(f.vm); runCurrent()
        assertEquals(mapOf(LogType.CHAIN_SMALL to 1_000), f.vm.intervals.value)

        f.intervals.value = f.intervals.value + (LogType.SERVICE_LARGE to 20_000)
        runCurrent()

        assertEquals(mapOf(LogType.CHAIN_SMALL to 1_000, LogType.SERVICE_LARGE to 20_000), f.vm.intervals.value)
    }

    @Test
    fun `setting writes the interval, zero clears it`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()

        f.vm.setInterval(LogType.TIRE_CHANGE, 15_000); runCurrent()
        assertEquals(mapOf(LogType.TIRE_CHANGE to 15_000), f.vm.intervals.value)

        f.vm.setInterval(LogType.TIRE_CHANGE, 0); runCurrent()
        assertEquals(emptyMap<LogType, Int>(), f.vm.intervals.value)
    }

    @Test
    fun `the limits are inclusive, a value outside them is not written`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()

        f.vm.setInterval(LogType.CHAIN_SMALL, 50); runCurrent()
        f.vm.setInterval(LogType.CHAIN_LARGE, 100_000); runCurrent()
        f.vm.setInterval(LogType.SERVICE_SMALL, 49); runCurrent()
        f.vm.setInterval(LogType.SERVICE_LARGE, 100_001); runCurrent()
        f.vm.setInterval(LogType.TIRE_CHANGE, -1); runCurrent()

        assertEquals(mapOf(LogType.CHAIN_SMALL to 50, LogType.CHAIN_LARGE to 100_000), f.vm.intervals.value)
        coVerify(exactly = 2) { f.prefs.setMaintenanceInterval(any(), any()) }
    }

    @Test
    fun `a kind without a maintenance interval is not written`() = runTest {
        val f = fixture()
        collect(f.vm); runCurrent()

        f.vm.setInterval(LogType.FUEL, 1_000); runCurrent()

        coVerify(exactly = 0) { f.prefs.setMaintenanceInterval(any(), any()) }
    }
}
