package de.adv990de.obd2.ui.dashboard

import android.bluetooth.BluetoothAdapter
import android.content.Context
import de.adv990de.obd2.ObdForegroundService
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.location.GpsSpeedSource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
        loggedPidIds: Set<String> = AppPreferences.ALL_PID_IDS,
        useGpsSpeed: Boolean = false,
        wheelCircumferenceMm: Float = AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM,
        primaryRatio: Float = AppPreferences.DEFAULT_PRIMARY_RATIO,
        secondaryRatio: Float = AppPreferences.DEFAULT_SECONDARY_RATIO,
        gearRatios: List<Float> = AppPreferences.DEFAULT_GEAR_RATIOS,
        rpmDynamicThreshold: Boolean = false,
    ): AppPreferences = mockk {
        every { slotPids }              returns flowOf(slotIds)
        every { logOnlyDtcSessions }    returns flowOf(logOnlyDtc)
        every { logOnlyDiscoveredPids } returns flowOf(logOnlyDiscovered)
        every { this@mockk.loggedPidIds } returns flowOf(loggedPidIds)
        every { rpmBlinkThreshold }     returns flowOf(0)
        every { this@mockk.rpmDynamicThreshold } returns flowOf(rpmDynamicThreshold)
        every { lastDevice }            returns flowOf(null)
        every { this@mockk.useGpsSpeed } returns flowOf(useGpsSpeed)
        every { this@mockk.wheelCircumferenceMm } returns flowOf(wheelCircumferenceMm)
        every { this@mockk.primaryRatio }         returns flowOf(primaryRatio)
        every { this@mockk.secondaryRatio }       returns flowOf(secondaryRatio)
        every { this@mockk.gearRatios }           returns flowOf(gearRatios)
    }

    private fun makeCrashReporter(): CrashReporter = mockk(relaxed = true)

    private fun makeBluetoothAdapter(): BluetoothAdapter = mockk {
        every { isEnabled } returns true
    }

    private fun makeAppLogRepository(): AppLogRepository = mockk(relaxed = true)

    /** Ohne GPS-Fix: der Flow liefert null, die Zusammenfuehrung bleibt wirkungslos. */
    private fun makeGpsSpeedSource(kmh: Float? = null): GpsSpeedSource = mockk {
        every { speedKmh } returns flowOf(kmh)
    }

    @Test
    fun `session starts when connected and readings are written`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
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

        makeVmFrom(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { sessionRepo.startSession("TESTVIN123") }
        coVerify { sessionRepo.writeReadings(42L, any()) }
    }

    @Test
    fun `session ends when disconnected`() = runTest {
        val isConnectedFlow = MutableStateFlow(true)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
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

        makeVmFrom(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
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
            every { setMoving(any()) } just Runs
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

        val vm = makeVmFrom(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isConnected)
        assertEquals(listOf(reading), vm.uiState.value.readings)
    }

    @Test
    fun `session starts with null vin when readVin throws`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
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

        makeVmFrom(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { sessionRepo.startSession(null) }
    }

    @Test
    fun `ui state shows error when livePids throws`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
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

        val vm = makeVmFrom(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
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
            every { setMoving(any()) } just Runs
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
        val vm = makeVmFrom(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
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

        makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
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
            every { setMoving(any()) } just Runs
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
        val vm = makeVmFrom(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceTimeBy(1)

        assertTrue(vm.uiState.value.isReconnecting)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `reconnect loop starts isReconnecting state before first delay`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
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

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
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
            every { setMoving(any()) } just Runs
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
            every { useGpsSpeed }            returns flowOf(false)
            every { slotPids }              returns flowOf(AppPreferences.DEFAULT_SLOT_PIDS)
            every { lastDevice }            returns flowOf(persistedDevice)
            every { logOnlyDtcSessions }    returns flowOf(false)
            every { logOnlyDiscoveredPids } returns flowOf(false)
            every { loggedPidIds }          returns flowOf(AppPreferences.ALL_PID_IDS)
            every { rpmBlinkThreshold }     returns flowOf(0)
            every { rpmDynamicThreshold }   returns flowOf(false)
            every { wheelCircumferenceMm } returns flowOf(AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM)
            every { primaryRatio }         returns flowOf(AppPreferences.DEFAULT_PRIMARY_RATIO)
            every { secondaryRatio }       returns flowOf(AppPreferences.DEFAULT_SECONDARY_RATIO)
            every { gearRatios }           returns flowOf(AppPreferences.DEFAULT_GEAR_RATIOS)
        }

        makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        // Advance past the first 3-second delay; cold-launch fallback should kick in
        advanceTimeBy(3_001)

        coVerify { obdRepo.connect(persistedDevice) }
    }

    @Test
    fun `reconnect loop sets permanent error after all attempts fail`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
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

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
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
            every { setMoving(any()) } just Runs
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

        val vm = makeVmFrom(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), btAdapter, makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
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
            every { setMoving(any()) } just Runs
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

        val vm = makeVmFrom(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), logRepo, makeGpsSpeedSource(), mockk(relaxed = true))
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

        val vm = makeVmFrom(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
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

        val vm = makeVmFrom(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.setMoving(false) }
    }

    // ── New recording settings tests ──────────────────────────────────────────

    @Test
    fun `logOnlyDtcSessions=true, no DTCs - session is deleted`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
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

        makeVmFrom(
            obdRepo, sessionRepo,
            makePrefs(logOnlyDtc = true),
            makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(),
            makeGpsSpeedSource(), mockk(relaxed = true)
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
            every { setMoving(any()) } just Runs
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

        makeVmFrom(
            obdRepo, sessionRepo,
            makePrefs(logOnlyDtc = true),
            makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(),
            makeGpsSpeedSource(), mockk(relaxed = true)
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

        makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
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

        makeVmFrom(
            obdRepo, sessionRepo,
            makePrefs(loggedPidIds = setOf(allowedId)),
            makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(),
            makeGpsSpeedSource(), mockk(relaxed = true)
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

        makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
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

        makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.livePids(match { pids ->
            pids.any { it.id == slotId }
        }) }
    }

    @Test
    fun `livePids is called with at least RPM when all slot PIDs are filtered out`() = runTest {
        // Edge case: supportedPidIds is non-null but contains none of the configured slot PIDs.
        // The filtered slot list is empty, but RPM must still be present so the polling loop
        // does not fall back to PidRegistry.defaults and skip the RPM-driven dashboard features.
        val slotId = PidRegistry.COOLANT_TEMP.id  // "0105" — not in the supported set below
        val supportedSet = setOf(PidRegistry.SPEED.id, PidRegistry.MAF.id)  // no overlap with slot

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
        val prefs = makePrefs(slotIds = listOf(slotId))

        makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.livePids(match { pids ->
            pids.none { it.id == slotId } && pids.any { it.id == PidRegistry.RPM.id }
        }) }
    }

    @Test
    fun `setSlotPid delegates to AppPreferences with correct slot and pid`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
        }
        val prefs = mockk<AppPreferences> {
            every { useGpsSpeed }            returns flowOf(false)
            every { slotPids }              returns flowOf(AppPreferences.DEFAULT_SLOT_PIDS)
            every { logOnlyDtcSessions }    returns flowOf(false)
            every { logOnlyDiscoveredPids } returns flowOf(false)
            every { this@mockk.loggedPidIds } returns flowOf(AppPreferences.ALL_PID_IDS)
            every { rpmBlinkThreshold }     returns flowOf(0)
            every { rpmDynamicThreshold }   returns flowOf(false)
            every { lastDevice }            returns flowOf(null)
            every { wheelCircumferenceMm } returns flowOf(AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM)
            every { primaryRatio }         returns flowOf(AppPreferences.DEFAULT_PRIMARY_RATIO)
            every { secondaryRatio }       returns flowOf(AppPreferences.DEFAULT_SECONDARY_RATIO)
            every { gearRatios }           returns flowOf(AppPreferences.DEFAULT_GEAR_RATIOS)
            coEvery { setSlotPid(any(), any()) } just Runs
        }
        val vm = makeVmFrom(obdRepo, mockk(relaxed = true), prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        advanceUntilIdle()

        vm.setSlotPid(2, PidRegistry.MAF.id)
        advanceUntilIdle()

        coVerify { prefs.setSlotPid(2, PidRegistry.MAF.id) }
    }

    // ── availablePids (Picker-Liste) ─────────────────────────────────────────
    // availablePids ist ein WhileSubscribed(5_000)-StateFlow (derselbe Trick wie bei
    // rpmDynamicThreshold oben) — ohne aktiven Collector bliebe `.value` fuer immer auf dem
    // Default (voller PidRegistry-Fallback) stehen, egal was supportedPidIds/useGpsSpeed liefern.

    @Test
    fun `availablePids returns the full registry when supportedPidIds is null`() = runTest {
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
            every { this@mockk.isConnectedFlow } returns MutableStateFlow(false)
            every { supportedPidIds } returns MutableStateFlow(null)
        }

        val vm = makeVmFrom(obdRepo, mockk(relaxed = true), makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.availablePids.collect {} }
        advanceUntilIdle()
        job.cancel()

        val expectedIds = PidRegistry.all.values.map { it.id }.toSet()
        assertEquals(expectedIds, vm.availablePids.value.map { it.id }.toSet())
        assertEquals(PidRegistry.all.size, vm.availablePids.value.size)
    }

    @Test
    fun `availablePids returns the full registry when supportedPidIds is empty`() = runTest {
        // isNullOrEmpty() treats an empty discovered set the same as "no discovery yet" — a
        // vehicle whose discovery legitimately came back empty must still offer the full picker,
        // not an empty one.
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
            every { this@mockk.isConnectedFlow } returns MutableStateFlow(false)
            every { supportedPidIds } returns MutableStateFlow(emptySet())
        }

        val vm = makeVmFrom(obdRepo, mockk(relaxed = true), makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.availablePids.collect {} }
        advanceUntilIdle()
        job.cancel()

        val expectedIds = PidRegistry.all.values.map { it.id }.toSet()
        assertEquals(expectedIds, vm.availablePids.value.map { it.id }.toSet())
    }

    @Test
    fun `availablePids is filtered to exactly the supported subset when GPS speed is off`() = runTest {
        val supported = setOf(PidRegistry.RPM.id, PidRegistry.MAF.id)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
            every { this@mockk.isConnectedFlow } returns MutableStateFlow(false)
            every { supportedPidIds } returns MutableStateFlow(supported)
        }

        val vm = makeVmFrom(obdRepo, mockk(relaxed = true), makePrefs(useGpsSpeed = false), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.availablePids.collect {} }
        advanceUntilIdle()
        job.cancel()

        assertEquals(supported, vm.availablePids.value.map { it.id }.toSet())
    }

    @Test
    fun `availablePids force-includes SPEED when GPS speed is enabled even though the ECU does not support it`() = runTest {
        // Die GPS-Ausnahme aus der Produktionslogik: SPEED bleibt waehlbar, weil das GPS sie
        // liefern kann, unabhaengig davon, ob das Fahrzeug den PID selbst unterstuetzt.
        val supported = setOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id) // SPEED absichtlich nicht enthalten
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
            every { this@mockk.isConnectedFlow } returns MutableStateFlow(false)
            every { supportedPidIds } returns MutableStateFlow(supported)
        }

        val vm = makeVmFrom(obdRepo, mockk(relaxed = true), makePrefs(useGpsSpeed = true), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.availablePids.collect {} }
        advanceUntilIdle()
        job.cancel()

        val resultIds = vm.availablePids.value.map { it.id }.toSet()
        assertEquals(supported + PidRegistry.SPEED.id, resultIds)
    }

    @Test
    fun `availablePids excludes SPEED when GPS speed is disabled and the ECU does not support it`() = runTest {
        // Kontrastfall zum GPS-Carve-out oben: ohne GPS-Speed und ohne ECU-Unterstuetzung darf
        // SPEED nicht im Picker auftauchen.
        val supported = setOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id) // SPEED absichtlich nicht enthalten
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
            every { this@mockk.isConnectedFlow } returns MutableStateFlow(false)
            every { supportedPidIds } returns MutableStateFlow(supported)
        }

        val vm = makeVmFrom(obdRepo, mockk(relaxed = true), makePrefs(useGpsSpeed = false), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.availablePids.collect {} }
        advanceUntilIdle()
        job.cancel()

        val resultIds = vm.availablePids.value.map { it.id }.toSet()
        assertEquals(supported, resultIds)
        assertFalse(PidRegistry.SPEED.id in resultIds)
    }

    // ── Auto-connect on startup ───────────────────────────────────────────────

    @Test
    fun `auto-connect on startup triggers reconnect loop when connect fails`() = runTest {
        val savedDevice = mockk<ScannedDevice> { every { name } returns "TestAdapter" }
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
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
            every { useGpsSpeed }            returns flowOf(false)
            every { slotPids }              returns flowOf(AppPreferences.DEFAULT_SLOT_PIDS)
            every { lastDevice }            returns flowOf(savedDevice)
            every { logOnlyDtcSessions }    returns flowOf(false)
            every { logOnlyDiscoveredPids } returns flowOf(false)
            every { loggedPidIds }          returns flowOf(AppPreferences.ALL_PID_IDS)
            every { rpmBlinkThreshold }     returns flowOf(0)
            every { rpmDynamicThreshold }   returns flowOf(false)
            every { wheelCircumferenceMm } returns flowOf(AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM)
            every { primaryRatio }         returns flowOf(AppPreferences.DEFAULT_PRIMARY_RATIO)
            every { secondaryRatio }       returns flowOf(AppPreferences.DEFAULT_SECONDARY_RATIO)
            every { gearRatios }           returns flowOf(AppPreferences.DEFAULT_GEAR_RATIOS)
        }

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        // advanceTimeBy(1) runs all pending coroutines up to the first delay.
        // advanceUntilIdle() would exhaust the entire reconnect backoff (93 s virtual),
        // ending with DashboardUiState.disconnected() → isReconnecting = false.
        advanceTimeBy(1)

        assertTrue("isReconnecting should be true after failed auto-connect", vm.uiState.value.isReconnecting)
        coVerify { obdRepo.connect(savedDevice) }
    }

    // ── makeVm() helper — used by foreground-service tests ────────────────────
    // Wraps the full 7-parameter constructor so new tests can be written concisely.
    // Parameters that are not relevant to a specific test keep their defaults.
    private fun makeVm(
        obdRepo: ObdRepository = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns MutableStateFlow(false)
            every { supportedPidIds } returns MutableStateFlow(null)
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(emptyList())
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        },
        sessionRepo: SessionRepository = mockk {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        },
        prefs: AppPreferences = makePrefs(),
        crashReporter: CrashReporter = makeCrashReporter(),
        btAdapter: BluetoothAdapter = makeBluetoothAdapter(),
        logRepo: AppLogRepository = makeAppLogRepository(),
        appContext: Context = mockk(relaxed = true)
    ): DashboardViewModel = makeVmFrom(
        obdRepo, sessionRepo, prefs, crashReporter, btAdapter, logRepo, makeGpsSpeedSource(), appContext
    )

    // ── Foreground service lifecycle ──────────────────────────────────────────

    @Test
    fun `service is started when auto-connect succeeds on startup`() = runTest {
        val savedDevice = mockk<ScannedDevice> { every { name } returns "TestAdapter" }
        val ctx = mockk<Context>(relaxed = true)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns MutableStateFlow(false)
            every { supportedPidIds } returns MutableStateFlow(null)
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
            coEvery { connect(savedDevice) } just Runs
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
        val prefs = mockk<AppPreferences> {
            every { useGpsSpeed }            returns flowOf(false)
            every { slotPids }              returns flowOf(AppPreferences.DEFAULT_SLOT_PIDS)
            every { lastDevice }            returns flowOf(savedDevice)
            every { logOnlyDtcSessions }    returns flowOf(false)
            every { logOnlyDiscoveredPids } returns flowOf(false)
            every { loggedPidIds }          returns flowOf(AppPreferences.ALL_PID_IDS)
            every { rpmBlinkThreshold }     returns flowOf(0)
            every { rpmDynamicThreshold }   returns flowOf(false)
            every { wheelCircumferenceMm } returns flowOf(AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM)
            every { primaryRatio }         returns flowOf(AppPreferences.DEFAULT_PRIMARY_RATIO)
            every { secondaryRatio }       returns flowOf(AppPreferences.DEFAULT_SECONDARY_RATIO)
            every { gearRatios }           returns flowOf(AppPreferences.DEFAULT_GEAR_RATIOS)
        }

        makeVm(obdRepo = obdRepo, sessionRepo = sessionRepo, prefs = prefs, appContext = ctx)
        advanceUntilIdle()

        verify { ctx.startForegroundService(any()) }
    }

    @Test
    fun `service is stopped when reconnect loop exhausts all attempts`() = runTest {
        val ctx = mockk<Context>(relaxed = true)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice>())
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            coEvery { reconnect() } throws Exception("fail")
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        makeVm(obdRepo = obdRepo, sessionRepo = sessionRepo, appContext = ctx)
        isConnectedFlow.value = true
        // Advances through all 5 delays (3+6+12+24+48 = 93 seconds virtual time)
        advanceUntilIdle()

        verify { ctx.stopService(any()) }
    }

    @Test
    fun `service is started when reconnect loop succeeds`() = runTest {
        val ctx = mockk<Context>(relaxed = true)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice> { every { name } returns "TestAdapter" })
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            // First attempt fails, second succeeds
            coEvery { reconnect() } throws Exception("fail") andThen Unit
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        makeVm(obdRepo = obdRepo, sessionRepo = sessionRepo, appContext = ctx)
        isConnectedFlow.value = true
        // Advance past the first delay (3 s) so attempt 1 fails, then past second (6 s)
        // so attempt 2 succeeds — total 3_001 + 6_001 = 9_002 ms virtual time is enough,
        // but advanceUntilIdle() is simpler and exhausts virtual time until reconnect succeeds.
        advanceTimeBy(3_001 + 6_001)

        verify { ctx.startForegroundService(any()) }
    }

    @Test
    fun `service is stopped when no device is configured`() = runTest {
        val ctx = mockk<Context>(relaxed = true)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
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
        }
        // makePrefs() already stubs lastDevice as flowOf(null) → no persisted device either
        makeVm(obdRepo = obdRepo, prefs = makePrefs(), appContext = ctx)
        isConnectedFlow.value = true
        // advanceTimeBy(1) runs all coroutines up to the first suspension point,
        // which is enough for the reconnect loop to check both device sources and abort.
        advanceTimeBy(1)

        verify { ctx.stopService(any()) }
    }

    @Test
    fun `setMoving false called when SPEED is not in selected PIDs`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(emptyList())
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        // Slot contains only RPM — SPEED is explicitly absent
        val prefs = makePrefs(slotIds = listOf(PidRegistry.RPM.id))

        makeVm(obdRepo = obdRepo, prefs = prefs)
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.setMoving(false) }
    }

    @Test
    fun `uiState error set to no-device banner when no device is configured`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
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
        }
        // makePrefs() already stubs lastDevice as flowOf(null) → no persisted device
        val vm = makeVm(obdRepo = obdRepo, prefs = makePrefs())
        isConnectedFlow.value = true
        // advanceTimeBy(1) runs coroutines up to the first suspension point,
        // enough for the reconnect loop to check both device sources and abort.
        advanceTimeBy(1)

        assertEquals(UiText.StringResource(R.string.err_no_device), vm.uiState.value.error)
    }

    @Test
    fun `service is stopped when Bluetooth is disabled during reconnect`() = runTest {
        val ctx = mockk<Context>(relaxed = true)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
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

        makeVm(obdRepo = obdRepo, sessionRepo = sessionRepo, btAdapter = btAdapter, appContext = ctx)
        isConnectedFlow.value = true
        // Advance past the first 3-second delay so the BT-disabled check fires
        advanceTimeBy(3_001)

        verify { ctx.stopService(any()) }
    }
    // --- GPS-Geschwindigkeit ---

    @Test
    fun `GPS speed reaches the ui state when the vehicle provides none`() = runTest {
        // Regression: gpsSpeedKmh war ein WhileSubscribed-StateFlow, den niemand sammelte.
        // Sein value blieb deshalb fuer immer null und die GPS-Geschwindigkeit kam nie an.
        val rpm = SensorValue(pid = PidRegistry.RPM, value = 1500f, timestamp = 1000L)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(setOf(PidRegistry.RPM.id))
            every { setMoving(any()) } just Runs
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(listOf(rpm))
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = makeVmFrom(
            obdRepo, sessionRepo,
            makePrefs(slotIds = listOf(PidRegistry.RPM.id, PidRegistry.SPEED.id, "", ""), useGpsSpeed = true),
            makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(),
            makeGpsSpeedSource(42f), mockk(relaxed = true)
        )
        isConnectedFlow.value = true
        advanceUntilIdle()

        val speed = vm.uiState.value.readings.firstOrNull { it.pid.id == PidRegistry.SPEED.id }
        assertNotNull(speed)
        assertEquals(42f, speed!!.value, 0.001f)
        verify { obdRepo.setMoving(true) }
    }

    @Test
    fun `GPS speed is ignored while the switch is off`() = runTest {
        val rpm = SensorValue(pid = PidRegistry.RPM, value = 1500f, timestamp = 1000L)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(setOf(PidRegistry.RPM.id))
            every { setMoving(any()) } just Runs
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(listOf(rpm))
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = makeVmFrom(
            obdRepo, sessionRepo,
            makePrefs(slotIds = listOf(PidRegistry.RPM.id, PidRegistry.SPEED.id, "", ""), useGpsSpeed = false),
            makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(),
            makeGpsSpeedSource(42f), mockk(relaxed = true)
        )
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertNull(vm.uiState.value.readings.firstOrNull { it.pid.id == PidRegistry.SPEED.id })
    }

    // ── Virtuelle Ganganzeige ─────────────────────────────────────────────────

    @Test
    fun `speedKmh is extracted independent of slot assignment`() = runTest {
        // Speed is NOT one of the default slot PIDs, so state.readings would never carry it —
        // speedKmh must still be populated.
        val speedReading = SensorValue(pid = PidRegistry.SPEED, value = 42f, timestamp = 1000L)
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
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

        val vm = makeVmFrom(obdRepo, sessionRepo, makePrefs(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(42f, vm.uiState.value.speedKmh!!, 0.001f)
    }

    @Test
    fun `gear is computed from rpm and GPS speed using the configured ratios`() = runTest {
        // 1x1 primary/secondary, wheel circumference 2000mm, single gear ratio 10 -> at 60 km/h
        // (wheelRpm 500) an engine speed of 5000 rpm hits gear 1 exactly. Speed comes from GPS
        // only, matching the fix in v1.16.6 — the vehicle reports no SPEED PID here at all.
        val readings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
        )
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(readings)
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(
            useGpsSpeed = true,
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f, 5f, 3f, 2f, 1.5f, 1.2f),
        )

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(kmh = 60f), mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.gear)
    }

    @Test
    fun `gear ignores a vehicle-reported speed and uses GPS instead`() = runTest {
        // v1.16.6 fix: determineGear() must be fed the GPS speed exclusively, never the
        // (possibly vehicle-sourced) merged SPEED PID value. The vehicle reports 999 km/h here —
        // a value that matches none of the configured ratios even remotely — while GPS reports
        // 60 km/h, which hits gear 1 exactly (same fixture as the test above). If this ever
        // regresses back to reading the merged SPEED PID, gear would come out null instead of 1.
        val readings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
            SensorValue(pid = PidRegistry.SPEED, value = 999f, timestamp = 1000L),
        )
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(readings)
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(
            useGpsSpeed = true,
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f, 5f, 3f, 2f, 1.5f, 1.2f),
        )

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(kmh = 60f), mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.gear)
    }

    @Test
    fun `gear is unknown when GPS has no fix, even if the vehicle reports a plausible speed`() = runTest {
        // Mirror of the test above: the vehicle reports 60 km/h (which would hit gear 1 if it
        // were used), but GPS has no fix at all. determineGear() must not fall back to the
        // vehicle's speed — the gear stays unknown.
        val readings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
            SensorValue(pid = PidRegistry.SPEED, value = 60f, timestamp = 1000L),
        )
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(readings)
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(
            useGpsSpeed = true,
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f, 5f, 3f, 2f, 1.5f, 1.2f),
        )

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(kmh = null), mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(null, vm.uiState.value.gear)
    }

    @Test
    fun `gear stays locked across updates via hysteresis lastGear wiring`() = runTest {
        // Finding 3 (final review): the two pre-existing gear tests each use a SINGLE emission,
        // so neither actually proves that determineGear() is threaded with the correct lastGear
        // on the NEXT update. A regression that silently passed lastGear = null on every call
        // (defeating hysteresis entirely) would not be caught by anything else in this suite.
        //
        // Same fixture style as `gear is computed from rpm and GPS speed using the configured
        // ratios`: 1x1 primary/secondary, wheel circumference 2000mm -> wheelRpm = speedKmh *
        // 8.3333. Speed comes from GPS (v1.16.6 fix), constant at 60 km/h across both emissions —
        // only rpm changes between them.
        //
        // 1st emission: rpm=5000, speed=60 -> wheelRpm=500 -> actualRatio=10.0 -> matches gear 1
        // (ratio 10) exactly -> gear = 1.
        // 2nd emission: rpm=4810, speed=60 -> wheelRpm=500 -> actualRatio=9.62 -> matches gear 2
        // (ratio 9.62, deliberately placed close to gear 1) exactly, deviation 0. Deviation from
        // gear 1's own ratio is |9.62-10|/10 = 3.8%, comfortably under STAY_TOLERANCE (6%).
        // With correct hysteresis wiring (lastGear=1 threaded from the 1st update), the gear
        // stays 1. Without it (lastGear reset to null / not threaded), the closest-match fallback
        // would pick gear 2 (deviation 0% beats gear 1's 3.8%) -> the assertion below would fail.
        val firstReadings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
        )
        val secondReadings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 4810f, timestamp = 2000L),
        )
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(firstReadings, secondReadings)
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(
            useGpsSpeed = true,
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f, 9.62f, 5f, 3f, 2f, 1.5f),
        )

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(kmh = 60f), mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.gear)
    }

    @Test
    fun `gear holds through a single implausible reading instead of dropping to unknown`() = runTest {
        // User feedback after a real test ride (v1.16.7): pulling the clutch or closing the
        // throttle briefly decouples engine rpm from road speed, so determineGear() legitimately
        // returns null for that one cycle even though the vehicle is still clearly moving. Without
        // holdGear(), that null would (a) blank the display and (b) erase the hysteresis memory
        // (lastGear back to null), forcing a full re-lock via the stricter ENTER_TOLERANCE on the
        // next reading instead of the looser STAY_TOLERANCE.
        //
        // Same fixture as `gear is computed from rpm and GPS speed using the configured ratios`:
        // 1x1 primary/secondary, wheel circumference 2000mm -> wheelRpm = 500 at speed=60 (GPS,
        // held constant across all three emissions).
        //
        // 1st emission: rpm=5000 -> actualRatio=10.0 -> exact match, gear locks to 1.
        // 2nd emission: rpm=100 (idle, as if the clutch were pulled) -> actualRatio=0.2 -> miles
        // off every configured ratio -> determineGear() returns null regardless of lastGear. This
        // is the implausible reading itself.
        // 3rd emission: rpm=4750 -> actualRatio=9.5, a 5% deviation from gear 1's ratio (10) —
        // between ENTER_TOLERANCE (4%) and STAY_TOLERANCE (6%) on purpose. If the 2nd emission's
        // held gear survived, lastGear=1 lets the STAY_TOLERANCE path (5% <= 6%) reconfirm gear 1.
        // If it didn't (holdGear regressed back to `newGear = computedGear`), lastGear resets to
        // null and only the stricter ENTER_TOLERANCE path runs — 5% > 4% fails it, so the gear
        // would come out null instead of 1.
        val readings = listOf(
            listOf(SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L)),
            listOf(SensorValue(pid = PidRegistry.RPM, value = 100f, timestamp = 2000L)),
            listOf(SensorValue(pid = PidRegistry.RPM, value = 4750f, timestamp = 3000L)),
        )
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(readings[0], readings[1], readings[2])
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(
            useGpsSpeed = true,
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f, 5f, 3f, 2f, 1.5f, 1.2f),
        )

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(kmh = 60f), mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.gear)
    }

    @Test
    fun `gear clears to unknown when speed drops below the minimum, not just held`() = runTest {
        // Counterpart to the hold test above: holdGear() must still clear the display once the
        // vehicle is genuinely stopped (or GPS has no fix), not hold onto a stale gear forever.
        // Same fixture: gear locks to 1 at speed=60, then GPS reports 5 km/h (below
        // MIN_SPEED_KMH=8.0) — the held gear must be cleared, not kept.
        //
        // Both source flows carry an explicit delay so the ordering is deterministic under
        // virtual time: GPS drops to 5 km/h at t=100, strictly before the second OBD reading at
        // t=200, so that reading is guaranteed to see the already-lowered speed.
        val firstReadings = listOf(SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L))
        val secondReadings = listOf(SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 2000L))
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flow {
                emit(firstReadings)
                delay(200)
                emit(secondReadings)
            }
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(
            useGpsSpeed = true,
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f, 5f, 3f, 2f, 1.5f, 1.2f),
        )
        val gps: GpsSpeedSource = mockk {
            every { speedKmh } returns flow {
                emit(60f)
                delay(100)
                emit(5f)
            }
        }

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), gps, mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(null, vm.uiState.value.gear)
    }

    @Test
    fun `gear resets to null after a disconnect during reconnect handling`() = runTest {
        // Finding 2/3 (final review): applyIdle()'s "error or reconnecting" branch used to leave
        // speedKmh/gear untouched, so a stale, never-revalidated gear survived a disconnect and
        // silently biased the hysteresis once the vehicle reconnected. This proves the reset.
        //
        // A first version of this test used the REAL ConnectionCoordinator (via makeVmFrom), like
        // `ConnectionLost from livePids triggers reconnect state not generic error` does. That
        // turned out to be a false positive: the real reconnect loop fires
        // ConnectionEvent.Attempting(attempt=1) so promptly that onConnectionEvent's "attempt != 0"
        // branch already replaces the whole state with a FRESH DashboardUiState (gear/speedKmh
        // defaulting to null) before the Idle event — and therefore applyIdle() — ever runs.
        // Confirmed empirically: reverting applyIdle()'s explicit reset left this assertion
        // passing anyway. A relaxed ConnectionCoordinator mock makes start()/requestReconnect()
        // no-ops, so onConnectionEvent is never invoked at all — isReconnecting is set ONLY by
        // startReconnecting()'s own `.copy(isReconnecting = true, ...)` (which preserves gear/
        // speedKmh unchanged), isolating applyIdle()'s own reset as the only thing that can still
        // null them here.
        //
        // isConnectedFlow is still flipped to false FROM WITHIN the livePids flow, in the same
        // call frame as the ConnectionLost throw — same ordering trick as the real-coordinator
        // test above, so startReconnecting() still runs before the Idle event that follows from
        // the outer combine()/flatMapLatest re-subscription.
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { setMoving(any()) } just Runs
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flow {
                // First, a normal cycle that locks in a gear...
                emit(
                    listOf(
                        SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
                        SensorValue(pid = PidRegistry.SPEED, value = 60f, timestamp = 1000L),
                    )
                )
                // ...then the connection drops.
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f, 5f, 3f, 2f, 1.5f, 1.2f),
        )

        val vm = DashboardViewModel(
            obdRepo,
            prefs,
            mockk<ConnectionCoordinator>(relaxed = true),
            ReadingsPipeline(obdRepo, sessionRepo, prefs, makeAppLogRepository(), makeCrashReporter(), makeGpsSpeedSource()),
        )
        isConnectedFlow.value = true
        advanceTimeBy(1)

        assertTrue(vm.uiState.value.isReconnecting)
        assertNull(vm.uiState.value.gear)
        assertNull(vm.uiState.value.speedKmh)
    }

    @Test
    fun `speedKmh and gear become null when a cycle omits the SPEED reading`() = runTest {
        // Finding 2 (final review): applyReadings() used to fall back to the previous speedKmh
        // whenever a cycle's readings omitted SPEED (`?: _uiState.value.speedKmh`), silently
        // keeping a stale GPS speed forever once GPS stopped reporting one, and feeding
        // determineGear() with data the vehicle/GPS source no longer actually provides. This
        // proves the fallback is gone: unlike rpmValue (which legitimately keeps its own
        // last-known-value fallback — RPM is polled every OBD cycle, so a single miss really is a
        // transient gap), a missing SPEED must make speedKmh — and therefore gear — null
        // immediately, not on some delay.
        val firstReadings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
            SensorValue(pid = PidRegistry.SPEED, value = 60f, timestamp = 1000L),
        )
        val secondReadings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 2000L),
            // No SPEED this cycle — e.g. GPS lost its fix.
        )
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(firstReadings, secondReadings)
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f, 5f, 3f, 2f, 1.5f, 1.2f),
        )

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        advanceUntilIdle()

        assertNull(vm.uiState.value.speedKmh)
        assertNull(vm.uiState.value.gear)
    }

    // ── Motortemperatur-Ableitung (rpmDynamicThreshold) ─────────────────────────
    // rpmDynamicThreshold ist ein WhileSubscribed(5_000)-StateFlow (anders als gearRatios,
    // s. dessen Kommentar oben) — ohne aktiven Collector bliebe `.value` fuer immer auf dem
    // Default (false) stehen, egal was AppPreferences liefert. Denselben Trick nutzt bereits
    // `DashboardConfigViewModelTest` ("rpmDynamicThreshold reflects AppPreferences flow"):
    // die StateFlow selbst kollektieren, bevor der Messwert-Zyklus laeuft.

    @Test
    fun `engineTempC stays null when rpmDynamicThreshold is disabled`() = runTest {
        // rpmDynamicThreshold=false: applyReadings() darf engineTempC gar nicht erst berechnen,
        // selbst wenn sowohl ein plausibler Oel- als auch Kuehlwasserwert vorliegen.
        val readings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
            SensorValue(pid = PidRegistry.ENGINE_OIL_TEMP, value = 95f, timestamp = 1000L),
            SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 88f, timestamp = 1000L),
        )
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(readings)
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(rpmDynamicThreshold = false)

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.rpmDynamicThreshold.collect {} }
        advanceUntilIdle()
        isConnectedFlow.value = true
        advanceUntilIdle()
        job.cancel()

        assertNull(vm.uiState.value.engineTempC)
    }

    @Test
    fun `engineTempC uses coolant temp when it is the only plausible reading`() = runTest {
        val readings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
            SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 88f, timestamp = 1000L),
        )
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(readings)
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(rpmDynamicThreshold = true)

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.rpmDynamicThreshold.collect {} }
        advanceUntilIdle()
        isConnectedFlow.value = true
        advanceUntilIdle()
        job.cancel()

        assertEquals(88f, vm.uiState.value.engineTempC!!, 0.001f)
    }

    @Test
    fun `engineTempC prefers oil temp over coolant temp when both are present and plausible`() = runTest {
        // Beide Werte plausibel und unterscheidbar (95 vs. 88) — die Assertion belegt damit
        // konkret, welcher der beiden gewinnt, nicht nur dass irgendein Wert ankommt.
        val readings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
            SensorValue(pid = PidRegistry.ENGINE_OIL_TEMP, value = 95f, timestamp = 1000L),
            SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 88f, timestamp = 1000L),
        )
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(readings)
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(rpmDynamicThreshold = true)

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.rpmDynamicThreshold.collect {} }
        advanceUntilIdle()
        isConnectedFlow.value = true
        advanceUntilIdle()
        job.cancel()

        assertEquals(95f, vm.uiState.value.engineTempC!!, 0.001f)
    }

    @Test
    fun `engineTempC falls back to coolant temp when oil temp reading is not finite`() = runTest {
        // NaN ist der Sentinel, den isPlausible() (value.isFinite()) ablehnt — z.B. eine
        // fehlerhaft geparste PID-Antwort. Der Oelwert liegt zwar vor, zaehlt aber nicht als
        // plausibel, daher muss der Kuehlwasserwert greifen statt null.
        val readings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
            SensorValue(pid = PidRegistry.ENGINE_OIL_TEMP, value = Float.NaN, timestamp = 1000L),
            SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 88f, timestamp = 1000L),
        )
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(readings)
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(rpmDynamicThreshold = true)

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.rpmDynamicThreshold.collect {} }
        advanceUntilIdle()
        isConnectedFlow.value = true
        advanceUntilIdle()
        job.cancel()

        assertEquals(88f, vm.uiState.value.engineTempC!!, 0.001f)
    }

    @Test
    fun `engineTempC is null when neither oil nor coolant temp reading is plausible`() = runTest {
        // Beide Werte liegen vor, sind aber beide NaN (implausibel) — der letzte Fallback ist
        // null, nicht etwa ein stehen gebliebener alter Wert.
        val readings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
            SensorValue(pid = PidRegistry.ENGINE_OIL_TEMP, value = Float.NaN, timestamp = 1000L),
            SensorValue(pid = PidRegistry.COOLANT_TEMP, value = Float.NaN, timestamp = 1000L),
        )
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(null)
            coEvery { readVin() } returns null
            coEvery { readDtcs() } returns emptyList()
            every { livePids(any()) } returns flowOf(readings)
            every { setMoving(any()) } just Runs
            every { updateLatestReadings(any()) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = makePrefs(rpmDynamicThreshold = true)

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.rpmDynamicThreshold.collect {} }
        advanceUntilIdle()
        isConnectedFlow.value = true
        advanceUntilIdle()
        job.cancel()

        assertNull(vm.uiState.value.engineTempC)
    }

    /**
     * Baut das ViewModel aus denselben Bausteinen wie Hilt.
     *
     * Seit 1.11.0 bekommt [DashboardViewModel] nur noch Repository, Praeferenzen und die beiden
     * herausgeloesten Kollaborateure. Die Tests hier pruefen weiterhin das Zusammenspiel und
     * uebergeben deshalb die feingranularen Mocks — die eigenen Tests der Kollaborateure stehen
     * in `ConnectionCoordinatorTest` und `ReadingsPipelineTest`.
     */
    private fun makeVmFrom(
        obdRepo: ObdRepository,
        sessionRepo: SessionRepository,
        prefs: AppPreferences,
        crashReporter: CrashReporter,
        btAdapter: BluetoothAdapter,
        logRepo: AppLogRepository,
        gpsSource: GpsSpeedSource,
        appContext: Context,
    ): DashboardViewModel = DashboardViewModel(
        obdRepo,
        prefs,
        ConnectionCoordinator(obdRepo, prefs, logRepo, btAdapter, appContext),
        ReadingsPipeline(obdRepo, sessionRepo, prefs, logRepo, crashReporter, gpsSource),
    )
}