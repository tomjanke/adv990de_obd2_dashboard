package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.fuel.FuelRangeSource
import de.adv990de.obd2.fuel.LearnedRange
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class FuelSettingsViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    private fun fill(id: Long, km: Int, ml: Int) = LogEntry(
        id = id, type = LogType.FUEL, date = LocalDate.of(2026, 1, 1).plusDays(id), odometerKm = km, amountMl = ml, fullTank = true,
        costCents = null, note = null, brand = null,
    )

    /** Eine erste volle Tankung und [intervals] Intervalle zu je 100 km und 7,7 l = 7,7 l/100 km (erfundene Zahlen). */
    private fun steadyFills(intervals: Int) = listOf(fill(0, 1_000, 15_000)) + List(intervals) { fill(it + 1L, 1_100 + it * 100, 7_700) }

    private fun viewModel(prefs: AppPreferences, entries: List<LogEntry> = emptyList()): FuelSettingsViewModel {
        val logbook = mockk<VehicleLogRepository> { every { this@mockk.entries } returns flowOf(entries) }
        return FuelSettingsViewModel(prefs, FuelRangeSource(prefs, logbook))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the range is the stored one from the first moment - never a flash of not set`() = runTest {
        val vm = viewModel(fakeAppPreferences(fuelRangeFullKm = 320))

        assertEquals(320, vm.rangeFullKm.value)
    }

    @Test
    fun `without a stored range the value is 0 (not set)`() = runTest {
        assertEquals(0, viewModel(fakeAppPreferences()).rangeFullKm.value)
    }

    @Test
    fun `setRangeFullKm saves a valid range`() = runTest {
        val prefs = fakeAppPreferences()
        coEvery { prefs.setFuelRangeFullKm(any()) } just Runs
        val vm = viewModel(prefs)

        vm.setRangeFullKm(320)
        advanceUntilIdle()

        coVerify(exactly = 1) { prefs.setFuelRangeFullKm(320) }
    }

    @Test
    fun `setRangeFullKm with 0 clears the range`() = runTest {
        val prefs = fakeAppPreferences(fuelRangeFullKm = 320)
        coEvery { prefs.setFuelRangeFullKm(any()) } just Runs
        val vm = viewModel(prefs)

        vm.setRangeFullKm(0)
        advanceUntilIdle()

        coVerify(exactly = 1) { prefs.setFuelRangeFullKm(0) }
    }

    @Test
    fun `a range outside 50 to 1000 is not saved`() = runTest {
        val prefs = fakeAppPreferences()
        coEvery { prefs.setFuelRangeFullKm(any()) } just Runs
        val vm = viewModel(prefs)

        for (bad in listOf(-1, 1, 49, 1001, 5000)) vm.setRangeFullKm(bad)
        advanceUntilIdle()

        coVerify(exactly = 0) { prefs.setFuelRangeFullKm(any()) }
    }

    @Test
    fun `the value follows the stored range`() = runTest {
        val vm = viewModel(fakeAppPreferences(fuelRangeFullKm = 250))
        val seen = mutableListOf<Int>()
        val job = launch { vm.rangeFullKm.collect { seen += it } }
        advanceUntilIdle()
        job.cancel()

        assertEquals(250, seen.last())
    }

    @Test
    fun `the reserve is the stored one from the first moment`() = runTest {
        assertEquals(40, viewModel(fakeAppPreferences(fuelReserveKm = 40)).reserveKm.value)
    }

    @Test
    fun `without a stored reserve the value is 0 (off)`() = runTest {
        assertEquals(0, viewModel(fakeAppPreferences()).reserveKm.value)
    }

    @Test
    fun `setReserveKm saves a valid reserve`() = runTest {
        val prefs = fakeAppPreferences()
        coEvery { prefs.setFuelReserveKm(any()) } just Runs
        val vm = viewModel(prefs)

        vm.setReserveKm(40)
        advanceUntilIdle()

        coVerify(exactly = 1) { prefs.setFuelReserveKm(40) }
    }

    @Test
    fun `setReserveKm with 0 switches the reserve off`() = runTest {
        val prefs = fakeAppPreferences(fuelReserveKm = 40)
        coEvery { prefs.setFuelReserveKm(any()) } just Runs
        val vm = viewModel(prefs)

        vm.setReserveKm(0)
        advanceUntilIdle()

        coVerify(exactly = 1) { prefs.setFuelReserveKm(0) }
    }

    @Test
    fun `a reserve outside 5 to 500 is not saved`() = runTest {
        val prefs = fakeAppPreferences()
        coEvery { prefs.setFuelReserveKm(any()) } just Runs
        val vm = viewModel(prefs)

        for (bad in listOf(-1, 1, 4, 501, 5000)) vm.setReserveKm(bad)
        advanceUntilIdle()

        coVerify(exactly = 0) { prefs.setFuelReserveKm(any()) }
    }

    // --- Tankinhalt und gelernte Reichweite (F5) ---

    @Test
    fun `the tank content is the stored one from the first moment`() = runTest {
        assertEquals(200, viewModel(fakeAppPreferences(fuelTankCapacityDl = 200)).tankCapacityDl.value)
    }

    @Test
    fun `without a stored tank content the value is 0 (not set)`() = runTest {
        assertEquals(0, viewModel(fakeAppPreferences()).tankCapacityDl.value)
    }

    @Test
    fun `setTankCapacityDl saves a valid content`() = runTest {
        val prefs = fakeAppPreferences()
        coEvery { prefs.setFuelTankCapacityDl(any()) } just Runs
        val vm = viewModel(prefs)

        vm.setTankCapacityDl(200)
        advanceUntilIdle()

        coVerify(exactly = 1) { prefs.setFuelTankCapacityDl(200) }
    }

    @Test
    fun `setTankCapacityDl with 0 clears the content`() = runTest {
        val prefs = fakeAppPreferences(fuelTankCapacityDl = 200)
        coEvery { prefs.setFuelTankCapacityDl(any()) } just Runs
        val vm = viewModel(prefs)

        vm.setTankCapacityDl(0)
        advanceUntilIdle()

        coVerify(exactly = 1) { prefs.setFuelTankCapacityDl(0) }
    }

    @Test
    fun `a tank content outside 3,0 to 60,0 liters is not saved`() = runTest {
        val prefs = fakeAppPreferences()
        coEvery { prefs.setFuelTankCapacityDl(any()) } just Runs
        val vm = viewModel(prefs)

        for (bad in listOf(-1, 1, 29, 601, 5000)) vm.setTankCapacityDl(bad)
        advanceUntilIdle()

        coVerify(exactly = 0) { prefs.setFuelTankCapacityDl(any()) }
    }

    @Test
    fun `the learned range shows with a tank content and enough intervals`() = runTest {
        val vm = viewModel(fakeAppPreferences(fuelRangeFullKm = 320, fuelTankCapacityDl = 200), steadyFills(3))
        val seen = mutableListOf<LearnedRange?>()
        val job = launch { vm.learned.collect { seen += it } }
        advanceUntilIdle()
        job.cancel()

        assertEquals(LearnedRange(260, 7.7), seen.last())
    }

    @Test
    fun `there is no learned range without a tank content`() = runTest {
        val vm = viewModel(fakeAppPreferences(fuelRangeFullKm = 320), steadyFills(5))
        val seen = mutableListOf<LearnedRange?>()
        val job = launch { vm.learned.collect { seen += it } }
        advanceUntilIdle()
        job.cancel()

        assertEquals(null, seen.last())
    }
}
