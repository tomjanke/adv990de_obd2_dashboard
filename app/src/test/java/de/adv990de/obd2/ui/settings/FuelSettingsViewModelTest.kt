package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.fakeAppPreferences
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

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the range is the stored one from the first moment - never a flash of not set`() = runTest {
        val vm = FuelSettingsViewModel(fakeAppPreferences(fuelRangeFullKm = 320))

        assertEquals(320, vm.rangeFullKm.value)
    }

    @Test
    fun `without a stored range the value is 0 (not set)`() = runTest {
        assertEquals(0, FuelSettingsViewModel(fakeAppPreferences()).rangeFullKm.value)
    }

    @Test
    fun `setRangeFullKm saves a valid range`() = runTest {
        val prefs = fakeAppPreferences()
        coEvery { prefs.setFuelRangeFullKm(any()) } just Runs
        val vm = FuelSettingsViewModel(prefs)

        vm.setRangeFullKm(320)
        advanceUntilIdle()

        coVerify(exactly = 1) { prefs.setFuelRangeFullKm(320) }
    }

    @Test
    fun `setRangeFullKm with 0 clears the range`() = runTest {
        val prefs = fakeAppPreferences(fuelRangeFullKm = 320)
        coEvery { prefs.setFuelRangeFullKm(any()) } just Runs
        val vm = FuelSettingsViewModel(prefs)

        vm.setRangeFullKm(0)
        advanceUntilIdle()

        coVerify(exactly = 1) { prefs.setFuelRangeFullKm(0) }
    }

    @Test
    fun `a range outside 50 to 1000 is not saved`() = runTest {
        val prefs = fakeAppPreferences()
        coEvery { prefs.setFuelRangeFullKm(any()) } just Runs
        val vm = FuelSettingsViewModel(prefs)

        for (bad in listOf(-1, 1, 49, 1001, 5000)) vm.setRangeFullKm(bad)
        advanceUntilIdle()

        coVerify(exactly = 0) { prefs.setFuelRangeFullKm(any()) }
    }

    @Test
    fun `the value follows the stored range`() = runTest {
        val vm = FuelSettingsViewModel(fakeAppPreferences(fuelRangeFullKm = 250))
        val seen = mutableListOf<Int>()
        val job = launch { vm.rangeFullKm.collect { seen += it } }
        advanceUntilIdle()
        job.cancel()

        assertEquals(250, seen.last())
    }
}
