package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
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

    private fun makeLogRepository(): AppLogRepository = mockk(relaxed = true)

    @Test
    fun `wheelCircumferenceMm reflects AppPreferences flow`() = runTest {
        val prefs = makePrefs(wheelCircumferenceMm = 2100f)
        val vm = GearSettingsViewModel(prefs, makeLogRepository())

        val job = launch { vm.wheelCircumferenceMm.collect {} }
        advanceUntilIdle()
        job.cancel()

        assertEquals(2100f, vm.wheelCircumferenceMm.value, 0.001f)
    }

    @Test
    fun `gearRatioConflicts reflects close gear ratios from AppPreferences`() = runTest {
        // 10x und 9.9x liegen ca. 1 Prozent auseinander -- klarer Konflikt, weit unter jeder
        // Grenzwert-Unschaerfe.
        val prefs = makePrefs(
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f, 9.9f, 5f, 4f, 3f, 2f),
        )
        val vm = GearSettingsViewModel(prefs, makeLogRepository())

        val job = launch { vm.gearRatioConflicts.collect {} }
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(1 to 2), vm.gearRatioConflicts.value)
    }

    @Test
    fun `gearRatioConflicts is empty for the default ratios`() = runTest {
        val prefs = makePrefs()
        val vm = GearSettingsViewModel(prefs, makeLogRepository())

        val job = launch { vm.gearRatioConflicts.collect {} }
        advanceUntilIdle()
        job.cancel()

        assertEquals(emptyList<Pair<Int, Int>>(), vm.gearRatioConflicts.value)
    }

    @Test
    fun `gearRatios reflects AppPreferences flow`() = runTest {
        val customRatios = listOf(3f, 2.2f, 1.7f, 1.4f, 1.1f, 1.0f)
        val prefs = makePrefs(gearRatios = customRatios)
        val vm = GearSettingsViewModel(prefs, makeLogRepository())

        val job = launch { vm.gearRatios.collect {} }
        advanceUntilIdle()
        job.cancel()

        assertEquals(customRatios, vm.gearRatios.value)
    }

    @Test
    fun `setWheelCircumferenceMm delegates to AppPreferences`() = runTest {
        val prefs = makePrefs()
        coEvery { prefs.setWheelCircumferenceMm(any()) } just Runs
        val vm = GearSettingsViewModel(prefs, makeLogRepository())

        vm.setWheelCircumferenceMm(2100f)
        advanceUntilIdle()

        coVerify { prefs.setWheelCircumferenceMm(2100f) }
    }

    @Test
    fun `setPrimaryRatio and setSecondaryRatio delegate to AppPreferences`() = runTest {
        val prefs = makePrefs()
        coEvery { prefs.setPrimaryRatio(any()) } just Runs
        coEvery { prefs.setSecondaryRatio(any()) } just Runs
        val vm = GearSettingsViewModel(prefs, makeLogRepository())

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
        val vm = GearSettingsViewModel(prefs, makeLogRepository())

        vm.setGearRatio(3, 6.5f)
        advanceUntilIdle()

        coVerify { prefs.setGearRatio(3, 6.5f) }
    }

    @Test
    fun `a failed write is caught and logged instead of crashing`() = runTest {
        val prefs = makePrefs()
        val boom = RuntimeException("disk full")
        coEvery { prefs.setGearRatio(any(), any()) } throws boom
        val logRepository = makeLogRepository()
        val vm = GearSettingsViewModel(prefs, logRepository)

        vm.setGearRatio(0, 3.0f)
        advanceUntilIdle()

        coVerify { logRepository.log(LogLevel.ERROR, any()) }
    }

    @Test
    fun `a CancellationException is rethrown instead of caught and logged`() = runTest {
        val prefs = makePrefs()
        coEvery { prefs.setGearRatio(any(), any()) } throws CancellationException("cancelled")
        val logRepository = makeLogRepository()
        val vm = GearSettingsViewModel(prefs, logRepository)

        vm.setGearRatio(0, 3.0f)
        advanceUntilIdle()

        // CancellationException was rethrown out of persist()'s catch block, so -- unlike
        // an ordinary exception -- it must never reach AppLogRepository.
        coVerify(exactly = 0) { logRepository.log(any(), any()) }
    }

    // ── B22: the ViewModel is the last line of defence against implausible values ──────────────────────

    private fun prefsThatAcceptWrites(): AppPreferences = makePrefs().also { prefs ->
        coEvery { prefs.setWheelCircumferenceMm(any()) } just Runs
        coEvery { prefs.setPrimaryRatio(any()) } just Runs
        coEvery { prefs.setSecondaryRatio(any()) } just Runs
        coEvery { prefs.setGearRatio(any(), any()) } just Runs
    }

    @Test
    fun `a wheel circumference of 2_096 mm-as-metres is not stored and is logged`() = runTest {
        val prefs = prefsThatAcceptWrites()
        val logRepo = makeLogRepository()
        val vm = GearSettingsViewModel(prefs, logRepo)

        vm.setWheelCircumferenceMm(2.096f)
        advanceUntilIdle()

        coVerify(exactly = 0) { prefs.setWheelCircumferenceMm(any()) }
        coVerify { logRepo.log(LogLevel.WARN, match { it.contains("2.096") }) }
    }

    @Test
    fun `plausible values reach the preferences`() = runTest {
        val prefs = prefsThatAcceptWrites()
        val vm = GearSettingsViewModel(prefs, makeLogRepository())

        vm.setWheelCircumferenceMm(2096f)
        vm.setPrimaryRatio(1.9143f)
        vm.setSecondaryRatio(2.625f)
        vm.setGearRatio(2, 1.6667f)
        advanceUntilIdle()

        coVerify { prefs.setWheelCircumferenceMm(2096f) }
        coVerify { prefs.setPrimaryRatio(1.9143f) }
        coVerify { prefs.setSecondaryRatio(2.625f) }
        coVerify { prefs.setGearRatio(2, 1.6667f) }
    }

    @Test
    fun `implausible ratios are not stored`() = runTest {
        val prefs = prefsThatAcceptWrites()
        val vm = GearSettingsViewModel(prefs, makeLogRepository())

        vm.setPrimaryRatio(0f)
        vm.setPrimaryRatio(11f)
        vm.setSecondaryRatio(0f)
        vm.setSecondaryRatio(16f)
        vm.setGearRatio(0, 9f)
        advanceUntilIdle()

        coVerify(exactly = 0) { prefs.setPrimaryRatio(any()) }
        coVerify(exactly = 0) { prefs.setSecondaryRatio(any()) }
        coVerify(exactly = 0) { prefs.setGearRatio(any(), any()) }
    }

    @Test
    fun `a gear can be switched off with zero - and only a gear`() = runTest {
        val prefs = prefsThatAcceptWrites()
        val vm = GearSettingsViewModel(prefs, makeLogRepository())

        vm.setGearRatio(5, 0f)
        vm.setPrimaryRatio(0f)
        advanceUntilIdle()

        coVerify { prefs.setGearRatio(5, 0f) }
        coVerify(exactly = 0) { prefs.setPrimaryRatio(any()) }
    }

    @Test
    fun `a switched-off gear causes no conflict warning`() = runTest {
        val prefs = makePrefs(gearRatios = listOf(2.9167f, 2.1333f, 1.6667f, 1.35f, 1.125f, 0f))
        val vm = GearSettingsViewModel(prefs, makeLogRepository())
        val job = launch { vm.gearRatioConflicts.collect {} }
        advanceUntilIdle()
        job.cancel()

        assertEquals(emptyList<Pair<Int, Int>>(), vm.gearRatioConflicts.value)
    }
}
