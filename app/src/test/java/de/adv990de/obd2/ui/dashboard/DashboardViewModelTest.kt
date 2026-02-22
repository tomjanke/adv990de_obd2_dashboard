package de.adv990de.obd2.ui.dashboard

import android.bluetooth.BluetoothAdapter
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText
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
import org.junit.Assert.assertNull
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
        selectedIds: Set<String> = AppPreferences.DEFAULT_PIDS,
        logOnlyDtc: Boolean = false,
        logOnlyDiscovered: Boolean = false,
        loggedPidIds: Set<String> = AppPreferences.ALL_PID_IDS
    ): AppPreferences = mockk {
        every { selectedPidIds }        returns flowOf(selectedIds)
        every { logOnlyDtcSessions }    returns flowOf(logOnlyDtc)
        every { logOnlyDiscoveredPids } returns flowOf(logOnlyDiscovered)
        every { this@mockk.loggedPidIds } returns flowOf(loggedPidIds)
    }

    private fun makeCrashReporter(): CrashReporter = mockk(relaxed = true)

    private fun makeBluetoothAdapter(): BluetoothAdapter = mockk {
        every { isEnabled } returns true
    }

    @Test
    fun `session starts when connected and readings are written`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } returns "TESTVIN123"
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 42L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter())
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
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter())
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
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(listOf(reading))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter())
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
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(null) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter())
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
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flow { throw Exception("BT disconnected") }
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter())
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        assertEquals(UiText.DynamicString("BT disconnected"), vm.uiState.value.error)
        coVerify { sessionRepo.endSession(1L) }
    }

    @Test
    fun `startSession failure sets error state and skips endSession`() = runTest {
        // Verifies: when startSession throws, the generic catch(Exception) handler fires —
        // not the ConnectionLost handler (isReconnecting stays false) — and endSession is
        // never called because sessionId was never assigned (guard: if (sessionId != -1L)).
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } throws Exception("DB failure")
            coEvery { endSession(any()) } just Runs
        }
        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter())
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertEquals(UiText.DynamicString("DB failure"), vm.uiState.value.error)
        assertFalse(vm.uiState.value.isReconnecting)
        coVerify(exactly = 0) { sessionRepo.endSession(any()) }
    }

    @Test
    fun `livePids is called with PIDs from AppPreferences`() = runTest {
        val customIds = setOf(PidRegistry.MAF.id)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(selectedIds = customIds)

        DashboardViewModel(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter())
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.livePids(match { it.map { p -> p.id }.toSet() == customIds }) }
    }

    @Test
    fun `ConnectionLost from livePids triggers reconnect state not generic error`() = runTest {
        // Verifies that ObdException.ConnectionLost (thrown e.g. when BusInit escalates after
        // 5 cycles in livePids) routes to startReconnecting() — isReconnecting=true, error=null —
        // rather than the catch(Exception) handler which would set error=".." instead.
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice>())
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
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
        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter())
        isConnectedFlow.value = true
        advanceTimeBy(1)

        assertTrue(vm.uiState.value.isReconnecting)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `reconnect loop starts isReconnecting state before first delay`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice>())
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
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

        val vm = DashboardViewModel(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter())
        isConnectedFlow.value = true
        // Advance 1ms: runs pending work and starts the reconnect loop up to the first
        // delay(3_000L), but does not advance past it.
        advanceTimeBy(1)

        assertTrue(vm.uiState.value.isReconnecting)
        assertEquals(1, vm.uiState.value.reconnectAttempt)
        assertEquals(5, vm.uiState.value.reconnectTotal)
    }

    @Test
    fun `cold-launch reconnect falls back to persisted device when no in-memory device`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val persistedDevice = mockk<ScannedDevice>()
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            // Cold launch: no prior connect in this session → lastConnectedDevice is null
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
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
            every { selectedPidIds }        returns flowOf(AppPreferences.DEFAULT_PIDS)
            every { lastDevice }            returns flowOf(persistedDevice)
            every { logOnlyDtcSessions }    returns flowOf(false)
            every { logOnlyDiscoveredPids } returns flowOf(false)
            every { loggedPidIds }          returns flowOf(AppPreferences.ALL_PID_IDS)
        }

        DashboardViewModel(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter())
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
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice>())
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
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

        val vm = DashboardViewModel(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter())
        isConnectedFlow.value = true
        // Advances through all 5 delays (3+6+12+24+48 = 93 seconds virtual time)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isReconnecting)
        assertEquals(UiText.StringResource(R.string.err_reconnect_fail), vm.uiState.value.error)
    }

    @Test
    fun `reconnect loop aborts immediately when Bluetooth is disabled`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice>())
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
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
        val btAdapter = mockk<BluetoothAdapter> {
            every { isEnabled } returns false
        }

        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), btAdapter)
        isConnectedFlow.value = true
        // Advance past the first 3-second delay so the BT-disabled check fires
        advanceTimeBy(3_001)

        assertFalse(vm.uiState.value.isReconnecting)
        assertEquals(UiText.StringResource(R.string.obd_err_bluetooth_disabled), vm.uiState.value.error)
    }

    // ── New recording settings tests ──────────────────────────────────────────

    @Test
    fun `logOnlyDtcSessions=true, no DTCs - session is deleted`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()  // no DTCs
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 99L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
            coEvery { deleteSession(any()) } returns null  // Unit? return type
        }

        DashboardViewModel(
            obdRepo, sessionRepo,
            makePrefs(logOnlyDtc = true),
            makeCrashReporter(), makeBluetoothAdapter()
        )
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { sessionRepo.deleteSession(99L) }
        coVerify(exactly = 0) { sessionRepo.endSession(any()) }
    }

    @Test
    fun `logOnlyDtcSessions=true, DTCs present - session is kept`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val dtc = mockk<DtcEntry>()
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns listOf(dtc)  // has DTCs
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 77L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
            coEvery { deleteSession(any()) } returns null  // Unit? return type
        }

        DashboardViewModel(
            obdRepo, sessionRepo,
            makePrefs(logOnlyDtc = true),
            makeCrashReporter(), makeBluetoothAdapter()
        )
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { sessionRepo.endSession(77L) }
        coVerify(exactly = 0) { sessionRepo.deleteSession(any()) }
    }

    @Test
    fun `loggedPidIds filter - writeReadings called with only allowed PIDs`() = runTest {
        val allowedId = PidRegistry.RPM.id
        val blockedId = PidRegistry.SPEED.id
        val allowedReading = SensorValue(pid = PidRegistry.RPM, value = 1500f, timestamp = 1000L)
        val blockedReading  = SensorValue(pid = PidRegistry.SPEED, value = 80f, timestamp = 1001L)

        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(listOf(allowedReading, blockedReading))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 55L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        DashboardViewModel(
            obdRepo, sessionRepo,
            makePrefs(loggedPidIds = setOf(allowedId)),
            makeCrashReporter(), makeBluetoothAdapter()
        )
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify {
            sessionRepo.writeReadings(55L, match { readings ->
                readings.size == 1 && readings[0].pid.id == allowedId
            })
        }
        coVerify(exactly = 0) {
            sessionRepo.writeReadings(any(), match { readings ->
                readings.any { it.pid.id == blockedId }
            })
        }
    }
}
