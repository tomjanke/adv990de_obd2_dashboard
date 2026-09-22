package de.adv990de.obd2.ui.settings

import android.content.Context
import de.adv990de.obd2.ObdForegroundService
import de.adv990de.obd2.bluetooth.BluetoothDeviceScanner
import de.adv990de.obd2.bluetooth.BluetoothException
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    private fun makeVm(
        obdRepo: ObdRepository = mockk {
            every { isConnectedFlow } returns MutableStateFlow(false)
            every { lastConnectedDevice } returns MutableStateFlow(null)
            coEvery { connect(any()) } just Runs
        },
        prefs: AppPreferences = mockk {
            coEvery { persistDevice(any()) } just Runs
            every { lastDevice } returns flowOf(null)
            every { showAllBtDevices } returns flowOf(false)
            every { useGpsSpeed } returns flowOf(false)
        },
        appLogRepository: AppLogRepository = mockk(relaxed = true),
        scanner: BluetoothDeviceScanner = mockk {
            every { bondedDevices(any()) } returns emptyList()
        },
        appContext: Context = mockk(relaxed = true)
    ) = SettingsViewModel(scanner, obdRepo, prefs, appLogRepository, appContext)

    @Test
    fun `connect persists device on success`() = runTest {
        val device = mockk<ScannedDevice> { every { name } returns "ELM327"; every { address } returns "AA:BB:CC:DD:EE:FF" }
        val prefs = mockk<AppPreferences> {
            coEvery { persistDevice(any()) } just Runs
            every { lastDevice } returns flowOf(null)
            every { showAllBtDevices } returns flowOf(false)
            every { useGpsSpeed } returns flowOf(false)
        }
        val vm = makeVm(prefs = prefs)

        vm.connect(device)
        advanceUntilIdle()

        coVerify { prefs.persistDevice(device) }
    }

    @Test
    fun `isConnecting is true during connect and false after success`() = runTest {
        val device = mockk<ScannedDevice> { every { name } returns "ELM327"; every { address } returns "AA:BB:CC:DD:EE:FF" }
        // Use a real delay so the coroutine suspends at connect() and the intermediate
        // isConnecting=true state is observable via runCurrent().
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false)
            coEvery { connect(any()) } coAnswers { kotlinx.coroutines.delay(10) }
        }
        val vm = makeVm(obdRepo = obdRepo)

        vm.connect(device)
        runCurrent()  // runs up to delay(10) — connect() is suspended, isConnecting still true
        assertTrue(vm.uiState.value.isConnecting)

        advanceUntilIdle()
        assertFalse(vm.uiState.value.isConnecting)
        assertEquals("ELM327", vm.uiState.value.connectedDevice)
    }

    @Test
    fun `error state set and isConnecting cleared when connect throws`() = runTest {
        val device = mockk<ScannedDevice> { every { name } returns "ELM327" }
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false)
            coEvery { connect(any()) } throws Exception("connection refused")
        }
        val vm = makeVm(obdRepo = obdRepo)

        vm.connect(device)
        advanceUntilIdle()

        assertNull(vm.uiState.value.connectedDevice)
        assertTrue(!vm.uiState.value.isConnecting)
        assertNotNull(vm.uiState.value.error)
        assertEquals("connection refused", vm.uiState.value.error)
    }

    @Test
    fun `connectedDevice cleared when isConnectedFlow emits false`() = runTest {
        val isConnectedFlow = MutableStateFlow(false)
        val btDevice = mockk<android.bluetooth.BluetoothDevice> { every { address } returns "AA:BB:CC:DD:EE:FF" }
        val device = mockk<ScannedDevice> {
            every { name } returns "ELM327"
            every { device } returns btDevice
            every { address } returns "AA:BB:CC:DD:EE:FF"
        }
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { connect(any()) } just Runs
        }
        // Prefs must return the device so the isConnectedFlow(true) handler can read its name
        val prefs = mockk<AppPreferences> {
            coEvery { persistDevice(any()) } just Runs
            every { lastDevice } returns flowOf(device)
            every { showAllBtDevices } returns flowOf(false)
        }
        val vm = makeVm(obdRepo = obdRepo, prefs = prefs)

        isConnectedFlow.value = true
        advanceUntilIdle()
        assertEquals("ELM327", vm.uiState.value.connectedDevice)

        isConnectedFlow.value = false
        advanceUntilIdle()
        assertNull(vm.uiState.value.connectedDevice)
    }

    @Test
    fun `connectedDevice set directly by connect(), DataStore read skipped while isConnecting`() = runTest {
        // Verifies the !isConnecting guard: when connect() is in progress (isConnecting=true),
        // the isConnectedFlow collector must NOT overwrite connectedDevice with the DataStore value.
        val isConnectedFlow = MutableStateFlow(false)
        val device = mockk<ScannedDevice> { every { name } returns "ELM327"; every { address } returns "AA:BB:CC:DD:EE:FF" }
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { connect(any()) } coAnswers {
                // Emit true while isConnecting is still true (connect hasn't returned yet).
                isConnectedFlow.value = true
                // yield() lets the collector coroutine run before connect() returns, so it sees
                // isConnecting=true and skips the DataStore read. Without this, connect() would
                // complete and set isConnecting=false before the collector fires.
                kotlinx.coroutines.yield()
            }
        }
        // DataStore returns null — without the guard this would clear connectedDevice
        val prefs = mockk<AppPreferences> {
            coEvery { persistDevice(any()) } just Runs
            every { lastDevice } returns flowOf(null)
            every { showAllBtDevices } returns flowOf(false)
            every { useGpsSpeed } returns flowOf(false)
        }
        val vm = makeVm(obdRepo = obdRepo, prefs = prefs)

        vm.connect(device)
        advanceUntilIdle()

        // connectedDevice must be "ELM327" (set directly in connect()), not null (from DataStore)
        assertEquals("ELM327", vm.uiState.value.connectedDevice)
    }

    @Test
    fun `connectedDevice set and isConnecting cleared even when persistDevice fails`() = runTest {
        // Verifies best-effort semantics: a DataStore write failure must not block the
        // connected state — connectedDevice and isConnecting should reflect the successful connect.
        val device = mockk<ScannedDevice> { every { name } returns "ELM327"; every { address } returns "AA:BB:CC:DD:EE:FF" }
        val prefs = mockk<AppPreferences> {
            coEvery { persistDevice(any()) } throws Exception("disk full")
            every { lastDevice } returns flowOf(null)
            every { showAllBtDevices } returns flowOf(false)
            every { useGpsSpeed } returns flowOf(false)
        }
        val vm = makeVm(prefs = prefs)

        vm.connect(device)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isConnecting)
        assertEquals("ELM327", vm.uiState.value.connectedDevice)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `connectedDevice backfilled when lastDevice loads after auto-reconnect`() = runTest {
        // Verifies the backfill path: auto-reconnect establishes connection (isConnectedFlow=true)
        // before lastDevice.collect has populated lastDeviceName. When lastDevice emits later,
        // connectedDevice must be set retroactively.
        val isConnectedFlow = MutableStateFlow(false)
        val btDevice = mockk<android.bluetooth.BluetoothDevice> { every { address } returns "AA:BB:CC:DD:EE:FF" }
        val device = mockk<ScannedDevice> {
            every { name } returns "ELM327"
            every { this@mockk.device } returns btDevice
            every { address } returns "AA:BB:CC:DD:EE:FF"
        }
        val lastDeviceFlow = MutableStateFlow<ScannedDevice?>(null)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow(null)
            coEvery { connect(any()) } just Runs
        }
        val prefs = mockk<AppPreferences> {
            coEvery { persistDevice(any()) } just Runs
            every { lastDevice } returns lastDeviceFlow
            every { showAllBtDevices } returns flowOf(false)
        }
        val vm = makeVm(obdRepo = obdRepo, prefs = prefs)

        // Auto-reconnect fires: connected=true but lastDevice still null
        isConnectedFlow.value = true
        advanceUntilIdle()
        assertNull(vm.uiState.value.connectedDevice)

        // DataStore finishes loading the last device
        lastDeviceFlow.value = device
        advanceUntilIdle()

        assertEquals("ELM327", vm.uiState.value.connectedDevice)
    }

    @Test
    fun `connectedDevice stays null on auto-reconnect when DataStore is empty after persistDevice failure`() = runTest {
        // Verifies the backfill path when persistDevice previously failed: DataStore has nothing,
        // so lastDevice emits null. The backfill must set connectedDevice to null (not crash),
        // documenting that the UI will show no device name in this edge case.
        val isConnectedFlow = MutableStateFlow(false)
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            every { lastConnectedDevice } returns MutableStateFlow(null)
            coEvery { connect(any()) } just Runs
        }
        val prefs = mockk<AppPreferences> {
            coEvery { persistDevice(any()) } just Runs
            // Empty DataStore — simulates state when persistDevice previously threw
            every { lastDevice } returns flowOf(null)
            every { showAllBtDevices } returns flowOf(false)
        }
        val vm = makeVm(obdRepo = obdRepo, prefs = prefs)

        // Auto-reconnect fires: isConnecting=false, no manual connect was initiated
        isConnectedFlow.value = true
        advanceUntilIdle()

        // Backfill runs: device is null → connectedDevice stays null
        assertNull(vm.uiState.value.connectedDevice)
    }

    @Test
    fun `disconnect delegates to ObdRepository`() = runTest {
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false)
            every { lastConnectedDevice } returns MutableStateFlow(null)
            coEvery { connect(any()) } just Runs
            coEvery { disconnect() } just Runs
        }
        val vm = makeVm(obdRepo = obdRepo)

        vm.disconnect()
        advanceUntilIdle()

        coVerify { obdRepo.disconnect() }
    }

    @Test
    fun `lastDeviceAddress populated from AppPreferences`() = runTest {
        val btDevice = mockk<android.bluetooth.BluetoothDevice> {
            every { address } returns "AA:BB:CC:DD:EE:FF"
        }
        val scanned = mockk<de.adv990de.obd2.bluetooth.ScannedDevice> {
            every { device } returns btDevice
            every { name } returns "ELM"
            every { type } returns de.adv990de.obd2.bluetooth.BtType.CLASSIC
            every { address } returns "AA:BB:CC:DD:EE:FF"
        }
        val prefs = mockk<AppPreferences> {
            coEvery { persistDevice(any()) } just Runs
            every { lastDevice } returns flowOf(scanned)
            every { showAllBtDevices } returns flowOf(false)
        }
        val vm = makeVm(prefs = prefs)
        advanceUntilIdle()

        assertEquals("AA:BB:CC:DD:EE:FF", vm.uiState.value.lastDeviceAddress)
    }

    @Test
    fun `connect starts foreground service on success`() = runTest {
        val ctx = mockk<Context>(relaxed = true)
        val vm = makeVm(appContext = ctx)
        val device = mockk<ScannedDevice> { every { name } returns "ELM327"; every { address } returns "AA:BB:CC:DD:EE:FF" }
        vm.connect(device)
        advanceUntilIdle()
        // In Android unit test stubs the Intent constructor is a no-op, so we cannot inspect
        // intent properties — verify that the call happened at all (not on failure path).
        verify { ctx.startForegroundService(any()) }
    }

    @Test
    fun `connect does not start foreground service on failure`() = runTest {
        val ctx = mockk<Context>(relaxed = true)
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false)
            coEvery { connect(any()) } throws Exception("connection refused")
        }
        val vm = makeVm(obdRepo = obdRepo, appContext = ctx)
        val device = mockk<ScannedDevice> { every { name } returns "ELM327" }
        vm.connect(device)
        advanceUntilIdle()
        verify(exactly = 0) { ctx.startForegroundService(any()) }
    }

    @Test
    fun `disconnect stops foreground service`() = runTest {
        val ctx = mockk<Context>(relaxed = true)
        val obdRepo = mockk<ObdRepository> {
            every { isConnectedFlow } returns MutableStateFlow(false)
            coEvery { connect(any()) } just Runs
            coEvery { disconnect() } just Runs
        }
        val vm = makeVm(obdRepo = obdRepo, appContext = ctx)
        vm.disconnect()
        advanceUntilIdle()
        // In Android unit test stubs the Intent constructor is a no-op, so we cannot inspect
        // intent properties — verify that the call happened at all.
        verify { ctx.stopService(any()) }
    }

    @Test
    fun `loadDevices yields an empty list and an error when the Bluetooth permission is denied`() = runTest {
        val scanner = mockk<BluetoothDeviceScanner> {
            every { bondedDevices(any()) } throws BluetoothException.PermissionDenied("BLUETOOTH_CONNECT")
        }
        val vm = makeVm(scanner = scanner)
        advanceUntilIdle()

        vm.loadDevices()

        assertTrue(vm.uiState.value.bondedDevices.isEmpty())
        assertNotNull("Der Nutzer soll erfahren, warum keine Geraete erscheinen", vm.uiState.value.error)
    }

    @Test
    fun `loadDevices survives a SecurityException from the scanner`() = runTest {
        val scanner = mockk<BluetoothDeviceScanner> {
            every { bondedDevices(any()) } throws SecurityException("permission revoked")
        }
        val vm = makeVm(scanner = scanner)
        advanceUntilIdle()

        vm.loadDevices()

        assertTrue(vm.uiState.value.bondedDevices.isEmpty())
    }
}
