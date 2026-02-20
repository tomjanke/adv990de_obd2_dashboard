package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.SensorValue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.Runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    private fun makeSensorValue() = SensorValue(pid = PidRegistry.RPM, value = 1500f, timestamp = 1000L)

    private fun makePrefs(
        selectedIds: Set<String> = AppPreferences.DEFAULT_PIDS
    ): AppPreferences = mockk {
        every { selectedPidIds } returns flowOf(selectedIds)
    }

    @Test
    fun `session starts when connected and readings are written`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } returns "TESTVIN123"
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 42L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        DashboardViewModel(obdRepo, sessionRepo, makePrefs())
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { sessionRepo.startSession("TESTVIN123") }
        coVerify { sessionRepo.writeReadings(42L, any()) }
    }

    @Test
    fun `session ends when disconnected`() = runTest {
        val isConnectedFlow = MutableStateFlow(true)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } returns null
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        DashboardViewModel(obdRepo, sessionRepo, makePrefs())
        advanceUntilIdle()
        isConnectedFlow.value = false
        advanceUntilIdle()

        coVerify { sessionRepo.endSession(1L) }
    }

    @Test
    fun `ui state shows readings when connected`() = runTest {
        val reading = makeSensorValue()
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } returns null
            every { livePids(any()) } returns flowOf(listOf(reading))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs())
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isConnected)
        assertEquals(listOf(reading), vm.uiState.value.readings)
    }

    @Test
    fun `session starts with null vin when readVin throws`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } throws Exception("VIN not supported")
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(null) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        DashboardViewModel(obdRepo, sessionRepo, makePrefs())
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { sessionRepo.startSession(null) }
    }

    @Test
    fun `ui state shows error when livePids throws`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } returns null
            every { livePids(any()) } returns flow { throw Exception("BT disconnected") }
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs())
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        assertEquals("BT disconnected", vm.uiState.value.error)
        coVerify { sessionRepo.endSession(1L) }
    }

    @Test
    fun `livePids is called with PIDs from AppPreferences`() = runTest {
        val customIds = setOf(PidRegistry.MAF.id)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } returns null
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(selectedIds = customIds)

        DashboardViewModel(obdRepo, sessionRepo, prefs)
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.livePids(match { it.map { p -> p.id }.toSet() == customIds }) }
    }

    @Test
    fun `reconnect loop starts isReconnecting state before first delay`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { hasLastDevice } returns true
            coEvery { readVin() } returns null
            every { livePids(any()) } returns flow {
                // Simulate what livePids' internal disconnect() does:
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            // reconnect fails so isReconnecting stays true and we can observe the state
            coEvery { reconnect() } throws Exception("reconnect failed")
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs()

        val vm = DashboardViewModel(obdRepo, sessionRepo, prefs)
        isConnectedFlow.value = true
        // Advance 1ms: runs pending work and starts the reconnect loop up to the first
        // delay(3_000L), but does not advance past it.
        advanceTimeBy(1)

        assertTrue(vm.uiState.value.isReconnecting)
        assertEquals(1, vm.uiState.value.reconnectAttempt)
        assertEquals(5, vm.uiState.value.reconnectTotal)
    }

    @Test
    fun `cold-launch reconnect falls back to persisted device via hasLastDevice`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val persistedDevice = mockk<ScannedDevice>()
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            // Cold launch: no prior connect in this session
            every { hasLastDevice } returns false
            coEvery { readVin() } returns null
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            coEvery { connect(persistedDevice) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = mockk<AppPreferences> {
            every { selectedPidIds } returns flowOf(AppPreferences.DEFAULT_PIDS)
            every { lastDevice } returns flowOf(persistedDevice)
        }

        DashboardViewModel(obdRepo, sessionRepo, prefs)
        isConnectedFlow.value = true
        // Advance past the first 3-second delay; cold-launch fallback should kick in
        advanceTimeBy(3_001)

        coVerify { obdRepo.connect(persistedDevice) }
    }

    @Test
    fun `reconnect loop sets permanent error after all attempts fail`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { hasLastDevice } returns true
            coEvery { readVin() } returns null
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            coEvery { reconnect() } throws Exception("reconnect failed")
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs()

        val vm = DashboardViewModel(obdRepo, sessionRepo, prefs)
        isConnectedFlow.value = true
        // Advances through all 5 delays (3+6+12+24+48 = 93 seconds virtual time)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isReconnecting)
        assertEquals("Verbindung dauerhaft unterbrochen.", vm.uiState.value.error)
    }
}
