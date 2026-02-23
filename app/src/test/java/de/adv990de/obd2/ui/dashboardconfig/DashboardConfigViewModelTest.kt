package de.adv990de.obd2.ui.dashboardconfig

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardConfigViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    private fun makeVm(
        prefs: AppPreferences = mockk {
            every { rpmBlinkThreshold } returns flowOf(0)
        },
        obdRepo: ObdRepository = mockk {
            every { isConnectedFlow } returns MutableStateFlow(false)
        }
    ) = DashboardConfigViewModel(prefs, obdRepo)

    @Test
    fun `rpmBlinkThreshold reflects AppPreferences flow`() = runTest {
        val prefs = mockk<AppPreferences> {
            every { rpmBlinkThreshold } returns flowOf(4000)
        }
        val vm = makeVm(prefs = prefs)

        val collected = mutableListOf<Int>()
        val job = launch { vm.rpmBlinkThreshold.collect { collected.add(it) } }
        advanceUntilIdle()
        job.cancel()

        // stateIn emits the initial default (0) then the upstream value (4000)
        assertTrue("expected ≥ 2 emissions, got ${collected.size}", collected.size >= 2)
        assertEquals(4000, collected.last())
    }

    @Test
    fun `setRpmBlinkThreshold delegates to AppPreferences`() = runTest {
        val prefs = mockk<AppPreferences> {
            coEvery { setRpmBlinkThreshold(any()) } just Runs
            every { rpmBlinkThreshold } returns flowOf(0)
        }
        val vm = makeVm(prefs = prefs)
        vm.setRpmBlinkThreshold(6000)
        advanceUntilIdle()

        coVerify { prefs.setRpmBlinkThreshold(6000) }
    }

    @Test
    fun `isConnected reflects ObdRepository isConnectedFlow`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
        }
        val vm = makeVm(obdRepo = obdRepo)

        val job = launch { vm.isConnected.collect {} }
        runCurrent()
        assertFalse(vm.isConnected.value)

        isConnectedFlow.value = true
        advanceUntilIdle()
        assertTrue(vm.isConnected.value)

        job.cancel()
    }
}
