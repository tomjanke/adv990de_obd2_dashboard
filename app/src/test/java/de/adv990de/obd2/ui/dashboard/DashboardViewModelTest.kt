package de.adv990de.obd2.ui.dashboard

import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.fuel.FuelDistanceSink
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.fakeObdRepository
import de.adv990de.obd2.location.GpsSpeedSource
import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.obd.model.ValueSource
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.TestScope
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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } returns "TESTVIN123"
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 42L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { sessionRepo.startSession("TESTVIN123", any()) }
        coVerify { sessionRepo.writeReadings(42L, any()) }
    }

    @Test
    fun `session ends when disconnected`() = runTest {
        val isConnectedFlow = MutableStateFlow(true)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        advanceUntilIdle()
        isConnectedFlow.value = false
        advanceUntilIdle()

        coVerify { sessionRepo.endSession(1L) }
    }

    @Test
    fun `ui state shows readings when connected`() = runTest {
        val reading = makeSensorValue()
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(listOf(reading))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isConnected)
        assertEquals(listOf(reading), vm.uiState.value.readings)
    }

    @Test
    fun `session starts with null vin when readVin throws`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            coEvery { readVin() } throws Exception("VIN not supported")
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(null, any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { sessionRepo.startSession(null, any()) }
    }

    @Test
    fun `ui state shows error when livePids throws`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flow { throw Exception("BT disconnected") }
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        assertEquals(UiText.DynamicString("BT disconnected"), vm.uiState.value.error)
        coVerify { sessionRepo.endSession(1L) }
    }

    @Test
    fun `startSession failure does not end the live display and skips endSession`() = runTest {
        // Aufzeichnen ist Beiwerk der Live-Anzeige: scheitert das Anlegen der Fahrt (Platte voll, Datenbank
        // gesperrt), laeuft die Anzeige weiter — kein Fehlerzustand, kein Reconnect — und endSession wird nie
        // gerufen, weil keine Fahrt existiert (Guard: if (sessionId != -1L)). Frueher beendete die
        // generische catch(Exception)-Behandlung die ganze Sitzung mit einer Fehlermeldung.
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } throws Exception("DB failure")
            coEvery { endSession(any()) } just Runs
        }
        val vm = makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isReconnecting)
        coVerify(exactly = 0) { sessionRepo.endSession(any()) }
        coVerify(exactly = 0) { sessionRepo.writeReadings(any(), any()) }
        verify { obdRepo.livePids(any()) } // die Messung lief trotz fehlgeschlagener Aufzeichnung an
    }

    @Test
    fun `livePids is called with PIDs from AppPreferences`() = runTest {
        val customIds = setOf(PidRegistry.MAF.id)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(slotPids = customIds.toList())

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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice>())
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            coEvery { reconnect() } throws Exception("reconnect failed")
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val vm = makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceTimeBy(1)

        assertTrue(vm.uiState.value.isReconnecting)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `reconnect loop starts isReconnecting state before first delay`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice>())
            every { livePids(any()) } returns flow {
                // Simulate what livePids' internal disconnect() does:
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            // reconnect fails so isReconnecting stays true and we can observe the state
            coEvery { reconnect() } throws Exception("reconnect failed")
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences()

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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            // Cold launch: no prior connect in this session → lastConnectedDevice is null
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            coEvery { connectIfDisconnected(persistedDevice) } just Runs
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(lastDevice = persistedDevice)

        makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        // Advance past the first 3-second delay; cold-launch fallback should kick in
        advanceTimeBy(3_001)

        coVerify { obdRepo.connectIfDisconnected(persistedDevice) }
    }

    @Test
    fun `reconnect loop sets permanent error after all attempts fail`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice>())
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            coEvery { reconnect() } throws Exception("reconnect failed")
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences()

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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice>())
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            coEvery { reconnect() } throws Exception("reconnect failed")
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val btAdapter = mockk<BluetoothAdapter> {
            every { isEnabled } returns false
        }

        val vm = makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), btAdapter, makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        // Advance past the first 3-second delay so the BT-disabled check fires
        advanceTimeBy(3_001)

        assertFalse(vm.uiState.value.isReconnecting)
        assertEquals(UiText.StringResource(R.string.obd_err_bluetooth_disabled), vm.uiState.value.error)
    }

    // ── DTC logging ───────────────────────────────────────────────────────────

    @Test
    fun `active DTCs on connect are logged as WARN`() = runTest {
        val dtc = DtcEntry("P0301", isPending = false)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            coEvery { readDtcs() } returns listOf(dtc)
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val logRepo = mockk<AppLogRepository>(relaxed = true)

        val vm = makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), logRepo, makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { logRepo.log(LogLevel.WARN, match { it.contains("DTC") || it.contains("dtc", ignoreCase = true) }) }
    }

    @Test
    fun `setMoving true called when Speed PID reading is above zero`() = runTest {
        val speedReading = SensorValue(pid = PidRegistry.SPEED, value = 50f, timestamp = 1000L)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(listOf(speedReading))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.setMoving(true) }
    }

    @Test
    fun `setMoving false called when Speed PID reading is zero`() = runTest {
        val speedReading = SensorValue(pid = PidRegistry.SPEED, value = 0f, timestamp = 1000L)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(listOf(speedReading))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.setMoving(false) }
    }

    // ── New recording settings tests ──────────────────────────────────────────

    @Test
    fun `logOnlyDtcSessions=true, no DTCs - session is deleted`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            coEvery { readDtcs() } returns emptyList()  // no DTCs
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 99L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
            coEvery { discardSession(any()) } just Runs
        }

        makeVmFrom(
            obdRepo, sessionRepo,
            fakeAppPreferences(logOnlyDtcSessions = true),
            makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(),
            makeGpsSpeedSource(), mockk(relaxed = true)
        )
        isConnectedFlow.value = true
        advanceUntilIdle()

        // Die Entscheidung steht vor dem Start fest: keine Fahrt anlegen, statt aufzuzeichnen und zu verwerfen.
        coVerify(exactly = 0) { sessionRepo.startSession(any(), any()) }
        coVerify(exactly = 0) { sessionRepo.writeReadings(any(), any()) }
        coVerify(exactly = 0) { sessionRepo.discardSession(any()) }
        coVerify(exactly = 0) { sessionRepo.endSession(any()) }
    }

    @Test
    fun `logOnlyDtcSessions=true, DTCs present - session is kept`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val dtc = DtcEntry("P0301", isPending = false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            coEvery { readDtcs() } returns listOf(dtc)  // has DTCs
            every { livePids(any()) } returns flowOf(listOf(makeSensorValue()))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 77L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
            coEvery { discardSession(any()) } just Runs
        }

        makeVmFrom(
            obdRepo, sessionRepo,
            fakeAppPreferences(logOnlyDtcSessions = true),
            makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(),
            makeGpsSpeedSource(), mockk(relaxed = true)
        )
        isConnectedFlow.value = true
        advanceUntilIdle()

        coVerify { sessionRepo.endSession(77L) }
        coVerify(exactly = 0) { sessionRepo.discardSession(any()) }
    }

    @Test
    fun `rpm is always polled even when not in selected pids`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        // Slot contains only SPEED — RPM explicitly excluded
        val prefs = fakeAppPreferences(slotPids = listOf(PidRegistry.SPEED.id))

        makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnectedFlow.value = true
        advanceUntilIdle()

        // livePids must have been called with a set that includes RPM
        verify {
            obdRepo.livePids(match { pids -> pids.any { it.id == PidRegistry.RPM.id } })
        }
    }

    // ── supportedPidIds filtering ──────────────────────────────────────────────

    @Test
    fun `livePids excludes slot PID when ECU does not support it`() = runTest {
        // THROTTLE ("0111") is in the slot but NOT in the ECU-supported set.
        // It must therefore be absent from the PID list passed to livePids.
        // (Not COOLANT_TEMP: since the oil-temperature bar it is always polled, like RPM,
        // regardless of slots and discovery — see ReadingsPipeline.selectPids().)
        val unsupportedSlotId = PidRegistry.THROTTLE.id  // "0111"
        val supportedSet = setOf(PidRegistry.RPM.id, PidRegistry.SPEED.id)  // does NOT contain "0111"

        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(supportedSet)
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(slotPids = listOf(unsupportedSlotId))

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
        // (Not COOLANT_TEMP: it is always polled for the oil-temperature bar, so it would pass
        // this assertion even if slot PIDs were dropped.)
        val slotId = PidRegistry.THROTTLE.id  // "0111"

        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(slotPids = listOf(slotId))

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
        // (THROTTLE, not COOLANT_TEMP: the latter is always polled for the oil-temperature bar.)
        val slotId = PidRegistry.THROTTLE.id  // "0111" — not in the supported set below
        val supportedSet = setOf(PidRegistry.SPEED.id, PidRegistry.MAF.id)  // no overlap with slot

        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(supportedSet)
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(slotPids = listOf(slotId))

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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
        }
        val prefs = fakeAppPreferences().apply { coEvery { setSlotPid(any(), any()) } just Runs }
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
    //
    // Diese Tests pruefen den Teil der Liste, der vom Fahrzeug abhaengt: die Gelaende-Kachel (DerivedPids.TERRAIN) ist immer dabei, kein Wert des Fahrzeugs
    // (DashboardViewModelTerrainTest: `the picker offers the terrain entry even when the vehicle lists its PIDs`) — [vehiclePickerIds] lässt sie aussen vor.

    private fun DashboardViewModel.vehiclePickerIds(): List<String> =
        availablePids.value.map { it.id }.filter { it != DerivedPids.TERRAIN_ID }

    @Test
    fun `availablePids returns the full registry when supportedPidIds is null`() = runTest {
        val obdRepo = fakeObdRepository()

        val vm = makeVmFrom(obdRepo, mockk(relaxed = true), fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.availablePids.collect {} }
        advanceUntilIdle()
        job.cancel()

        val expectedIds = PidRegistry.all.values.map { it.id }.toSet()
        assertEquals(expectedIds, vm.vehiclePickerIds().toSet())
        assertEquals(PidRegistry.all.size, vm.vehiclePickerIds().size)
    }

    @Test
    fun `availablePids returns the full registry when supportedPidIds is empty`() = runTest {
        // isNullOrEmpty() treats an empty discovered set the same as "no discovery yet" — a
        // vehicle whose discovery legitimately came back empty must still offer the full picker,
        // not an empty one.
        val obdRepo = fakeObdRepository {
            every { supportedPidIds } returns MutableStateFlow(emptySet())
        }

        val vm = makeVmFrom(obdRepo, mockk(relaxed = true), fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.availablePids.collect {} }
        advanceUntilIdle()
        job.cancel()

        val expectedIds = PidRegistry.all.values.map { it.id }.toSet()
        assertEquals(expectedIds, vm.vehiclePickerIds().toSet())
    }

    @Test
    fun `availablePids is filtered to exactly the supported subset when GPS speed is off`() = runTest {
        val supported = setOf(PidRegistry.RPM.id, PidRegistry.MAF.id)
        val obdRepo = fakeObdRepository {
            every { supportedPidIds } returns MutableStateFlow(supported)
        }

        val vm = makeVmFrom(obdRepo, mockk(relaxed = true), fakeAppPreferences(useGpsSpeed = false), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.availablePids.collect {} }
        advanceUntilIdle()
        job.cancel()

        assertEquals(supported, vm.vehiclePickerIds().toSet())
    }

    @Test
    fun `availablePids force-includes SPEED when GPS speed is enabled even though the ECU does not support it`() = runTest {
        // Die GPS-Ausnahme aus der Produktionslogik: SPEED bleibt waehlbar, weil das GPS sie
        // liefern kann, unabhaengig davon, ob das Fahrzeug den PID selbst unterstuetzt.
        val supported = setOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id) // SPEED absichtlich nicht enthalten
        val obdRepo = fakeObdRepository {
            every { supportedPidIds } returns MutableStateFlow(supported)
        }

        val vm = makeVmFrom(obdRepo, mockk(relaxed = true), fakeAppPreferences(useGpsSpeed = true), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.availablePids.collect {} }
        advanceUntilIdle()
        job.cancel()

        val resultIds = vm.vehiclePickerIds().toSet()
        assertEquals(supported + PidRegistry.SPEED.id, resultIds)
    }

    @Test
    fun `availablePids excludes SPEED when GPS speed is disabled and the ECU does not support it`() = runTest {
        // Kontrastfall zum GPS-Carve-out oben: ohne GPS-Speed und ohne ECU-Unterstuetzung darf
        // SPEED nicht im Picker auftauchen.
        val supported = setOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id) // SPEED absichtlich nicht enthalten
        val obdRepo = fakeObdRepository {
            every { supportedPidIds } returns MutableStateFlow(supported)
        }

        val vm = makeVmFrom(obdRepo, mockk(relaxed = true), fakeAppPreferences(useGpsSpeed = false), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.availablePids.collect {} }
        advanceUntilIdle()
        job.cancel()

        val resultIds = vm.vehiclePickerIds().toSet()
        assertEquals(supported, resultIds)
        assertFalse(PidRegistry.SPEED.id in resultIds)
    }

    // ── Auto-connect on startup ───────────────────────────────────────────────

    @Test
    fun `auto-connect on startup triggers reconnect loop when connect fails`() = runTest {
        val savedDevice = mockk<ScannedDevice> { every { name } returns "TestAdapter" }
        val obdRepo = fakeObdRepository {
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
            coEvery { connectIfDisconnected(savedDevice) } throws Exception("BT unavailable")
            // reconnect() must not be called — auto-connect uses connectIfDisconnected(), not reconnect()
            coEvery { reconnect() } throws Exception("should not be called")
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(lastDevice = savedDevice)

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        // advanceTimeBy(1) runs all pending coroutines up to the first delay.
        // advanceUntilIdle() would exhaust the entire reconnect backoff (93 s virtual),
        // ending with DashboardUiState.disconnected() → isReconnecting = false.
        advanceTimeBy(1)

        assertTrue("isReconnecting should be true after failed auto-connect", vm.uiState.value.isReconnecting)
        coVerify { obdRepo.connectIfDisconnected(savedDevice) }
    }

    // ── makeVm() helper — used by foreground-service tests ────────────────────
    // Wraps makeVmFrom(), which builds the ViewModel with its collaborators, so new tests can be written concisely.
    // Parameters that are not relevant to a specific test keep their defaults.
    private fun makeVm(
        obdRepo: ObdRepository = fakeObdRepository {
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
            every { livePids(any()) } returns flowOf(emptyList())
        },
        sessionRepo: SessionRepository = mockk {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        },
        prefs: AppPreferences = fakeAppPreferences(),
        crashReporter: CrashReporter = makeCrashReporter(),
        btAdapter: BluetoothAdapter = makeBluetoothAdapter(),
        logRepo: AppLogRepository = makeAppLogRepository(),
        appContext: Context = mockk(relaxed = true)
    ): DashboardViewModel = makeVmFrom(
        obdRepo, sessionRepo, prefs, crashReporter, btAdapter, logRepo, makeGpsSpeedSource(), appContext
    )

    // ── Start: saved slots are there from the first state (UID-12) ───────────

    @Test
    fun `the first state already carries the saved slot PIDs - no flash of empty or default tiles`() {
        // Bewusst OHNE runTest/advance: gemeint ist der Zustand im Moment der Erzeugung, den die erste Komposition liest.
        val saved = listOf("0105", "010C", "010D", "0104")
        val vm = makeVm(prefs = fakeAppPreferences(slotPids = saved))

        assertEquals(saved, vm.slotPids.value)
        assertEquals(saved, vm.placeholderReadings.value.map { it.pid.id })
    }

    @Test
    fun `unknown saved PIDs fall back to the defaults, as before`() {
        val vm = makeVm(prefs = fakeAppPreferences(slotPids = listOf("ZZZZ")))

        assertEquals(PidRegistry.defaults.map { it.id }, vm.placeholderReadings.value.map { it.pid.id })
    }

    // ── Foreground service lifecycle ──────────────────────────────────────────

    @Test
    fun `service is started when auto-connect succeeds on startup`() = runTest {
        val savedDevice = mockk<ScannedDevice> { every { name } returns "TestAdapter" }
        val ctx = mockk<Context>(relaxed = true)
        val obdRepo = fakeObdRepository {
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
            coEvery { connectIfDisconnected(savedDevice) } just Runs
            every { livePids(any()) } returns flowOf(emptyList())
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(lastDevice = savedDevice)

        makeVm(obdRepo = obdRepo, sessionRepo = sessionRepo, prefs = prefs, appContext = ctx)
        advanceUntilIdle()

        verify { ctx.startForegroundService(any()) }
    }

    @Test
    fun `service is stopped when reconnect loop exhausts all attempts`() = runTest {
        val ctx = mockk<Context>(relaxed = true)
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice>())
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            coEvery { reconnect() } throws Exception("fail")
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice> { every { name } returns "TestAdapter" })
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            // First attempt fails, second succeeds
            coEvery { reconnect() } throws Exception("fail") andThen Unit
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            // Cold launch: no prior connect in this session → lastConnectedDevice is null
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
        }
        // fakeAppPreferences() already stubs lastDevice as flowOf(null) → no persisted device either
        makeVm(obdRepo = obdRepo, prefs = fakeAppPreferences(), appContext = ctx)
        isConnectedFlow.value = true
        // advanceTimeBy(1) runs all coroutines up to the first suspension point,
        // which is enough for the reconnect loop to check both device sources and abort.
        advanceTimeBy(1)

        verify { ctx.stopService(any()) }
    }

    @Test
    fun `setMoving false called when SPEED is not in selected PIDs`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
            every { livePids(any()) } returns flowOf(emptyList())
        }
        // Slot contains only RPM — SPEED is explicitly absent
        val prefs = fakeAppPreferences(slotPids = listOf(PidRegistry.RPM.id))

        makeVm(obdRepo = obdRepo, prefs = prefs)
        isConnectedFlow.value = true
        advanceUntilIdle()

        verify { obdRepo.setMoving(false) }
    }

    @Test
    fun `uiState error set to no-device banner when no device is configured`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            // Cold launch: no prior connect in this session → lastConnectedDevice is null
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
        }
        // fakeAppPreferences() already stubs lastDevice as flowOf(null) → no persisted device
        val vm = makeVm(obdRepo = obdRepo, prefs = fakeAppPreferences())
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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice>())
            every { livePids(any()) } returns flow {
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
            coEvery { reconnect() } throws Exception("reconnect failed")
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(setOf(PidRegistry.RPM.id))
            every { livePids(any()) } returns flowOf(listOf(rpm))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = makeVmFrom(
            obdRepo, sessionRepo,
            fakeAppPreferences(slotPids = listOf(PidRegistry.RPM.id, PidRegistry.SPEED.id, "", ""), useGpsSpeed = true),
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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { supportedPidIds } returns MutableStateFlow(setOf(PidRegistry.RPM.id))
            every { livePids(any()) } returns flowOf(listOf(rpm))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = makeVmFrom(
            obdRepo, sessionRepo,
            fakeAppPreferences(slotPids = listOf(PidRegistry.RPM.id, PidRegistry.SPEED.id, "", ""), useGpsSpeed = false),
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
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { livePids(any()) } returns flowOf(listOf(speedReading))
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
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
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { livePids(any()) } returns flowOf(readings)
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(
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
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { livePids(any()) } returns flowOf(readings)
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(
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
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { livePids(any()) } returns flowOf(readings)
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(
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
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { livePids(any()) } returns flowOf(firstReadings, secondReadings)
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(
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
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { livePids(any()) } returns flowOf(readings[0], readings[1], readings[2])
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(
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
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { livePids(any()) } returns flow {
                emit(firstReadings)
                delay(200)
                emit(secondReadings)
            }
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(
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
        // isConnectedFlow is still flipped to false FROM WITHIN the livePids flow. Since the
        // StateFlow-reentrancy fix (ReadingsPipeline.connectionLostSignal), ConnectionLost no
        // longer travels through the same cancellable coroutine as this cycle's Values reading —
        // it survives via an independent sibling flow, which is the whole point of that fix, but
        // it also means the two are no longer strictly ordered relative to each other the instant
        // they're both emitted with zero real time between them. The `delay(1)` below restores the
        // ordering that ALWAYS holds on a real device: a poll cycle's Values reading is fully
        // delivered (record()/writeReadings, a real suspending DB write in production) well before
        // any LATER cycle can detect a lost connection — real BLE polling always has at least
        // pollIntervalMs, and typically a 5s timeout, of real time between two cycles. Without
        // this delay, this test collapses that gap to zero, an ordering no real connection can
        // produce, and the two independent flows can race.
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flow {
                // First, a normal cycle that locks in a gear...
                emit(
                    listOf(
                        SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
                        SensorValue(pid = PidRegistry.SPEED, value = 60f, timestamp = 1000L),
                    )
                )
                delay(1)
                // ...then the connection drops.
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f, 5f, 3f, 2f, 1.5f, 1.2f),
        )

        val vm = DashboardViewModel(
            obdRepo,
            prefs,
            mockk<ConnectionCoordinator>(relaxed = true),
            ReadingsPipeline(obdRepo, sessionRepo, prefs, makeAppLogRepository(), makeCrashReporter(), makeGpsSpeedSource(), mockk(relaxed = true)),
            SavedStateHandle(),
        )
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isReconnecting)
        assertNull(vm.uiState.value.gear)
        assertNull(vm.uiState.value.speedKmh)
    }

    // ── B29: values nobody refreshes are shown as stale, not as live ──────────

    private fun staleRepo(isConnected: MutableStateFlow<Boolean>, session: kotlinx.coroutines.flow.Flow<List<SensorValue>>) =
        fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnected
            every { livePids(any()) } returns session
        }

    private val liveCycle = listOf(
        SensorValue(pid = PidRegistry.RPM, value = 3000f, timestamp = 1000L),
        SensorValue(pid = PidRegistry.SPEED, value = 60f, timestamp = 1000L),
    )

    @Test
    fun `a silent ECU turns the display stale - no readings, no rpm, no speed, no gear - but the link stays connected`() = runTest {
        val isConnected = MutableStateFlow(false)
        val repo = staleRepo(isConnected, kotlinx.coroutines.flow.flow { emit(liveCycle); kotlinx.coroutines.awaitCancellation() })
        val sessionRepo = mockk<SessionRepository>(relaxed = true) { coEvery { startSession(any(), any()) } returns 1L }
        val vm = makeVmFrom(repo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnected.value = true
        advanceTimeBy(1_000)
        assertFalse(vm.uiState.value.isStale)
        assertTrue(vm.uiState.value.readings.isNotEmpty())

        advanceTimeBy(OBD_STALE_AFTER_MS + 1_000)

        val state = vm.uiState.value
        assertTrue("veraltet", state.isStale)
        assertTrue("der Adapter-Link steht weiter", state.isConnected)
        assertTrue("keine alten Werte mehr: ${state.readings}", state.readings.isEmpty())
        assertEquals(0f, state.rpmValue, 0f)
        assertNull(state.speedKmh)
        assertNull(state.gear)
        assertFalse(state.isNeutral)
    }

    @Test
    fun `fresh values after a stale phase clear the stale flag`() = runTest {
        val isConnected = MutableStateFlow(false)
        val repo = staleRepo(
            isConnected,
            kotlinx.coroutines.flow.flow { emit(liveCycle); delay(15_000); emit(liveCycle); kotlinx.coroutines.awaitCancellation() },
        )
        val sessionRepo = mockk<SessionRepository>(relaxed = true) { coEvery { startSession(any(), any()) } returns 1L }
        val vm = makeVmFrom(repo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnected.value = true
        advanceTimeBy(OBD_STALE_AFTER_MS + 1_000)
        assertTrue(vm.uiState.value.isStale)

        advanceTimeBy(5_000)        // t = 16 s: the second cycle has arrived

        assertFalse(vm.uiState.value.isStale)
        assertTrue(vm.uiState.value.readings.isNotEmpty())
    }

    // ── B24/B23: the link is up before the first values; values for no slot are still "live" ──

    private fun makeStaleVm(repo: ObdRepository, prefs: AppPreferences = fakeAppPreferences()): DashboardViewModel {
        val sessionRepo = mockk<SessionRepository>(relaxed = true) { coEvery { startSession(any(), any()) } returns 1L }
        return makeVmFrom(repo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
    }

    @Test
    fun `a link that is up shows connected at once and waits for the first readings`() = runTest {
        val isConnected = MutableStateFlow(false)
        val vm = makeStaleVm(staleRepo(isConnected, kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }))
        assertFalse(vm.uiState.value.isConnected)

        isConnected.value = true
        advanceTimeBy(100)

        val state = vm.uiState.value
        assertTrue("Link steht = verbunden, nicht 'Vorschau · nicht verbunden'", state.isConnected)
        assertTrue("noch keine Werte: wartet", state.awaitingFirstValues)
        assertTrue(state.readings.isEmpty())
        assertFalse(state.isReconnecting)
        assertNull(state.error)
    }

    @Test
    fun `the first readings end the waiting`() = runTest {
        val isConnected = MutableStateFlow(false)
        val vm = makeStaleVm(staleRepo(isConnected, kotlinx.coroutines.flow.flow { emit(liveCycle); kotlinx.coroutines.awaitCancellation() }))

        isConnected.value = true
        advanceTimeBy(1_000)

        assertTrue(vm.uiState.value.isConnected)
        assertFalse(vm.uiState.value.awaitingFirstValues)
        assertTrue(vm.uiState.value.readings.isNotEmpty())
    }

    @Test
    fun `a silent ECU from the start turns waiting into stale`() = runTest {
        val isConnected = MutableStateFlow(false)
        val vm = makeStaleVm(staleRepo(isConnected, kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }))
        isConnected.value = true
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.awaitingFirstValues)

        advanceTimeBy(OBD_STALE_AFTER_MS + 1_000)

        assertTrue(vm.uiState.value.isStale)
        assertFalse("kein 'warte' neben 'keine aktuellen Werte'", vm.uiState.value.awaitingFirstValues)
    }

    @Test
    fun `values for none of the slots keep the display live - connected, not waiting, not stale (B23)`() = runTest {
        val isConnected = MutableStateFlow(false)
        // Alle vier Slots belegen PIDs, die das Steuergeraet nicht liefert; RPM und Kuehlmittel werden immer abgefragt.
        val slots = listOf(PidRegistry.THROTTLE.id, PidRegistry.ENGINE_LOAD.id, PidRegistry.INTAKE_AIR_TEMP.id, PidRegistry.MAF.id)
        val delivered = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 2500f, timestamp = 1000L),
            SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 90f, timestamp = 1000L),
        )
        val vm = makeStaleVm(
            staleRepo(isConnected, kotlinx.coroutines.flow.flow { emit(delivered); kotlinx.coroutines.awaitCancellation() }),
            prefs = fakeAppPreferences(slotPids = slots),
        )

        isConnected.value = true
        advanceTimeBy(1_000)

        val state = vm.uiState.value
        assertTrue(state.isConnected)
        assertFalse("es fliessen Werte - nicht 'warte'", state.awaitingFirstValues)
        assertFalse(state.isStale)
        assertTrue("keiner gehoert zu einem Slot: ${state.readings}", state.readings.isEmpty())
        assertEquals("die Drehzahl wird trotzdem gefuehrt", 2500f, state.rpmValue, 0f)
    }

    @Test
    fun `a link lost while waiting leaves no waiting flag behind - the reconnect state stays clean`() = runTest {
        val isConnected = MutableStateFlow(false)
        val repo = staleRepo(isConnected, kotlinx.coroutines.flow.flow {
            delay(1_000)
            isConnected.value = false
            throw de.adv990de.obd2.obd.model.ObdException.ConnectionLost()
        })
        val sessionRepo = mockk<SessionRepository>(relaxed = true) { coEvery { startSession(any(), any()) } returns 1L }
        val prefs = fakeAppPreferences()
        val vm = DashboardViewModel(
            repo, prefs, mockk<ConnectionCoordinator>(relaxed = true),
            ReadingsPipeline(repo, sessionRepo, prefs, makeAppLogRepository(), makeCrashReporter(), makeGpsSpeedSource(), mockk(relaxed = true)),
            SavedStateHandle(),
        )
        isConnected.value = true
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.awaitingFirstValues)

        advanceTimeBy(2_000)

        assertTrue("der Reconnect laeuft", vm.uiState.value.isReconnecting)
        assertFalse("kein 'warte' in den Reconnect mitnehmen", vm.uiState.value.awaitingFirstValues)
    }

    @Test
    fun `a reported connection loss means not connected even if isConnectedFlow has not flipped yet`() = runTest {
        val isConnected = MutableStateFlow(false)
        // isConnectedFlow wird bewusst NICHT gekippt: Die Reihenfolge von Idle-Ereignis und Verlustmeldung ist nicht garantiert.
        val repo = staleRepo(isConnected, kotlinx.coroutines.flow.flow {
            delay(1_000)
            throw de.adv990de.obd2.obd.model.ObdException.ConnectionLost()
        })
        val sessionRepo = mockk<SessionRepository>(relaxed = true) { coEvery { startSession(any(), any()) } returns 1L }
        val prefs = fakeAppPreferences()
        val vm = DashboardViewModel(
            repo, prefs, mockk<ConnectionCoordinator>(relaxed = true),
            ReadingsPipeline(repo, sessionRepo, prefs, makeAppLogRepository(), makeCrashReporter(), makeGpsSpeedSource(), mockk(relaxed = true)),
            SavedStateHandle(),
        )
        isConnected.value = true
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.isConnected)

        advanceTimeBy(2_000)

        assertTrue(vm.uiState.value.isReconnecting)
        assertFalse("verloren heisst nicht verbunden", vm.uiState.value.isConnected)
    }

    @Test
    fun `losing the link ends the waiting`() = runTest {
        val isConnected = MutableStateFlow(false)
        val vm = makeStaleVm(staleRepo(isConnected, kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }))
        isConnected.value = true
        advanceTimeBy(100)
        assertTrue(vm.uiState.value.awaitingFirstValues)

        isConnected.value = false
        advanceTimeBy(100)

        assertFalse(vm.uiState.value.isConnected)
        assertFalse(vm.uiState.value.awaitingFirstValues)
    }

    @Test
    fun `a link lost while stale ends the stale state too - the reconnect spinner takes over`() = runTest {
        val isConnected = MutableStateFlow(false)
        val repo = staleRepo(isConnected, kotlinx.coroutines.flow.flow {
            emit(liveCycle)
            delay(12_000)
            isConnected.value = false
            throw de.adv990de.obd2.obd.model.ObdException.ConnectionLost()
        })
        val sessionRepo = mockk<SessionRepository>(relaxed = true) { coEvery { startSession(any(), any()) } returns 1L }
        val prefs = fakeAppPreferences()
        // Ein relaxter Koordinator: der echte wuerde den Reconnect tatsaechlich durchspielen.
        val vm = DashboardViewModel(
            repo,
            prefs,
            mockk<ConnectionCoordinator>(relaxed = true),
            ReadingsPipeline(repo, sessionRepo, prefs, makeAppLogRepository(), makeCrashReporter(), makeGpsSpeedSource(), mockk(relaxed = true)),
            SavedStateHandle(),
        )
        isConnected.value = true
        advanceTimeBy(OBD_STALE_AFTER_MS + 1_000)
        assertTrue(vm.uiState.value.isStale)

        advanceTimeBy(2_000)        // t = 13 s: the link drops

        assertFalse("kein Veraltet-Zustand neben dem Reconnect", vm.uiState.value.isStale)
        assertTrue(vm.uiState.value.isReconnecting)
        assertFalse(vm.uiState.value.isConnected)
    }

    @Test
    fun `losing the connection ends the stale state - the not-connected banner takes over`() = runTest {
        val isConnected = MutableStateFlow(false)
        val repo = staleRepo(isConnected, kotlinx.coroutines.flow.flow { emit(liveCycle); kotlinx.coroutines.awaitCancellation() })
        val sessionRepo = mockk<SessionRepository>(relaxed = true) { coEvery { startSession(any(), any()) } returns 1L }
        val vm = makeVmFrom(repo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        isConnected.value = true
        advanceTimeBy(OBD_STALE_AFTER_MS + 1_000)
        assertTrue(vm.uiState.value.isStale)

        isConnected.value = false
        advanceTimeBy(1_000)

        assertFalse(vm.uiState.value.isStale)
        assertFalse(vm.uiState.value.isConnected)
    }

    // ── B30/B26: tests with a pipeline whose events the test controls ──────────

    private class Driven(
        val vm: DashboardViewModel,
        val events: kotlinx.coroutines.flow.MutableSharedFlow<SessionEvent>,
        val pipeline: ReadingsPipeline,
        /** Was das ViewModel je OBD-Zyklus an `recordDerived` uebergab, in der Reihenfolge der Aufrufe. */
        val recorded: List<List<SensorValue>>,
    )

    /**
     * Eine strenge Pipeline-Attrappe: die Ereignisse bestimmt der Test, und die Aufzeichnung der abgeleiteten Werte (`recordDerived`: Gang
     * und Oel-Balken) ist angenommen — das ViewModel ruft sie nach jedem OBD-Zyklus, eine strenge Attrappe ohne diese Zeile liesse die
     * Sammlung scheitern. Was uebergeben wird, sammelt [recorded].
     */
    private fun strictPipeline(
        events: kotlinx.coroutines.flow.Flow<SessionEvent>,
        recorded: MutableList<List<SensorValue>> = mutableListOf(),
    ): ReadingsPipeline = mockk {
        every { events(any()) } returns events
        coEvery { recordDerived(capture(recorded)) } just Runs
    }

    private fun driven(prefs: AppPreferences = fakeAppPreferences(), fuelSink: FuelDistanceSink = FuelDistanceSink.None): Driven {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
        val recorded = mutableListOf<List<SensorValue>>()
        val pipeline = strictPipeline(events, recorded)
        val obdRepo = fakeObdRepository()
        val vm = DashboardViewModel(obdRepo, prefs, mockk<ConnectionCoordinator>(relaxed = true), pipeline, SavedStateHandle(), fuelSink)
        return Driven(vm, events, pipeline, recorded)
    }

    private fun rpmAt(ts: Long, value: Float = 3000f) = SensorValue(pid = PidRegistry.RPM, value = value, timestamp = ts)

    private suspend fun Driven.cycle(vararg values: SensorValue) {
        events.emit(SessionEvent.Values(values.toList(), gpsSpeedKmh = null))
    }


    // ── Tankanzeige: die GPS-Kilometer ─────────────────────────────────────────

    private class RecordingFuelSink : FuelDistanceSink {
        val km = mutableListOf<Float>()
        var flushes = 0
        override fun addDistance(km: Float) { this.km += km }
        override fun flush() { flushes++ }
    }

    /** Ein Ereignis mit GPS-Geschwindigkeit [gpsKmh] zur Uhrzeit [atMillis] (monotone Uhr des ViewModels). */
    private suspend fun TestScope.fuelEvent(d: Driven, clock: LongArray, atMillis: Long, gpsKmh: Float?, isObdCycle: Boolean = true) {
        clock[0] = atMillis
        d.events.emit(SessionEvent.Values(listOf(rpmAt(atMillis)), gpsSpeedKmh = gpsKmh, isObdCycle = isObdCycle))
        advanceUntilIdle() // das ViewModel liest die Uhr beim Verarbeiten: nach JEDEM Ereignis abwarten
    }

    private fun TestScope.drivenWithFuel(sink: RecordingFuelSink, clock: LongArray): Driven =
        driven(fuelSink = sink).also { it.vm.nowMillis = { clock[0] }; advanceUntilIdle() }

    @Test
    fun `GPS speed over time is counted as fuel distance - 72 km per hour for 10 seconds is 0,2 km`() = runTest {
        val sink = RecordingFuelSink()
        val clock = longArrayOf(0L)
        val d = drivenWithFuel(sink, clock)

        fuelEvent(d, clock, 0L, 72f)
        fuelEvent(d, clock, 10_000L, 72f)

        assertEquals(0.2f, sink.km.sum(), 1e-5f)
    }

    @Test
    fun `GPS refresh events count as well - the distance does not depend on OBD cycles`() = runTest {
        val sink = RecordingFuelSink()
        val clock = longArrayOf(0L)
        val d = drivenWithFuel(sink, clock)

        fuelEvent(d, clock, 0L, 36f)
        for (i in 1..10) fuelEvent(d, clock, i * 1_000L, 36f, isObdCycle = false)

        assertEquals(0.1f, sink.km.sum(), 1e-5f)
    }

    @Test
    fun `no GPS speed counts nothing`() = runTest {
        val sink = RecordingFuelSink()
        val clock = longArrayOf(0L)
        val d = drivenWithFuel(sink, clock)

        fuelEvent(d, clock, 0L, null)
        fuelEvent(d, clock, 10_000L, null)

        assertTrue("nichts gezaehlt: ${sink.km}", sink.km.isEmpty())
    }

    @Test
    fun `idle ends the interval and saves the counter - the first sample afterwards counts nothing`() = runTest {
        val sink = RecordingFuelSink()
        val clock = longArrayOf(0L)
        val d = drivenWithFuel(sink, clock)
        fuelEvent(d, clock, 0L, 72f)
        fuelEvent(d, clock, 1_000L, 72f)
        val before = sink.km.sum()

        d.events.emit(SessionEvent.Idle)
        advanceUntilIdle()
        assertEquals("Zaehler gespeichert", 1, sink.flushes)

        fuelEvent(d, clock, 2_000L, 72f)
        assertEquals("die Luecke wird nicht gezaehlt", before, sink.km.sum(), 0f)
    }

    @Test
    fun `stale values, a lost connection and a failed session also save the counter`() = runTest {
        val sink = RecordingFuelSink()
        val d = drivenWithFuel(sink, longArrayOf(0L))

        d.events.emit(SessionEvent.Stale)
        advanceUntilIdle()
        d.events.emit(SessionEvent.ConnectionLost)
        advanceUntilIdle()
        d.events.emit(SessionEvent.Failed(UiText.DynamicString("x")))
        advanceUntilIdle()

        assertEquals(3, sink.flushes)
    }

    @Test
    fun `the counter is saved when the view model ends`() = runTest {
        val sink = RecordingFuelSink()
        val d = drivenWithFuel(sink, longArrayOf(0L))

        val onCleared = DashboardViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(d.vm)

        assertEquals(1, sink.flushes)
    }

    @Test
    fun `a replay of the same OBD cycle adds no sparkline point - the GPS refresh must not stair-step the graph (B30)`() = runTest {
        val d = driven()
        advanceUntilIdle()

        d.cycle(rpmAt(1_000L))
        d.cycle(rpmAt(1_000L))          // GPS-Zwischenupdate: dieselben Werte, derselbe Zeitstempel
        d.cycle(rpmAt(1_000L))
        advanceUntilIdle()

        assertEquals(1, d.vm.uiState.value.sparklines[PidRegistry.RPM.id]!!.size)
    }

    @Test
    fun `a genuinely new cycle adds a sparkline point`() = runTest {
        val d = driven()
        advanceUntilIdle()

        d.cycle(rpmAt(1_000L, 3000f))
        d.cycle(rpmAt(1_000L, 3000f))   // Replay
        d.cycle(rpmAt(2_000L, 3500f))
        advanceUntilIdle()

        assertEquals(listOf(3000f, 3500f), d.vm.uiState.value.sparklines[PidRegistry.RPM.id])
    }

    @Test
    fun `samples closer together than the minimum interval are skipped - one point per second, not per fix`() = runTest {
        val d = driven()
        advanceUntilIdle()

        for (i in 0 until 4) d.cycle(rpmAt(1_000L + i * 200L, 3000f + i))      // 5 Hz, hoechstens 600 ms Abstand zum ersten
        d.cycle(rpmAt(2_000L, 4000f))
        advanceUntilIdle()

        assertEquals(listOf(3000f, 4000f), d.vm.uiState.value.sparklines[PidRegistry.RPM.id])
    }

    @Test
    fun `each PID keeps its own sampling clock`() = runTest {
        val d = driven()
        advanceUntilIdle()

        d.cycle(rpmAt(1_000L), SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 90f, timestamp = 1_000L))
        d.cycle(rpmAt(1_000L), SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 90f, timestamp = 1_000L))
        d.cycle(rpmAt(2_000L), SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 90f, timestamp = 2_000L))
        advanceUntilIdle()

        assertEquals(2, d.vm.uiState.value.sparklines[PidRegistry.RPM.id]!!.size)
        assertEquals(2, d.vm.uiState.value.sparklines[PidRegistry.COOLANT_TEMP.id]!!.size)
    }

    @Test
    fun `an idle connection clears the sparkline buffer - a reconnect starts a fresh line (UID-16)`() = runTest {
        val d = driven()
        advanceUntilIdle()
        d.cycle(rpmAt(1_000L, 3000f))
        d.cycle(rpmAt(2_000L, 3100f))
        advanceUntilIdle()
        assertEquals(2, d.vm.uiState.value.sparklines[PidRegistry.RPM.id]!!.size)

        d.events.emit(SessionEvent.Idle)
        advanceUntilIdle()
        d.cycle(rpmAt(60_000L, 900f))
        advanceUntilIdle()

        assertEquals("nur der neue Punkt, nicht der alte Verlauf davor", listOf(900f), d.vm.uiState.value.sparklines[PidRegistry.RPM.id])
    }

    // --- Tacho-Sweep: einmal je App-Start (Entscheidung 2026-10-12) ---------------------------------------------------

    @Test
    fun `the demo sweep is offered once - the first call gets it, every later call does not`() {
        val vm = makeVm()

        assertEquals("first visit of the dashboard", true, vm.consumeDemoSweepOffer())
        assertEquals("second visit (tab switch)", false, vm.consumeDemoSweepOffer())
        assertEquals("third visit", false, vm.consumeDemoSweepOffer())
    }

    @Test
    fun `a new view model gets the demo sweep again - that is what an app start is`() {
        assertEquals(true, makeVm().consumeDemoSweepOffer())
        assertEquals(true, makeVm().consumeDemoSweepOffer())
    }

    @Test
    fun `connection changes do not bring the demo sweep back`() = runTest {
        val d = driven()
        advanceUntilIdle()
        assertEquals(true, d.vm.consumeDemoSweepOffer())

        d.events.emit(SessionEvent.Idle)
        advanceUntilIdle()
        d.cycle(rpmAt(1_000L, 900f))
        d.events.emit(SessionEvent.Failed(UiText.DynamicString("link lost")))
        advanceUntilIdle()

        assertEquals("a reconnect is not an app start", false, d.vm.consumeDemoSweepOffer())
    }

    @Test
    fun `a lost connection clears the sparklines at once and the buffer for the next session (UID-16)`() = runTest {
        val d = driven()
        advanceUntilIdle()
        d.cycle(rpmAt(1_000L, 3000f))
        advanceUntilIdle()

        d.events.emit(SessionEvent.ConnectionLost)
        advanceUntilIdle()
        assertTrue("waehrend der Wiederverbindung keine alte Kurve", d.vm.uiState.value.sparklines.isEmpty())

        d.cycle(rpmAt(30_000L, 1100f))
        advanceUntilIdle()
        assertEquals(listOf(1100f), d.vm.uiState.value.sparklines[PidRegistry.RPM.id])
    }

    @Test
    fun `a failed session clears the sparkline buffer too (UID-16)`() = runTest {
        val d = driven()
        advanceUntilIdle()
        d.cycle(rpmAt(1_000L, 3000f))
        advanceUntilIdle()

        d.events.emit(SessionEvent.Failed(UiText.DynamicString("boom")))
        advanceUntilIdle()
        d.cycle(rpmAt(5_000L, 1500f))
        advanceUntilIdle()

        assertEquals(listOf(1500f), d.vm.uiState.value.sparklines[PidRegistry.RPM.id])
    }

    @Test
    fun `the first sample after a reconnect is not suppressed by the clock of the old line (UID-16)`() = runTest {
        val d = driven()
        advanceUntilIdle()
        d.cycle(rpmAt(10_000L, 3000f))
        advanceUntilIdle()

        d.events.emit(SessionEvent.Idle)
        advanceUntilIdle()
        // 300 ms nach dem letzten Punkt der alten Linie (Geraeteuhr, Zeitstempel zaehlen weiter): ohne zurueckgesetzte
        // Zeitmarke wuerde der Mindestabstand von 800 ms diesen ersten Punkt der neuen Verbindung verschlucken.
        d.cycle(rpmAt(10_300L, 900f))
        advanceUntilIdle()

        assertEquals(listOf(900f), d.vm.uiState.value.sparklines[PidRegistry.RPM.id])
    }

    private fun coolant(value: Float, ts: Long) = SensorValue(pid = PidRegistry.COOLANT_TEMP, value = value, timestamp = ts)

    @Test
    fun `a cycle without a temperature keeps the last one - the dynamic threshold must not halve for a cycle (B26)`() = runTest {
        val d = driven(fakeAppPreferences(rpmDynamicThreshold = true))
        val sub = launch { d.vm.rpmDynamicThreshold.collect {} }
        advanceUntilIdle()

        d.cycle(rpmAt(1_000L), coolant(90f, 1_000L))
        advanceUntilIdle()
        assertEquals(90f, d.vm.uiState.value.engineTempC!!, 0.001f)

        d.cycle(rpmAt(2_000L))                          // diesmal ohne Kuehlmittel-Probe
        advanceUntilIdle()
        sub.cancel()

        assertEquals("die letzte Temperatur bleibt, null liesse die Schwelle auf den kalten Wert fallen",
            90f, d.vm.uiState.value.engineTempC!!, 0.001f)
    }

    @Test
    fun `a newer temperature replaces the held one`() = runTest {
        val d = driven(fakeAppPreferences(rpmDynamicThreshold = true))
        val sub = launch { d.vm.rpmDynamicThreshold.collect {} }
        advanceUntilIdle()

        d.cycle(rpmAt(1_000L), coolant(60f, 1_000L))
        d.cycle(rpmAt(2_000L))
        d.cycle(rpmAt(3_000L), coolant(75f, 3_000L))
        advanceUntilIdle()
        sub.cancel()

        assertEquals(75f, d.vm.uiState.value.engineTempC!!, 0.001f)
    }

    @Test
    fun `without the dynamic threshold no temperature is kept`() = runTest {
        val d = driven(fakeAppPreferences(rpmDynamicThreshold = false))
        val sub = launch { d.vm.rpmDynamicThreshold.collect {} }
        advanceUntilIdle()

        d.cycle(rpmAt(1_000L), coolant(90f, 1_000L))
        d.cycle(rpmAt(2_000L))
        advanceUntilIdle()
        sub.cancel()

        assertNull(d.vm.uiState.value.engineTempC)
    }

    @Test
    fun `a stale display forgets the held temperature`() = runTest {
        val d = driven(fakeAppPreferences(rpmDynamicThreshold = true))
        val sub = launch { d.vm.rpmDynamicThreshold.collect {} }
        advanceUntilIdle()
        d.cycle(rpmAt(1_000L), coolant(90f, 1_000L))
        advanceUntilIdle()
        assertEquals(90f, d.vm.uiState.value.engineTempC!!, 0.001f)

        d.events.emit(SessionEvent.Stale)
        advanceUntilIdle()
        sub.cancel()

        assertNull(d.vm.uiState.value.engineTempC)
    }

    // ── B3: the user's disconnect ends a running reconnect round without an error ──

    @Test
    fun `an aborted reconnect round leaves a clean disconnected state - no spinner, no error`() = runTest {
        val onEvent = io.mockk.slot<(ConnectionEvent) -> Unit>()
        val coordinator = mockk<ConnectionCoordinator>(relaxed = true) {
            every { start(any(), capture(onEvent)) } just Runs
        }
        val obdRepo = fakeObdRepository {
            every { lastConnectedDevice } returns MutableStateFlow<ScannedDevice?>(null)
        }
        val prefs = fakeAppPreferences()
        val vm = DashboardViewModel(
            obdRepo,
            prefs,
            coordinator,
            ReadingsPipeline(obdRepo, mockk(relaxed = true), prefs, makeAppLogRepository(), makeCrashReporter(), makeGpsSpeedSource(), mockk(relaxed = true)),
            SavedStateHandle(),
        )
        advanceUntilIdle()
        onEvent.captured(ConnectionEvent.Failed(de.adv990de.obd2.ui.UiText.StringResource(de.adv990de.obd2.R.string.err_reconnect_fail)))
        onEvent.captured(ConnectionEvent.Attempting(attempt = 2, total = RECONNECT_ATTEMPT_COUNT))
        assertTrue(vm.uiState.value.isReconnecting)

        onEvent.captured(ConnectionEvent.Aborted)

        assertEquals(DashboardUiState(), vm.uiState.value)
        assertFalse(vm.uiState.value.isReconnecting)
        assertNull("der Nutzer wollte getrennt sein - kein Fehler", vm.uiState.value.error)
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
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { livePids(any()) } returns flowOf(firstReadings, secondReadings)
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(
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

    // ── Leerlauf-Anzeige ("N") ─────────────────────────────────────────────────────
    // Stand + laufender Motor → DashboardUiState.isNeutral (s. isNeutral() in GearIndicator.kt).
    // Geschwindigkeit ist hier der zusammengefuehrte Anzeigewert (ECU zuerst, GPS-Fallback), nicht
    // der rohe GPS-Wert wie beim Gang — die ECU meldet den Stillstand exakt und ohne GPS-Fix.

    private fun cycle(rpm: Float, speedKmh: Float? = null, timestamp: Long = 1000L): List<SensorValue> =
        listOfNotNull(
            SensorValue(pid = PidRegistry.RPM, value = rpm, timestamp = timestamp),
            speedKmh?.let { SensorValue(pid = PidRegistry.SPEED, value = it, timestamp = timestamp) },
        )

    private fun makeNeutralVm(vararg cycles: List<SensorValue>, gpsKmh: Float? = null): DashboardViewModel =
        makeVmFrom(
            makeConnectedObdRepo(flowOf(*cycles)),
            mockk<SessionRepository> {
                coEvery { startSession(any(), any()) } returns 1L
                coEvery { writeReadings(any(), any()) } just Runs
                coEvery { endSession(any()) } just Runs
            },
            fakeAppPreferences(
                useGpsSpeed = gpsKmh != null,
                wheelCircumferenceMm = 2000f,
                primaryRatio = 1f,
                secondaryRatio = 1f,
                gearRatios = listOf(10f, 5f, 3f, 2f, 1.5f, 1.2f),
            ),
            makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(),
            makeGpsSpeedSource(kmh = gpsKmh), mockk(relaxed = true),
        )

    @Test
    fun `neutral is shown when the vehicle stands and the engine runs`() = runTest {
        val vm = makeNeutralVm(cycle(rpm = 1200f, speedKmh = 0f))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isNeutral)
        assertNull(vm.uiState.value.gear)
    }

    // UID-19: eine ECU, die konstant 0 km/h meldet, darf "N" nicht die ganze Fahrt stehen lassen. Beim Anhalten hinkt das GPS dagegen
    // ein bis zwei Sekunden hinterher (siehe `neutral wins over a gear that GPS still reports`) — das ist kein Widerspruch. Erst ein
    // Widerspruch, der ohne Unterbrechung 5 s dauert, hebt "N" auf; die Zeit kommt aus `nowMillis` (monoton).

    private fun drivenWithGears(): Driven = driven(
        fakeAppPreferences(
            useGpsSpeed = true,
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f, 5f, 3f, 2f, 1.5f, 1.2f),
        )
    )

    private suspend fun Driven.cycleAt(clockMs: Long, rpm: Float, ecuKmh: Float, gpsKmh: Float?) {
        vm.nowMillis = { clockMs }
        events.emit(SessionEvent.Values(cycle(rpm, ecuKmh, timestamp = clockMs), gpsSpeedKmh = gpsKmh))
    }

    @Test
    fun `an ECU that always reports 0 stops showing N after 5 seconds of GPS contradiction - and the gear comes back (UID-19)`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        d.cycleAt(0L, rpm = 2500f, ecuKmh = 0f, gpsKmh = 60f)
        advanceUntilIdle()
        assertTrue("das GPS hinkt vielleicht nur nach: zunaechst bleibt N", d.vm.uiState.value.isNeutral)

        d.cycleAt(4_999L, rpm = 2500f, ecuKmh = 0f, gpsKmh = 60f)
        advanceUntilIdle()
        assertTrue("knapp unter 5 s", d.vm.uiState.value.isNeutral)

        d.cycleAt(5_000L, rpm = 2500f, ecuKmh = 0f, gpsKmh = 60f)
        advanceUntilIdle()
        assertFalse("5 s Widerspruch: kein N mehr", d.vm.uiState.value.isNeutral)
        // rpm 2500 bei 60 km/h und Rad 2000 mm: Uebersetzung 5 -> zweiter Gang, aus dem GPS.
        assertEquals(2, d.vm.uiState.value.gear)
    }

    @Test
    fun `the GPS catching up with a stop never takes N away - the contradiction ends before it counts`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        d.cycleAt(0L, rpm = 1200f, ecuKmh = 0f, gpsKmh = 12f)      // ECU steht, GPS hinkt
        d.cycleAt(1_000L, rpm = 1200f, ecuKmh = 0f, gpsKmh = 4f)   // GPS ist unten
        advanceUntilIdle()
        assertTrue(d.vm.uiState.value.isNeutral)

        d.cycleAt(9_000L, rpm = 1200f, ecuKmh = 0f, gpsKmh = 9f)   // spaeter wieder ein GPS-Ausreisser: die Zaehlung beginnt neu
        advanceUntilIdle()
        assertTrue("ein Ausreisser nach langer Ruhe loest nichts aus", d.vm.uiState.value.isNeutral)
    }

    @Test
    fun `the contradiction has to be uninterrupted - one quiet cycle restarts the count`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        d.cycleAt(0L, rpm = 2500f, ecuKmh = 0f, gpsKmh = 60f)
        d.cycleAt(3_000L, rpm = 2500f, ecuKmh = 0f, gpsKmh = 2f)   // Ruhe: Zaehlung zurueckgesetzt
        d.cycleAt(6_000L, rpm = 2500f, ecuKmh = 0f, gpsKmh = 60f)  // seit 0 waeren das 6 s, seit der Pause sind es 0
        advanceUntilIdle()

        assertTrue(d.vm.uiState.value.isNeutral)
    }

    @Test
    fun `the contradiction clock starts over with every connection`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()
        d.cycleAt(0L, rpm = 2500f, ecuKmh = 0f, gpsKmh = 60f)
        advanceUntilIdle()

        d.events.emit(SessionEvent.Idle)
        advanceUntilIdle()
        d.cycleAt(6_000L, rpm = 2500f, ecuKmh = 0f, gpsKmh = 60f)  // 6 s nach dem alten Beginn, aber eine neue Verbindung
        advanceUntilIdle()

        assertTrue(d.vm.uiState.value.isNeutral)
    }

    @Test
    fun `GPS noise at a standstill does not veto neutral`() = runTest {
        val vm = makeNeutralVm(cycle(rpm = 1200f, speedKmh = 0f), gpsKmh = 3f)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isNeutral)
    }

    @Test
    fun `neutral also works from GPS speed alone when the vehicle reports none`() = runTest {
        // Kein SPEED-PID vom Fahrzeug, aber GPS meldet 0 km/h: die Zusammenfuehrung (mergeGpsSpeed)
        // liefert den Wert als Anzeige-Geschwindigkeit, daraus wird "N".
        val vm = makeNeutralVm(cycle(rpm = 1200f), gpsKmh = 0f)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isNeutral)
    }

    @Test
    fun `neutral is not shown without any speed`() = runTest {
        // Weder ECU noch GPS: unbekannt statt "steht" — nie ein falsches "N".
        val vm = makeNeutralVm(cycle(rpm = 1200f))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isNeutral)
    }

    @Test
    fun `neutral is not shown when the engine is not running`() = runTest {
        val vm = makeNeutralVm(cycle(rpm = 0f, speedKmh = 0f))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isNeutral)
    }

    @Test
    fun `neutral holds through slow speed noise but slow speed alone does not enter it`() = runTest {
        // Hysterese-Verdrahtung wie bei `gear stays locked across updates...`: 3 km/h liegen
        // zwischen Eintritts- (2) und Haltegrenze (4). Mit wasNeutral aus dem vorigen Zyklus bleibt
        // "N" stehen; ohne ihn (frischer Start) tritt es nicht ein. Eine Verdrahtung, die wasNeutral
        // immer als false uebergibt, wuerde den ersten Fall brechen.
        val holding = makeNeutralVm(cycle(1200f, 0f, 1000L), cycle(1200f, 3f, 2000L))
        val fresh = makeNeutralVm(cycle(1200f, 3f))
        advanceUntilIdle()

        assertTrue("an already neutral state holds at 3 km/h", holding.uiState.value.isNeutral)
        assertFalse("3 km/h alone does not enter neutral", fresh.uiState.value.isNeutral)
    }

    @Test
    fun `neutral clears when the vehicle pulls away`() = runTest {
        val vm = makeNeutralVm(cycle(1200f, 0f, 1000L), cycle(2500f, 5f, 2000L))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isNeutral)
        assertNull(vm.uiState.value.gear)
    }

    @Test
    fun `neutral wins over a gear that GPS still reports`() = runTest {
        // Beim Anhalten meldet die ECU sofort 0 km/h, waehrend die (verzoegerte) GPS-Geschwindigkeit
        // noch 60 km/h zeigt und mit 5000 rpm exakt Gang 1 trifft (Fixture wie `gear is computed
        // from rpm and GPS speed...`). "N" gewinnt und der Gang wird geleert — die Invariante
        // isNeutral => gear == null haelt Anzeige und Hysterese-Gedaechtnis konsistent.
        val vm = makeNeutralVm(cycle(rpm = 5000f, speedKmh = 0f), gpsKmh = 60f)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isNeutral)
        assertNull(vm.uiState.value.gear)
    }

    @Test
    fun `neutral resets after a disconnect during reconnect handling`() = runTest {
        // Spiegel zu `gear resets to null after a disconnect during reconnect handling`: der
        // applyIdle()-Zweig "Fehler/Wiederverbindung" setzt per copy() zurueck, ein vergessenes
        // isNeutral = false liesse nach dem Abbruch ein altes "N" stehen. Relaxed
        // ConnectionCoordinator aus demselben Grund wie dort (sonst ersetzt onConnectionEvent den
        // ganzen State schon vor applyIdle()); delay(1) stellt die reale Zeit-Trennung zwischen
        // zwei Zyklen nach, s. dortigen Kommentar.
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flow {
                emit(cycle(rpm = 1200f, speedKmh = 0f))
                delay(1)
                isConnectedFlow.value = false
                throw ObdException.ConnectionLost()
            }
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences()

        val vm = DashboardViewModel(
            obdRepo,
            prefs,
            mockk<ConnectionCoordinator>(relaxed = true),
            ReadingsPipeline(obdRepo, sessionRepo, prefs, makeAppLogRepository(), makeCrashReporter(), makeGpsSpeedSource(), mockk(relaxed = true)),
            SavedStateHandle(),
        )
        isConnectedFlow.value = true
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isReconnecting)
        assertFalse(vm.uiState.value.isNeutral)
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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(readings)
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(rpmDynamicThreshold = false)

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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(readings)
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(rpmDynamicThreshold = true)

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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(readings)
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(rpmDynamicThreshold = true)

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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(readings)
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(rpmDynamicThreshold = true)

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
        val obdRepo = fakeObdRepository {
            every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
            every { livePids(any()) } returns flowOf(readings)
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }
        val prefs = fakeAppPreferences(rpmDynamicThreshold = true)

        val vm = makeVmFrom(obdRepo, sessionRepo, prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        val job = launch { vm.rpmDynamicThreshold.collect {} }
        advanceUntilIdle()
        isConnectedFlow.value = true
        advanceUntilIdle()
        job.cancel()

        assertNull(vm.uiState.value.engineTempC)
    }

    // ── Oel-Balken-Farbe (oilBarColor) ───────────────────────────────────────────
    // Die reine Schwellwert-/Timer-Logik (updateOilBarTimer/oilBarColor) ist bereits in
    // OilTemperatureBarTest direkt abgedeckt. Hier wird nur das Zusammenspiel mit
    // applyReadings()/DashboardUiState geprueft: die Kuehlmitteltemperatur-Extraktion aus den
    // Readings, das Threading der injizierten Uhr ueber mehrere Zyklen, und das Halten des Werts
    // bei einem einzelnen Zyklus ohne Kuehlmittelwert (Analogie zu holdGear()).

    @Test
    fun `oilBarColor is Red when coolant is at or below 75C`() = runTest {
        val readings = listOf(
            SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 70f, timestamp = 1000L),
        )
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { livePids(any()) } returns flowOf(readings)
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(OilBarColor.Red, vm.uiState.value.oilBarColor)
    }

    // Die folgenden Tests treiben applyReadings() ueber einen livePids()-Flow mit echten
    // delay()-Schritten unter virtueller Zeit. nowMillis liest testScheduler.currentTime statt
    // eines separat mitgefuehrten Zaehlers — die injizierte Uhr ist damit IMMER exakt die
    // virtuelle Testzeit, unabhaengig davon, wie merge()/combine() die Verarbeitung intern auf
    // Coroutinen verteilen. Messwerte kommen im Sekundentakt wie im echten OBD-Betrieb: seit dem
    // 60-s-Luecken-Limit (MAX_SAMPLE_GAP_MS) setzen Minutenabstaende den Gruen-Timer bewusst
    // zurueck, und seit der Zeitstempel-Pruefung zaehlt nur ein Messwert mit NEUEM Zeitstempel.

    @Test
    fun `oilBarColor turns Green after ten continuous minutes of fresh readings above 80C`() = runTest {
        // Regressionsschutz fuer den Normalfall: frische Messwerte (je eigener Zeitstempel) im
        // Sekundentakt ab t=0. Gelb bis 9:59, Gruen ab genau 10:00.
        val obdRepo = makeConnectedObdRepo(flow {
            repeat(11 * 60) {
                emit(listOf(coolantReading(85f, timestamp = testScheduler.currentTime)))
                delay(1_000L)
            }
        })
        val vm = makeOilBarVm(obdRepo, nowMillis = { testScheduler.currentTime })

        advanceTimeBy(10 * 60_000L)  // verarbeitet alle Messwerte vor t=10:00, der letzte bei 9:59
        assertEquals(OilBarColor.Yellow, vm.uiState.value.oilBarColor)
        advanceUntilIdle()
        assertEquals(OilBarColor.Green, vm.uiState.value.oilBarColor)
    }

    @Test
    fun `a re-delivered coolant reading with an unchanged timestamp does not advance the green timer`() = runTest {
        // Ein echter Messwert bei t=0 (85 C), danach 11 Minuten lang derselbe Messwert mit
        // DEMSELBEN Zeitstempel, jede Sekunde erneut zugestellt — waehrend die Uhr weiterlaeuft.
        // Ohne Zeitstempel-Pruefung zaehlte jede Wiederholung als Bestaetigung (Abstand 1 s, also
        // kein Luecken-Reset) und der Balken wuerde bei t=10:00 gruen, obwohl nach t=0 nie ein
        // neuer Messwert kam.
        val sameReading = listOf(coolantReading(85f, timestamp = 1_000L))
        val obdRepo = makeConnectedObdRepo(flow {
            repeat(11 * 60) {
                emit(sameReading)
                delay(1_000L)
            }
        })
        val vm = makeOilBarVm(obdRepo, nowMillis = { testScheduler.currentTime })
        advanceUntilIdle()

        assertEquals(OilBarColor.Yellow, vm.uiState.value.oilBarColor)
    }

    @Test
    fun `GPS-triggered replays of the last OBD cycle do not advance the green timer`() = runTest {
        // Der konkrete Mechanismus aus dem Review: Zuendung aus, das Steuergeraet antwortet nur
        // noch NO DATA (livePids() emittiert nichts mehr, der Watchdog toleriert das beliebig
        // lange), aber jede GPS-Aenderung stellte per ReadingsPipeline.gpsRefresh() die letzten
        // OBD-Werte erneut zu — inklusive des alten Kuehlmittelwerts mit seinem alten Zeitstempel.
        //
        // Seit B29 beendet die Pipeline diese Wiederholungen nach OBD_STALE_AFTER_MS selbst (s.
        // ReadingsPipelineTest). Die Sperre im ViewModel (lastOilBarSampleTimestamp) bleibt als zweite
        // Verteidigung — deshalb speist dieser Test die Wiederholungen DIREKT ein, ueber eine
        // Pipeline, die sie unbegrenzt liefert, statt sich auf deren Begrenzung zu verlassen.
        val replayed = coolantReading(85f, timestamp = 1_000L)
        val pipeline = strictPipeline(
            flow {
                repeat(11 * 60) {
                    emit(SessionEvent.Values(listOf(replayed), gpsSpeedKmh = 10f))
                    delay(1_000L)
                }
            }
        )
        val obdRepo = fakeObdRepository()
        val vm = DashboardViewModel(
            obdRepo, fakeAppPreferences(), mockk<ConnectionCoordinator>(relaxed = true), pipeline, SavedStateHandle(),
        ).also { it.nowMillis = { testScheduler.currentTime } }
        advanceUntilIdle()

        // Beleg, dass die Wiederholungen tatsaechlich bis applyReadings() durchkamen (sonst waere der
        // Test wertlos): der Kuehlmittelwert steht in der Anzeige, und der Balken hat eine Farbe.
        assertTrue(
            "die Wiederholungen kamen an: ${vm.uiState.value.readings}",
            vm.uiState.value.readings.any { it.pid.id == PidRegistry.COOLANT_TEMP.id }
        )
        assertEquals(OilBarColor.Yellow, vm.uiState.value.oilBarColor)
    }

    @Test
    fun `a pause of more than 60s between fresh readings restarts the green count`() = runTest {
        // Frischer Messwert bei t=0, dann 61 s nichts, ab t=61 s wieder frische Messwerte im
        // Sekundentakt. Der Timer startet bei t=61 s neu: bei t=10:00 noch Gelb, Gruen erst bei
        // t=11:01. Prueft zugleich, dass das ViewModel lastSampleAtMillis zwischen den Zyklen
        // mitfuehrt (die reine Luecken-Logik selbst deckt OilTemperatureBarTest ab).
        val obdRepo = makeConnectedObdRepo(flow {
            emit(listOf(coolantReading(85f, timestamp = testScheduler.currentTime)))
            delay(61_000L)
            repeat(10 * 60 + 1) {
                emit(listOf(coolantReading(85f, timestamp = testScheduler.currentTime)))
                delay(1_000L)
            }
        })
        val vm = makeOilBarVm(obdRepo, nowMillis = { testScheduler.currentTime })

        advanceTimeBy(10 * 60_000L + 1)  // bis einschliesslich t=10:00
        assertEquals(OilBarColor.Yellow, vm.uiState.value.oilBarColor)
        advanceUntilIdle()               // letzter Messwert bei t=11:01
        assertEquals(OilBarColor.Green, vm.uiState.value.oilBarColor)
    }

    @Test
    fun `a brief disconnect of less than 60s keeps the green count`() = runTest {
        // Frische Messwerte t=0..4:59, Verbindung weg bei t=5:00.5, zurueck bei t=5:30.5, danach
        // wieder frische Messwerte. Luecke zwischen zwei Messwerten: 31,5 s < 60 s — der Timer
        // laeuft von t=0 weiter, Gruen also bei t=10:00 (nicht erst 10 Minuten nach dem Reconnect).
        // Waehrend der Trennung ist der Balken grau (oilBarColor == null).
        val isConnectedFlow = MutableStateFlow(true)
        val beforeDrop = flow {
            repeat(5 * 60) {
                emit(listOf(coolantReading(85f, timestamp = testScheduler.currentTime)))
                delay(1_000L)
            }
            awaitCancellation()
        }
        val afterReconnect = flow {
            repeat(5 * 60) {
                emit(listOf(coolantReading(85f, timestamp = testScheduler.currentTime)))
                delay(1_000L)
            }
        }
        val obdRepo = makeConnectedObdRepo(beforeDrop, afterReconnect, isConnectedFlow = isConnectedFlow)
        val vm = makeOilBarVm(obdRepo, nowMillis = { testScheduler.currentTime })

        advanceTimeBy(5 * 60_000L + 500)
        assertEquals(OilBarColor.Yellow, vm.uiState.value.oilBarColor)
        isConnectedFlow.value = false
        advanceTimeBy(30_000L)
        assertNull(vm.uiState.value.oilBarColor)
        isConnectedFlow.value = true
        advanceTimeBy(10 * 60_000L - (5 * 60_000L + 500 + 30_000L))  // bis kurz vor t=10:00
        assertEquals(OilBarColor.Yellow, vm.uiState.value.oilBarColor)
        advanceUntilIdle()
        assertEquals(OilBarColor.Green, vm.uiState.value.oilBarColor)
    }

    @Test
    fun `oilBarColor holds its last value through a single cycle missing the coolant reading`() = runTest {
        // Analogie zu `gear holds through a single implausible reading instead of dropping to
        // unknown`: ein Zyklus ohne COOLANT_TEMP-Reading darf die zuletzt bestimmte Farbe nicht
        // auf null zuruecksetzen.
        val firstReadings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 1000L),
            SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 85f, timestamp = 1000L),
        )
        val secondReadings = listOf(
            SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 2000L),
            // Kein COOLANT_TEMP in diesem Zyklus.
        )
        val obdRepo = fakeObdRepository {
            every { isConnectedFlow } returns MutableStateFlow(false).also { it.value = true }
            every { livePids(any()) } returns flowOf(firstReadings, secondReadings)
        }
        val sessionRepo = mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        }

        val vm = makeVmFrom(obdRepo, sessionRepo, fakeAppPreferences(), makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), makeGpsSpeedSource(), mockk(relaxed = true))
        advanceUntilIdle()

        // 85 C > 80 C, aber der Gruen-Timer laeuft erst seit dem 1. Zyklus -> Yellow, nicht Red
        // und nicht null (der 2. Zyklus ohne Kuehlmittelwert darf das nicht veraendern).
        assertEquals(OilBarColor.Yellow, vm.uiState.value.oilBarColor)
    }

    @Test
    fun `oilBarTimerState survives an Activity-only recreation via the shared SavedStateHandle`() = runTest {
        // Regressionsschutz fuer Backlog-Sweep 2026-09-28, Fund 2: oilBarTimerState und
        // lastOilBarSampleTimestamp waren reine In-Memory-Felder. Eine Activity-Neuerzeugung bei
        // laufendem Prozess (Dev-Option "Nicht im Hintergrund behalten", aggressives
        // OEM-Reclaiming) erzeugt ein frisches DashboardViewModel, ohne dass ConnectionLost oder
        // das 60s-Luecken-Limit je eintraten — der Gruen-Timer waere stillschweigend auf Null
        // zurueckgefallen. Simuliert hier durch zwei ViewModel-Instanzen, die dieselbe
        // SavedStateHandle-Instanz teilen, genau wie Android es bei einer Activity-Neuerzeugung
        // tut (Bundle aus onSaveInstanceState) — mit einer 50s-Luecke dazwischen (real, aber klar
        // unter dem 60s-Limit), damit ein Reset durchs Luecken-Limit selbst ausgeschlossen ist.
        val handle = SavedStateHandle()

        // VM1: ein einzelner Messwert bei t=0 setzt den Gruen-Timer-Start.
        val vm1 = makeOilBarVm(
            makeConnectedObdRepo(flowOf(listOf(coolantReading(85f, timestamp = 0L)))),
            nowMillis = { testScheduler.currentTime },
            savedStateHandle = handle,
        )
        advanceUntilIdle()
        assertEquals(OilBarColor.Yellow, vm1.uiState.value.oilBarColor)

        // "Neuerzeugung": 50s Luecke, dann ein zweites ViewModel mit DERSELBEN SavedStateHandle-
        // Instanz. Ohne den Fix waere oilBarTimerState hier wieder leer (OilBarTimerState()).
        advanceTimeBy(50_000L)
        val vm2 = makeOilBarVm(
            makeConnectedObdRepo(flow {
                repeat(560) {
                    emit(listOf(coolantReading(85f, timestamp = testScheduler.currentTime)))
                    delay(1_000L)
                }
            }),
            nowMillis = { testScheduler.currentTime },
            savedStateHandle = handle,
        )

        // VM2s Flow laeuft bis knapp t=609s (560 Messwerte im Sekundentakt ab t=50s) und damit
        // klar ueber die vollen 10 Minuten (600s) seit dem URSPRUENGLICHEN Start bei t=0, aber
        // klar UNTER den 10 Minuten seit VM2s eigenem Start bei t=50s (waere erst bei t=650s
        // erreicht). Nur wenn oilBarTimerState wirklich aus dem SavedStateHandle restauriert
        // wurde (statt bei der Neuerzeugung zurueckzufallen auf OilBarTimerState()), kann der
        // Balken in diesem Fenster ueberhaupt schon Gruen zeigen.
        advanceUntilIdle()
        assertEquals(OilBarColor.Green, vm2.uiState.value.oilBarColor)
    }

    private fun coolantReading(celsius: Float, timestamp: Long) =
        SensorValue(pid = PidRegistry.COOLANT_TEMP, value = celsius, timestamp = timestamp)

    /**
     * Verbundenes ObdRepository ohne PID-Discovery fuer die Oel-Balken-Tests. Jeder Sitzungsbeginn
     * ruft livePids() erneut auf und bekommt der Reihe nach den naechsten Strom aus [sessions]
     * (MockK wiederholt danach den letzten).
     */
    private fun makeConnectedObdRepo(
        vararg sessions: Flow<List<SensorValue>>,
        isConnectedFlow: MutableStateFlow<Boolean> = MutableStateFlow(true),
    ): ObdRepository = fakeObdRepository {
        every { this@fakeObdRepository.isConnectedFlow } returns isConnectedFlow
        every { livePids(any()) } returnsMany sessions.toList()
    }

    private fun makeOilBarVm(
        obdRepo: ObdRepository,
        nowMillis: () -> Long,
        prefs: AppPreferences = fakeAppPreferences(),
        gps: GpsSpeedSource = makeGpsSpeedSource(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): DashboardViewModel = makeVmFrom(
        obdRepo,
        mockk<SessionRepository> {
            coEvery { startSession(any(), any()) } returns 1L
            coEvery { writeReadings(any(), any()) } just Runs
            coEvery { endSession(any()) } just Runs
        },
        prefs, makeCrashReporter(), makeBluetoothAdapter(), makeAppLogRepository(), gps,
        mockk(relaxed = true),
        nowMillis = nowMillis,
        savedStateHandle = savedStateHandle,
    )

    /**
     * Baut das ViewModel aus denselben Bausteinen wie Hilt.
     *
     * Seit 1.11.0 bekommt [DashboardViewModel] nur noch Repository, Praeferenzen und die beiden
     * herausgeloesten Kollaborateure. Die Tests hier pruefen weiterhin das Zusammenspiel und
     * uebergeben deshalb die feingranularen Mocks — die eigenen Tests der Kollaborateure stehen
     * in `ConnectionCoordinatorTest` und `ReadingsPipelineTest`.
     */
    // ── Der angezeigte Gang und die Oel-Balken-Farbe stehen in der Aufzeichnung (Nutzerwunsch 2026-10-14) ───────────────────────

    /** Die Gang-Zeilen, die das ViewModel aufzeichnen liess — in der Reihenfolge der Zyklen. */
    private fun Driven.recordedGear(): List<SensorValue> = recorded.flatten().filter { it.pid == DerivedPids.GEAR }

    /** Die Oel-Balken-Zeilen, die das ViewModel aufzeichnen liess — in der Reihenfolge der Zyklen. */
    private fun Driven.recordedOilBar(): List<SensorValue> = recorded.flatten().filter { it.pid == DerivedPids.OIL_BAR }

    /** Ein OBD-Zyklus mit Kuehlmitteltemperatur (und einer Drehzahl), gestempelt mit der Uhr des ViewModels. */
    private suspend fun Driven.coolantCycleAt(clockMs: Long, coolantC: Float?) {
        vm.nowMillis = { clockMs }
        val values = buildList {
            add(SensorValue(pid = PidRegistry.RPM, value = 1200f, timestamp = clockMs))
            if (coolantC != null) add(SensorValue(pid = PidRegistry.COOLANT_TEMP, value = coolantC, timestamp = clockMs))
        }
        events.emit(SessionEvent.Values(values, gpsSpeedKmh = null))
    }

    @Test
    fun `each OBD cycle records the gear the display shows, stamped with the cycle`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        // 60 km/h per GPS, 5000 rpm, Rad 2000 mm, Uebersetzung 10 -> erster Gang
        d.cycleAt(1_000L, rpm = 5000f, ecuKmh = 60f, gpsKmh = 60f)
        advanceUntilIdle()

        assertEquals(1, d.vm.uiState.value.gear)
        assertEquals(listOf(gearSensorValue(GearReading(gear = 1, isNeutral = false), 1_000L)), d.recordedGear())
    }

    @Test
    fun `N is recorded as N`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        d.cycleAt(2_000L, rpm = 1200f, ecuKmh = 0f, gpsKmh = 0f)
        advanceUntilIdle()

        assertTrue(d.vm.uiState.value.isNeutral)
        assertEquals(listOf(gearSensorValue(GearReading(gear = null, isNeutral = true), 2_000L)), d.recordedGear())
    }

    @Test
    fun `a cycle without a determinable gear records the dash`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        // ohne GPS-Fix kein Gang, und die Geschwindigkeit des Fahrzeugs (50) ist kein "steht" -> weder Gang noch N
        d.cycleAt(3_000L, rpm = 1200f, ecuKmh = 50f, gpsKmh = null)
        advanceUntilIdle()

        assertEquals(null, d.vm.uiState.value.gear)
        assertFalse(d.vm.uiState.value.isNeutral)
        assertEquals(listOf(gearSensorValue(GearReading.Unknown, 3_000L)), d.recordedGear())
    }

    @Test
    fun `a GPS refresh between two OBD cycles shows a new gear but records nothing`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        // dieselben OBD-Werte, neuer GPS-Fix: das Dashboard rechnet neu, die Aufzeichnung bleibt beim OBD-Takt
        d.events.emit(SessionEvent.Values(cycle(5000f, 60f, timestamp = 1_000L), gpsSpeedKmh = 60f, isObdCycle = false))
        advanceUntilIdle()

        assertEquals(1, d.vm.uiState.value.gear)
        assertTrue("ein GPS-Zwischenupdate zeichnet nichts auf: ${d.recorded}", d.recorded.isEmpty())
    }

    @Test
    fun `an event without any value is no cycle and records nothing`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        d.events.emit(SessionEvent.Values(emptyList(), gpsSpeedKmh = null))
        advanceUntilIdle()

        assertTrue("ein Ereignis ohne Wert ist kein Zyklus: ${d.recorded}", d.recorded.isEmpty())
    }

    @Test
    fun `the timestamp is the newest one of the cycle`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        d.events.emit(
            SessionEvent.Values(
                listOf(
                    SensorValue(pid = PidRegistry.RPM, value = 5000f, timestamp = 4_000L),
                    SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 80f, timestamp = 4_250L),
                ),
                gpsSpeedKmh = 60f,
            )
        )
        advanceUntilIdle()

        assertEquals(listOf(4_250L), d.recordedGear().map { it.timestamp })
        assertEquals(listOf(4_250L), d.recordedOilBar().map { it.timestamp })
    }

    @Test
    fun `each OBD cycle records the gear and the oil bar together, in one call`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        d.coolantCycleAt(1_000L, coolantC = 70f)
        advanceUntilIdle()

        assertEquals(
            "ein Aufruf je Zyklus mit beiden Zeilen, Gang zuerst",
            listOf(listOf(gearSensorValue(GearReading.Unknown, 1_000L), oilBarSensorValue(OilBarColor.Red, 1_000L))),
            d.recorded,
        )
    }

    @Test
    fun `a cold engine is recorded as red, a warm one as yellow`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        d.coolantCycleAt(1_000L, coolantC = 75f)    // <= 75: rot
        d.coolantCycleAt(2_000L, coolantC = 76f)    // darueber: gelb, solange der Gruen-Timer nicht 10 Minuten voll hat
        advanceUntilIdle()

        assertEquals(listOf(0f, 1f), d.recordedOilBar().map { it.value })
        assertTrue(d.recordedOilBar().all { it.source == ValueSource.DERIVED })
    }

    @Test
    fun `before the first coolant value the bar is grey and recorded as -1`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        d.coolantCycleAt(1_000L, coolantC = null)
        advanceUntilIdle()

        assertEquals(null, d.vm.uiState.value.oilBarColor)
        assertEquals(listOf(-1f), d.recordedOilBar().map { it.value })
    }

    @Test
    fun `a cycle without a new coolant value records the colour the display keeps`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        d.coolantCycleAt(1_000L, coolantC = 70f)    // rot
        d.coolantCycleAt(2_000L, coolantC = null)   // kein Kuehlmittelwert: die Anzeige haelt Rot
        advanceUntilIdle()

        assertEquals(OilBarColor.Red, d.vm.uiState.value.oilBarColor)
        assertEquals(listOf(0f, 0f), d.recordedOilBar().map { it.value })
    }

    @Test
    fun `after ten minutes above 80 degrees the recorded colour is green - the one the display shows`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        // alle 30 s ein neuer Kuehlmittelwert (unter dem Luecken-Limit von 60 s), zehn Minuten lang ueber 80 Grad; jeder Zyklus wird
        // verarbeitet, bevor die Uhr weiterspringt (das ViewModel liest sie erst beim Verarbeiten)
        for (clock in 0L..600_000L step 30_000L) {
            d.coolantCycleAt(clock, coolantC = 85f)
            advanceUntilIdle()
        }

        assertEquals(OilBarColor.Green, d.vm.uiState.value.oilBarColor)
        val colours = d.recordedOilBar().map { it.value }
        assertEquals("bis zur letzten Minute gelb: $colours", 1f, colours[colours.size - 2], 0f)
        assertEquals("im letzten Zyklus gruen: $colours", 2f, colours.last(), 0f)
    }

    @Test
    fun `a short dip does not turn the recorded colour back - the debounced display is what is recorded`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        for (clock in 0L..600_000L step 30_000L) {
            d.coolantCycleAt(clock, coolantC = 85f)
            advanceUntilIdle()
        }
        for (clock in listOf(630_000L, 660_000L)) {
            d.coolantCycleAt(clock, coolantC = 77f)    // Einbruch <= 78: der Balken bleibt gruen (Entprellen 120 s)
            advanceUntilIdle()
        }

        assertEquals(listOf(2f, 2f, 2f), d.recordedOilBar().map { it.value }.takeLast(3))
    }

    @Test
    fun `a dip that lasts 120 seconds turns the recorded colour back to yellow, at the cycle where the display does`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        for (clock in 0L..600_000L step 30_000L) {
            d.coolantCycleAt(clock, coolantC = 85f)
            advanceUntilIdle()
        }
        for (clock in 630_000L..750_000L step 30_000L) {   // Einbruch ab 630 s, 120 s ununterbrochen um 750 s
            d.coolantCycleAt(clock, coolantC = 77f)
            advanceUntilIdle()
        }

        assertEquals(OilBarColor.Yellow, d.vm.uiState.value.oilBarColor)
        assertEquals(listOf(2f, 2f, 2f, 2f, 1f), d.recordedOilBar().map { it.value }.takeLast(5))
    }

    // ── Die Restzeit bis Gruen im UI-State: der Balken zeigt den Timer als fortlaufenden Farbwechsel (Nutzerwunsch 2026-10-16) ──

    @Test
    fun `the ui state starts with the full ten minutes - no warm-up is shown`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        assertEquals(OIL_BAR_GREEN_HOLD_MS, d.vm.uiState.value.oilBarGreenInMillis)
    }

    @Test
    fun `the ui state counts down from the first coolant value above 80`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()

        d.coolantCycleAt(1_000L, coolantC = 78f)    // nicht ueber 80: der Timer laeuft noch nicht
        advanceUntilIdle()
        assertEquals(600_000L, d.vm.uiState.value.oilBarGreenInMillis)
        assertEquals(OilBarColor.Yellow, d.vm.uiState.value.oilBarColor)

        d.coolantCycleAt(31_000L, coolantC = 85f)   // erster Wert ueber 80: Start
        advanceUntilIdle()
        assertEquals(600_000L, d.vm.uiState.value.oilBarGreenInMillis)

        d.coolantCycleAt(61_000L, coolantC = 85f)
        advanceUntilIdle()
        assertEquals(570_000L, d.vm.uiState.value.oilBarGreenInMillis)
        assertEquals(OilBarColor.Yellow, d.vm.uiState.value.oilBarColor)
    }

    @Test
    fun `a cold engine takes the shown progress back to nothing`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()
        d.coolantCycleAt(0L, coolantC = 85f)
        advanceUntilIdle()
        d.coolantCycleAt(30_000L, coolantC = 85f)
        advanceUntilIdle()
        assertEquals(570_000L, d.vm.uiState.value.oilBarGreenInMillis)

        d.coolantCycleAt(60_000L, coolantC = 70f)   // <= 75: sofort zurueck, der Balken wird rot
        advanceUntilIdle()

        assertEquals(600_000L, d.vm.uiState.value.oilBarGreenInMillis)
        assertEquals(OilBarColor.Red, d.vm.uiState.value.oilBarColor)
    }

    @Test
    fun `a GPS refresh moves the shown progress too - it follows the clock, not only new readings`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()
        d.coolantCycleAt(0L, coolantC = 85f)
        advanceUntilIdle()
        d.coolantCycleAt(30_000L, coolantC = 85f)
        advanceUntilIdle()

        d.vm.nowMillis = { 45_000L }
        d.events.emit(
            SessionEvent.Values(
                listOf(
                    SensorValue(pid = PidRegistry.RPM, value = 1200f, timestamp = 30_000L),
                    SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 85f, timestamp = 30_000L),
                ),
                gpsSpeedKmh = 5f,
                isObdCycle = false,
            )
        )
        advanceUntilIdle()

        assertEquals(555_000L, d.vm.uiState.value.oilBarGreenInMillis)
    }

    @Test
    fun `losing the connection clears the shown progress together with the bar colour`() = runTest {
        val d = drivenWithGears()
        advanceUntilIdle()
        d.coolantCycleAt(0L, coolantC = 85f)
        advanceUntilIdle()
        d.coolantCycleAt(30_000L, coolantC = 85f)
        advanceUntilIdle()
        assertEquals(570_000L, d.vm.uiState.value.oilBarGreenInMillis)

        d.events.emit(SessionEvent.Idle)
        advanceUntilIdle()

        assertEquals(null, d.vm.uiState.value.oilBarColor)
        assertEquals(OIL_BAR_GREEN_HOLD_MS, d.vm.uiState.value.oilBarGreenInMillis)
    }

    private fun makeVmFrom(
        obdRepo: ObdRepository,
        sessionRepo: SessionRepository,
        prefs: AppPreferences,
        crashReporter: CrashReporter,
        btAdapter: BluetoothAdapter,
        logRepo: AppLogRepository,
        gpsSource: GpsSpeedSource,
        appContext: Context,
        nowMillis: () -> Long = { System.currentTimeMillis() },
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): DashboardViewModel = DashboardViewModel(
        obdRepo,
        prefs,
        ConnectionCoordinator(
            obdRepo, prefs, logRepo, btAdapter,
            de.adv990de.obd2.ObdServiceController(
                obdRepo, prefs, appContext, logRepo,
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            ),
        ),
        ReadingsPipeline(obdRepo, sessionRepo, prefs, logRepo, crashReporter, gpsSource, mockk(relaxed = true)),
        savedStateHandle,
    ).also { it.nowMillis = nowMillis }
}