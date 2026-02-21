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
            coEvery { togglePid(any()) } just Runs
            every { selectedPidIds } returns flowOf(AppPreferences.DEFAULT_PIDS)
        },
        obdRepo: ObdRepository = mockk {
            every { isConnectedFlow } returns MutableStateFlow(false)
        }
    ) = DashboardConfigViewModel(prefs, obdRepo)

    @Test
    fun `togglePid delegates to AppPreferences`() = runTest {
        val prefs = mockk<AppPreferences> {
            coEvery { togglePid(any()) } just Runs
            every { selectedPidIds } returns flowOf(AppPreferences.DEFAULT_PIDS)
        }
        val vm = makeVm(prefs = prefs)
        vm.togglePid("010C")
        advanceUntilIdle()

        coVerify { prefs.togglePid("010C") }
    }

    @Test
    fun `selectedPidIds reflects AppPreferences flow`() = runTest {
        val customIds = setOf("010C", "010D")
        val prefs = mockk<AppPreferences> {
            every { selectedPidIds } returns flowOf(customIds)
        }
        val vm = makeVm(prefs = prefs)

        // Subscribe to trigger WhileSubscribed collection, then drain
        val collected = mutableListOf<Set<String>>()
        val job = launch { vm.selectedPidIds.collect { collected.add(it) } }
        advanceUntilIdle()
        job.cancel()

        // stateIn emits the initial default value before the upstream value.
        // Assert both the size (proves the default was emitted first) and the final value.
        assertTrue("expected ≥ 2 emissions (default + upstream), got ${collected.size}", collected.size >= 2)
        assertEquals(customIds, collected.last())
    }

    @Test
    fun `selectedPidIds falls back to DEFAULT_PIDS when upstream emits empty set`() = runTest {
        // Verifies the ifEmpty guard: a DataStore wipe leaving emptySet() must not propagate
        // to the UI — DashboardViewModel uses ifEmpty { defaults } too, but both layers should
        // be independently robust.
        val prefs = mockk<AppPreferences> {
            coEvery { togglePid(any()) } just Runs
            every { selectedPidIds } returns flowOf(emptySet())
        }
        val vm = makeVm(prefs = prefs)

        val job = launch { vm.selectedPidIds.collect {} }
        advanceUntilIdle()
        job.cancel()

        // Upstream emits emptySet() → ifEmpty substitutes DEFAULT_PIDS → never empty
        assertEquals(AppPreferences.DEFAULT_PIDS, vm.selectedPidIds.value)
        assertTrue("selectedPidIds must never expose empty set to UI", vm.selectedPidIds.value.isNotEmpty())
    }

    @Test
    fun `allPids is sorted alphabetically by name`() = runTest {
        val vm = makeVm()
        val names = vm.allPids.map { it.name }
        assertEquals(names.sorted(), names)
    }

    @Test
    fun `isConnected reflects ObdRepository isConnectedFlow`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
        }
        val vm = makeVm(obdRepo = obdRepo)

        // Subscribe to activate WhileSubscribed sharing
        val job = launch { vm.isConnected.collect {} }
        runCurrent()
        assertFalse(vm.isConnected.value)

        isConnectedFlow.value = true
        advanceUntilIdle()
        assertTrue(vm.isConnected.value)

        job.cancel()
    }
}
