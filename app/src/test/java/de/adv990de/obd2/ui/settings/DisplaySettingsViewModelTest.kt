package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.AppPreferences.Companion.THEME_SYSTEM
import de.adv990de.obd2.data.AppPreferences.Companion.THEME_DARK
import de.adv990de.obd2.data.AppPreferences.Companion.THEME_BLUE
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisplaySettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    private fun makePrefs(
        useImperial: Boolean = false,
        theme: String = THEME_SYSTEM,
        useGpsSpeed: Boolean = false,
        backgroundLocationPrompted: Boolean = false,
        weatherLocationPrompted: Boolean = false
    ): AppPreferences = mockk {
        every { this@mockk.useImperial }  returns flowOf(useImperial)
        every { this@mockk.theme }        returns flowOf(theme)
        every { this@mockk.useGpsSpeed }  returns flowOf(useGpsSpeed)
        every { this@mockk.backgroundLocationPrompted } returns flowOf(backgroundLocationPrompted)
        every { this@mockk.weatherLocationPrompted } returns flowOf(weatherLocationPrompted)
    }

    @Test
    fun `useImperial defaults to false`() = runTest {
        val vm = DisplaySettingsViewModel(makePrefs(useImperial = false))
        // Subscribe to trigger WhileSubscribed collection
        val job = launch { vm.useImperial.collect {} }
        advanceUntilIdle()
        job.cancel()
        assertFalse(vm.useImperial.value)
    }

    @Test
    fun `useImperial reflects persisted true value`() = runTest {
        val vm = DisplaySettingsViewModel(makePrefs(useImperial = true))
        // Subscribe to trigger WhileSubscribed collection
        val job = launch { vm.useImperial.collect {} }
        advanceUntilIdle()
        job.cancel()
        assertTrue(vm.useImperial.value)
    }

    @Test
    fun `toggleUseImperial calls setUseImperial with toggled value`() = runTest {
        val prefs = makePrefs(useImperial = false)
        coEvery { prefs.setUseImperial(any()) } just Runs
        val vm = DisplaySettingsViewModel(prefs)
        val job = launch { vm.useImperial.collect {} }
        advanceUntilIdle()

        vm.toggleUseImperial()
        advanceUntilIdle()
        job.cancel()

        coVerify { prefs.setUseImperial(true) }
    }

    @Test
    fun `theme defaults to system`() = runTest {
        val vm = DisplaySettingsViewModel(makePrefs(theme = THEME_SYSTEM))
        // Subscribe to trigger WhileSubscribed collection
        val job = launch { vm.theme.collect {} }
        advanceUntilIdle()
        job.cancel()
        assertEquals(THEME_SYSTEM, vm.theme.value)
    }

    @Test
    fun `setTheme calls appPreferences setTheme with given value`() = runTest {
        val prefs = makePrefs(theme = THEME_SYSTEM)
        coEvery { prefs.setTheme(any()) } just Runs
        val vm = DisplaySettingsViewModel(prefs)
        val job = launch { vm.theme.collect {} }
        advanceUntilIdle()

        vm.setTheme(THEME_DARK)
        advanceUntilIdle()
        job.cancel()

        coVerify { prefs.setTheme(THEME_DARK) }
    }

    @Test
    fun `toggleUseImperial from true calls setUseImperial with false`() = runTest {
        val prefs = makePrefs(useImperial = true)
        coEvery { prefs.setUseImperial(any()) } just Runs
        val vm = DisplaySettingsViewModel(prefs)
        val job = launch { vm.useImperial.collect {} }
        advanceUntilIdle()

        vm.toggleUseImperial()
        advanceUntilIdle()
        job.cancel()

        coVerify { prefs.setUseImperial(false) }
    }

    @Test
    fun `theme emits THEME_BLUE when prefs returns blue`() = runTest {
        val vm = DisplaySettingsViewModel(makePrefs(theme = THEME_BLUE))
        val job = launch { vm.theme.collect {} }
        advanceUntilIdle()
        job.cancel()
        assertEquals(THEME_BLUE, vm.theme.value)
    }

    @Test
    fun `useGpsSpeed defaults to false`() = runTest {
        val vm = DisplaySettingsViewModel(makePrefs(useGpsSpeed = false))
        val job = launch { vm.useGpsSpeed.collect {} }
        advanceUntilIdle()
        job.cancel()
        assertFalse(vm.useGpsSpeed.value)
    }

    @Test
    fun `useGpsSpeed reflects persisted true value`() = runTest {
        val vm = DisplaySettingsViewModel(makePrefs(useGpsSpeed = true))
        val job = launch { vm.useGpsSpeed.collect {} }
        advanceUntilIdle()
        job.cancel()
        assertTrue(vm.useGpsSpeed.value)
    }

    @Test
    fun `setUseGpsSpeed calls appPreferences setUseGpsSpeed with given value`() = runTest {
        val prefs = makePrefs(useGpsSpeed = false)
        coEvery { prefs.setUseGpsSpeed(any()) } just Runs
        val vm = DisplaySettingsViewModel(prefs)
        val job = launch { vm.useGpsSpeed.collect {} }
        advanceUntilIdle()

        vm.setUseGpsSpeed(true)
        advanceUntilIdle()
        job.cancel()

        coVerify { prefs.setUseGpsSpeed(true) }
    }

    @Test
    fun `backgroundLocationPrompted defaults to false`() = runTest {
        val vm = DisplaySettingsViewModel(makePrefs(backgroundLocationPrompted = false))
        val job = launch { vm.backgroundLocationPrompted.collect {} }
        advanceUntilIdle()
        job.cancel()
        assertFalse(vm.backgroundLocationPrompted.value)
    }

    @Test
    fun `backgroundLocationPrompted reflects persisted true value`() = runTest {
        val vm = DisplaySettingsViewModel(makePrefs(backgroundLocationPrompted = true))
        val job = launch { vm.backgroundLocationPrompted.collect {} }
        advanceUntilIdle()
        job.cancel()
        assertTrue(vm.backgroundLocationPrompted.value)
    }

    @Test
    fun `setBackgroundLocationPrompted calls appPreferences setBackgroundLocationPrompted with given value`() = runTest {
        val prefs = makePrefs(backgroundLocationPrompted = false)
        coEvery { prefs.setBackgroundLocationPrompted(any()) } just Runs
        val vm = DisplaySettingsViewModel(prefs)
        val job = launch { vm.backgroundLocationPrompted.collect {} }
        advanceUntilIdle()

        vm.setBackgroundLocationPrompted(true)
        advanceUntilIdle()
        job.cancel()

        coVerify { prefs.setBackgroundLocationPrompted(true) }
    }

    @Test
    fun `weatherLocationPrompted defaults to false`() = runTest {
        val vm = DisplaySettingsViewModel(makePrefs(weatherLocationPrompted = false))
        val job = launch { vm.weatherLocationPrompted.collect {} }
        advanceUntilIdle()
        job.cancel()
        assertFalse(vm.weatherLocationPrompted.value)
    }

    @Test
    fun `weatherLocationPrompted reflects persisted true value`() = runTest {
        val vm = DisplaySettingsViewModel(makePrefs(weatherLocationPrompted = true))
        val job = launch { vm.weatherLocationPrompted.collect {} }
        advanceUntilIdle()
        job.cancel()
        assertTrue(vm.weatherLocationPrompted.value)
    }

    @Test
    fun `setWeatherLocationPrompted calls appPreferences setWeatherLocationPrompted with given value`() = runTest {
        val prefs = makePrefs(weatherLocationPrompted = false)
        coEvery { prefs.setWeatherLocationPrompted(any()) } just Runs
        val vm = DisplaySettingsViewModel(prefs)
        val job = launch { vm.weatherLocationPrompted.collect {} }
        advanceUntilIdle()

        vm.setWeatherLocationPrompted(true)
        advanceUntilIdle()
        job.cancel()

        coVerify { prefs.setWeatherLocationPrompted(true) }
    }
}