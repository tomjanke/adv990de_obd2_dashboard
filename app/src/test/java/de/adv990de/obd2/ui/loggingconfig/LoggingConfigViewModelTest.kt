package de.adv990de.obd2.ui.loggingconfig

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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoggingConfigViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    private fun makeVm(
        prefs: AppPreferences = mockk {
            every { loggedPidIds } returns flowOf(AppPreferences.ALL_PID_IDS)
        },
        repo: ObdRepository = mockk {
            every { latestReadings } returns MutableStateFlow(emptyList())
        }
    ) = LoggingConfigViewModel(prefs, repo)

    // ── Test 1 ────────────────────────────────────────────────────────────────

    @Test
    fun `allPids is sorted alphabetically by name`() {
        val vm = makeVm()

        assertTrue("allPids must not be empty", vm.allPids.isNotEmpty())

        for (i in 0 until vm.allPids.size - 1) {
            val a = vm.allPids[i].name
            val b = vm.allPids[i + 1].name
            assertTrue(
                "Expected allPids[$i].name ('$a') <= allPids[${i + 1}].name ('$b')",
                a <= b
            )
        }
    }

    // ── Test 2 ────────────────────────────────────────────────────────────────

    @Test
    fun `togglePid delegates to appPreferences`() = runTest {
        val prefs = mockk<AppPreferences> {
            every { loggedPidIds } returns flowOf(AppPreferences.ALL_PID_IDS)
            coEvery { toggleLoggedPid("010C") } just Runs
        }
        val vm = makeVm(prefs = prefs)

        vm.togglePid("010C")
        advanceUntilIdle()

        coVerify { prefs.toggleLoggedPid("010C") }
    }

    // ── Test 3 ────────────────────────────────────────────────────────────────

    @Test
    fun `selectAll delegates to appPreferences with ALL_PID_IDS`() = runTest {
        val prefs = mockk<AppPreferences> {
            every { loggedPidIds } returns flowOf(AppPreferences.ALL_PID_IDS)
            coEvery { setLoggedPids(any()) } just Runs
        }
        val vm = makeVm(prefs = prefs)

        vm.selectAll()
        advanceUntilIdle()

        coVerify { prefs.setLoggedPids(AppPreferences.ALL_PID_IDS) }
    }

    // ── Test 4 ────────────────────────────────────────────────────────────────

    @Test
    fun `deselectAll delegates to appPreferences with empty set`() = runTest {
        val prefs = mockk<AppPreferences> {
            every { loggedPidIds } returns flowOf(AppPreferences.ALL_PID_IDS)
            coEvery { setLoggedPids(any()) } just Runs
        }
        val vm = makeVm(prefs = prefs)

        vm.deselectAll()
        advanceUntilIdle()

        coVerify { prefs.setLoggedPids(emptySet()) }
    }

    // ── Test 5 ────────────────────────────────────────────────────────────────

    @Test
    fun `loggedPidIds emits from appPreferences`() = runTest {
        val expectedIds = setOf("010C", "010D")
        val prefs = mockk<AppPreferences> {
            every { loggedPidIds } returns flowOf(expectedIds)
        }
        val vm = makeVm(prefs = prefs)

        // stateIn(WhileSubscribed) only collects upstream while there is at least one active
        // subscriber. Launch a collector to activate the sharing, then advance until the
        // upstream flowOf has emitted and the StateFlow has been updated.
        val job = launch { vm.loggedPidIds.collect {} }
        advanceUntilIdle()

        assertEquals(expectedIds, vm.loggedPidIds.value)
        job.cancel()
    }
}
