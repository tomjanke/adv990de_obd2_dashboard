package de.adv990de.obd2.ui.dashboard

import android.bluetooth.BluetoothAdapter
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
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
        slotIds: List<String> = AppPreferences.DEFAULT_SLOT_PIDS,
        logOnlyDtc: Boolean = false,
        logOnlyDiscovered: Boolean = false,
        loggedPidIds: Set<String> = AppPreferences.ALL_PID_IDS
    ): AppPreferences = mockk {
        every { slotPids }              returns flowOf(slotIds)
        every { logOnlyDtcSessions }    returns flowOf(logOnlyDtc)
        every { logOnlyDiscoveredPids } returns flowOf(logOnlyDiscovered)
        every { this@mockk.loggedPidIds } returns flowOf(loggedPidIds)
        every { rpmBlinkThreshold }     returns flowOf(0)
        every { rpmDynamicThreshold }   returns flowOf(false)
        every { lastDevice }            returns flowOf(null)
    }

    private fun makeCrashReporter(): CrashReporter = mockk(relaxed = true)

    private fun makeBluetoothAdapter(): BluetoothAdapter = mockk {
        every { isEnabled } returns true
    }

    private fun makeAppLogRepository(): AppLogRepository = mockk(relaxed = true)

    @Test
    fun `session starts when connected and readings are written`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns "TESTVIN123"
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 42L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
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
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
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
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(listOf(reading))
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
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
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } throws Exception("VIN not supported")
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(emptyList())
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(null) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { sessionRepo.startSession(null) }
    }

    @Test
    fun `ui state shows error when livePids throws`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flow { throw Exception("BT disconnected") }
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
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
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(emptyList())
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } throws Exception("DB failure")
            coEvery { endSession(any()) } just Runs
        }
        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
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
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(emptyList())
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(slotIds = customIds.toList())

        DashboardViewModel(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
        isConnectedFlow.value = true
        advanceUntilIdle()

        // RPM is always force-included in the polled set even when not selected for display
        verify { obdRepo.livePids(match { pids ->
            val ids = pids.map { p -> p.id }.toSet()
            customIds.all { it in ids } && PidRegistry.RPM.id in ids
        }) }
    }

    @Test
    fun `ConnectionLost from livePids triggers reconnect state not generic error`() = runTest {
        // Verifies that ObdException.ConnectionLost (thrown e.g. when BusInit escalates after
        // 5 cycles in livePids) routes to startReconnecting() — isReconnecting=true, error=null —
        // rather than the catch(Exception) handler which would set error=".." instead.
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
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
        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
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
            every { supportedPidIds } returns MutableStateFlow(null)
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

        val vm = DashboardViewModel(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
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
            every { supportedPidIds } returns MutableStateFlow(null)
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
            every { slotPids }              returns flowOf(AppPreferences.DEFAULT_SLOT_PIDS)
            every { lastDevice }            returns flowOf(persistedDevice)
            every { logOnlyDtcSessions }    returns flowOf(false)
            every { logOnlyDiscoveredPids } returns flowOf(false)
            every { loggedPidIds }          returns flowOf(AppPreferences.ALL_PID_IDS)
            every { rpmBlinkThreshold }     returns flowOf(0)
            every { rpmDynamicThreshold }   returns flowOf(false)
        }

        DashboardViewModel(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
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
            every { supportedPidIds } returns MutableStateFlow(null)
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

        val vm = DashboardViewModel(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
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
            every { supportedPidIds } returns MutableStateFlow(null)
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

        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), btAdapter, makeAppLogRepository())
        isConnectedFlow.value = true
        // Advance past the first 3-second delay so the BT-disabled check fires
        advanceTimeBy(3_001)

        assertFalse(vm.uiState.value.isReconnecting)
        assertEquals(UiText.StringResource(R.string.obd_err_bluetooth_disabled), vm.uiState.value.error)
    }

    // ── DTC logging ───────────────────────────────────────────────────────────

    @Test
    fun `active DTCs on connect are logged as WARN`() = runTest {
        val dtc = mockk<DtcEntry>()
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns listOf(dtc)
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val logRepo = mockk<AppLogRepository>(relaxed = true)

        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), logRepo)
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { logRepo.log(LogLevel.WARN, match { it.contains("DTC") || it.contains("dtc", ignoreCase = true) }) }
    }

    @Test
    fun `setMoving true called when Speed PID reading is above zero`() = runTest {
        val speedReading = SensorValue(pid = PidRegistry.SPEED, value = 50f, timestamp = 1000L)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(listOf(speedReading))
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.setMoving(true) }
    }

    @Test
    fun `setMoving false called when Speed PID reading is zero`() = runTest {
        val speedReading = SensorValue(pid = PidRegistry.SPEED, value = 0f, timestamp = 1000L)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(listOf(speedReading))
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = DashboardViewModel(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.setMoving(false) }
    }

    // ── New recording settings tests ──────────────────────────────────────────

    @Test
    fun `logOnlyDtcSessions=true, no DTCs - session is deleted`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()  // no DTCs
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
            every { updateLatestReadings(any()) } just Runs
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
            makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository()
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
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns listOf(dtc)  // has DTCs
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
            every { updateLatestReadings(any()) } just Runs
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
            makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository()
        )
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { sessionRepo.endSession(77L) }
        coVerify(exactly = 0) { sessionRepo.deleteSession(any()) }
    }

    @Test
    fun `rpm is always polled even when not in selected pids`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(emptyList())
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        // Slot contains only SPEED — RPM explicitly excluded
        val prefs = makePrefs(slotIds = listOf(PidRegistry.SPEED.id))

        DashboardViewModel(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
        isConnectedFlow.value = true
        advanceUntilIdle()

        // livePids must have been called with a set that includes RPM
        verify {
            obdRepo.livePids(match { pids -> pids.any { it.id == PidRegistry.RPM.id } })
        }
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
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(listOf(allowedReading, blockedReading))
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 55L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        DashboardViewModel(
            obdRepo, sessionRepo,
            makePrefs(loggedPidIds = setOf(allowedId)),
            makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository()
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

    // ── supportedPidIds filtering ──────────────────────────────────────────────

    @Test
    fun `livePids excludes slot PID when ECU does not support it`() = runTest {
        // COOLANT_TEMP ("0105") is in the slot but NOT in the ECU-supported set.
        // It must therefore be absent from the PID list passed to livePids.
        val unsupportedSlotId = PidRegistry.COOLANT_TEMP.id  // "0105"
        val supportedSet = setOf(PidRegistry.RPM.id, PidRegistry.SPEED.id)  // does NOT contain "0105"

        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(supportedSet)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(emptyList())
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(slotIds = listOf(unsupportedSlotId))

        DashboardViewModel(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.livePids(match { pids ->
            pids.none { it.id == unsupportedSlotId }
        }) }
    }

    @Test
    fun `livePids includes slot PID when supportedPidIds is null`() = runTest {
        // When supportedPidIds is null (disconnected or discovery failed),
        // no filtering is applied and the slot PID must be passed to livePids.
        val slotId = PidRegistry.COOLANT_TEMP.id  // "0105"

        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(emptyList())
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(slotIds = listOf(slotId))

        DashboardViewModel(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.livePids(match { pids ->
            pids.any { it.id == slotId }
        }) }
    }

    @Test
    fun `setSlotPid delegates to AppPreferences with correct slot and pid`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
        }
        val prefs = mockk<AppPreferences> {
            every { slotPids }              returns flowOf(AppPreferences.DEFAULT_SLOT_PIDS)
            every { logOnlyDtcSessions }    returns flowOf(false)
            every { logOnlyDiscoveredPids } returns flowOf(false)
            every { this@mockk.loggedPidIds } returns flowOf(AppPreferences.ALL_PID_IDS)
            every { rpmBlinkThreshold }     returns flowOf(0)
            every { rpmDynamicThreshold }   returns flowOf(false)
            every { lastDevice }            returns flowOf(null)
            coEvery { setSlotPid(any(), any()) } just Runs
        }
        val vm = DashboardViewModel(obdRepo, mockk(relaxed = true), prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
        advanceUntilIdle()

        vm.setSlotPid(2, PidRegistry.MAF.id)
        advanceUntilIdle()

        coVerify { prefs.setSlotPid(2, PidRegistry.MAF.id) }
    }

    // ── Auto-connect on startup ───────────────────────────────────────────────

    @Test
    fun `auto-connect on startup triggers reconnect loop when connect fails`() = runTest {
        val savedDevice = mockk<ScannedDevice> { every { name } returns "TestAdapter" }
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns MutableStateFlow(false)
            every { supportedPidIds } returns MutableStateFlow(null)
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
            coEvery { connect(savedDevice) } throws Exception("BT unavailable")
            // reconnect() must not be called — auto-connect uses connect(), not reconnect()
            coEvery { reconnect() } throws Exception("should not be called")
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = mockk<AppPreferences> {
            every { slotPids }              returns flowOf(AppPreferences.DEFAULT_SLOT_PIDS)
            every { lastDevice }            returns flowOf(savedDevice)
            every { logOnlyDtcSessions }    returns flowOf(false)
            every { logOnlyDiscoveredPids } returns flowOf(false)
            every { loggedPidIds }          returns flowOf(AppPreferences.ALL_PID_IDS)
            every { rpmBlinkThreshold }     returns flowOf(0)
            every { rpmDynamicThreshold }   returns flowOf(false)
        }

        val vm = DashboardViewModel(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository())
        // advanceTimeBy(1) runs all pending coroutines up to the first delay.
        // advanceUntilIdle() would exhaust the entire reconnect backoff (93 s virtual),
        // ending with DashboardUiState.disconnected() → isReconnecting = false.
        advanceTimeBy(1)

        assertTrue("isReconnecting should be true after failed auto-connect", vm.uiState.value.isReconnecting)
        coVerify { obdRepo.connect(savedDevice) }
    }
}
