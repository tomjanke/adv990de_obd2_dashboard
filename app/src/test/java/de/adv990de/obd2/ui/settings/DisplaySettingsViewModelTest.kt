package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.data.AppPreferences
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
        theme: String = "system"
    ): AppPreferences = mockk {
        every { this@mockk.useImperial } returns flowOf(useImperial)
        every { this@mockk.theme }       returns flowOf(theme)
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
        val vm = DisplaySettingsViewModel(makePrefs(theme = "system"))
        // Subscribe to trigger WhileSubscribed collection
        val job = launch { vm.theme.collect {} }
        advanceUntilIdle()
        job.cancel()
        assertEquals("system", vm.theme.value)
    }

    @Test
    fun `setTheme calls appPreferences setTheme with given value`() = runTest {
        val prefs = makePrefs(theme = "system")
        coEvery { prefs.setTheme(any()) } just Runs
        val vm = DisplaySettingsViewModel(prefs)
        val job = launch { vm.theme.collect {} }
        advanceUntilIdle()

        vm.setTheme("dark")
        advanceUntilIdle()
        job.cancel()

        coVerify { prefs.setTheme("dark") }
    }
}
