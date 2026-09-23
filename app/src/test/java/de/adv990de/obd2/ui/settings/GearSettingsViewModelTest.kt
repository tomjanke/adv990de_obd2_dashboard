package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.data.AppPreferences
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
class GearSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    private fun makePrefs(
        wheelCircumferenceMm: Float = AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM,
        primaryRatio: Float = AppPreferences.DEFAULT_PRIMARY_RATIO,
        secondaryRatio: Float = AppPreferences.DEFAULT_SECONDARY_RATIO,
        gearRatios: List<Float> = AppPreferences.DEFAULT_GEAR_RATIOS,
    ): AppPreferences = mockk {
        every { this@mockk.wheelCircumferenceMm } returns flowOf(wheelCircumferenceMm)
        every { this@mockk.primaryRatio }         returns flowOf(primaryRatio)
        every { this@mockk.secondaryRatio }       returns flowOf(secondaryRatio)
        every { this@mockk.gearRatios }           returns flowOf(gearRatios)
    }

    @Test
    fun `wheelCircumferenceMm reflects AppPreferences flow`() = runTest {
        val prefs = makePrefs(wheelCircumferenceMm = 2100f)
        val vm = GearSettingsViewModel(prefs)

        val job = launch { vm.wheelCircumferenceMm.collect {} }
        advanceUntilIdle()
        job.cancel()

        assertEquals(2100f, vm.wheelCircumferenceMm.value, 0.001f)
    }

    @Test
    fun `gearRatios reflects AppPreferences flow`() = runTest {
        val customRatios = listOf(3f, 2.2f, 1.7f, 1.4f, 1.1f, 1.0f)
        val prefs = makePrefs(gearRatios = customRatios)
        val vm = GearSettingsViewModel(prefs)

        val job = launch { vm.gearRatios.collect {} }
        advanceUntilIdle()
        job.cancel()

        assertEquals(customRatios, vm.gearRatios.value)
    }

    @Test
    fun `setWheelCircumferenceMm delegates to AppPreferences`() = runTest {
        val prefs = makePrefs()
        coEvery { prefs.setWheelCircumferenceMm(any()) } just Runs
        val vm = GearSettingsViewModel(prefs)

        vm.setWheelCircumferenceMm(2100f)
        advanceUntilIdle()

        coVerify { prefs.setWheelCircumferenceMm(2100f) }
    }

    @Test
    fun `setPrimaryRatio and setSecondaryRatio delegate to AppPreferences`() = runTest {
        val prefs = makePrefs()
        coEvery { prefs.setPrimaryRatio(any()) } just Runs
        coEvery { prefs.setSecondaryRatio(any()) } just Runs
        val vm = GearSettingsViewModel(prefs)

        vm.setPrimaryRatio(2.0f)
        vm.setSecondaryRatio(2.8f)
        advanceUntilIdle()

        coVerify { prefs.setPrimaryRatio(2.0f) }
        coVerify { prefs.setSecondaryRatio(2.8f) }
    }

    @Test
    fun `setGearRatio delegates to AppPreferences with the given index`() = runTest {
        val prefs = makePrefs()
        coEvery { prefs.setGearRatio(any(), any()) } just Runs
        val vm = GearSettingsViewModel(prefs)

        vm.setGearRatio(3, 6.5f)
        advanceUntilIdle()

        coVerify { prefs.setGearRatio(3, 6.5f) }
    }
}
