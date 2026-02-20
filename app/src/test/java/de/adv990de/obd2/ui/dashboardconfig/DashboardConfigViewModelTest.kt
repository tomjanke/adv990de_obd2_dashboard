package de.adv990de.obd2.ui.dashboardconfig

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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardConfigViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `togglePid delegates to AppPreferences`() = runTest {
        val prefs = mockk<AppPreferences> {
            coEvery { togglePid(any()) } just Runs
            every { selectedPidIds } returns flowOf(AppPreferences.DEFAULT_PIDS)
        }
        val vm = DashboardConfigViewModel(prefs)
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
        val vm = DashboardConfigViewModel(prefs)

        // Subscribe to trigger WhileSubscribed collection, then drain
        val collected = mutableListOf<Set<String>>()
        val job = launch { vm.selectedPidIds.collect { collected.add(it) } }
        advanceUntilIdle()
        job.cancel()

        assert(collected.last() == customIds) {
            "Expected $customIds but got ${collected.lastOrNull()}"
        }
    }
}
