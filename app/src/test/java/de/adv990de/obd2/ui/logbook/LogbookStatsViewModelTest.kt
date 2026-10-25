package de.adv990de.obd2.ui.logbook

import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** Die Detailseite der Auswertung: der Zustand je Art (Tanken, Oel, Kuehlfluessigkeit) aus dem Repository, und was bei einem unbrauchbaren Argument passiert. */
@OptIn(ExperimentalCoroutinesApi::class)
class LogbookStatsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(testDispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun entry(id: Long, type: LogType, km: Int, ml: Int?, full: Boolean? = null) = LogEntry(
        id = id, type = type, date = LocalDate.of(2026, 10, 1), odometerKm = km, amountMl = ml,
        fullTank = full, costCents = null, note = null, brand = null,
    )

    private fun vm(typeArg: String?, entries: MutableStateFlow<List<LogEntry>>): LogbookStatsViewModel {
        val repo = mockk<VehicleLogRepository> { every { this@mockk.entries } returns entries }
        val handle = SavedStateHandle(if (typeArg == null) emptyMap() else mapOf(LOG_STATS_TYPE_ARG to typeArg))
        return LogbookStatsViewModel(handle, repo)
    }

    private fun TestScope.collect(vm: LogbookStatsViewModel) {
        backgroundScope.launch { vm.state.collect {} }
    }

    @Test
    fun `a missing, unknown or unsuitable type is invalid`() = runTest {
        for (arg in listOf(null, "", "NOPE", "CHAIN_SMALL", "SERVICE_LARGE", "fuel")) {
            val vm = vm(arg, MutableStateFlow(emptyList()))
            collect(vm); runCurrent()
            assertEquals("$arg", LogbookStatsState.Invalid, vm.state.value)
        }
    }

    @Test
    fun `the state starts as loading until the repository has answered`() = runTest {
        val vm = vm("FUEL", MutableStateFlow(emptyList()))
        assertEquals(LogbookStatsState.Loading, vm.state.value)
    }

    @Test
    fun `fuel gives the fuel report`() = runTest {
        val vm = vm(
            "FUEL",
            MutableStateFlow(listOf(entry(1, LogType.FUEL, 1_000, 10_000, true), entry(2, LogType.FUEL, 1_200, 12_000, true), entry(3, LogType.OIL, 1_100, 100))),
        )
        collect(vm); runCurrent()

        val state = vm.state.value as LogbookStatsState.Fuel
        assertEquals(1, state.report.intervals.size)
        assertEquals(6.0, state.report.summary!!.averageLitersPer100Km, 1e-9)
    }

    @Test
    fun `oil and coolant each give their own report`() = runTest {
        val entries = MutableStateFlow(
            listOf(
                entry(1, LogType.OIL, 10_000, 100), entry(2, LogType.OIL, 10_500, 50),
                entry(3, LogType.COOLANT, 10_000, 200), entry(4, LogType.COOLANT, 12_000, 100),
            ),
        )
        val oil = vm("OIL", entries)
        val coolant = vm("COOLANT", entries)
        collect(oil); collect(coolant); runCurrent()

        val oilState = oil.state.value as LogbookStatsState.Fluid
        val coolantState = coolant.state.value as LogbookStatsState.Fluid
        assertEquals(LogType.OIL, oilState.type)
        assertEquals(100.0, oilState.report.summary!!.averageMlPer1000Km, 1e-9)
        assertEquals(LogType.COOLANT, coolantState.type)
        assertEquals(50.0, coolantState.report.summary!!.averageMlPer1000Km, 1e-9)
    }

    @Test
    fun `the state follows changes of the logbook`() = runTest {
        val entries = MutableStateFlow(listOf(entry(1, LogType.OIL, 10_000, 100)))
        val vm = vm("OIL", entries)
        collect(vm); runCurrent()
        assertTrue((vm.state.value as LogbookStatsState.Fluid).report.intervals.isEmpty())

        entries.value = entries.value + entry(2, LogType.OIL, 10_500, 50)
        runCurrent()

        assertEquals(1, (vm.state.value as LogbookStatsState.Fluid).report.intervals.size)
    }
}
