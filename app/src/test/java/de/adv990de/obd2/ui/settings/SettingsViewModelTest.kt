package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.bluetooth.BluetoothDeviceScanner
import de.adv990de.obd2.bluetooth.ScannedDevice
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
            coEvery { connect(any()) } just Runs
        },
        prefs: AppPreferences = mockk {
            coEvery { persistDevice(any()) } just Runs
            every { lastDevice } returns flowOf(null)
        }
    ) = SettingsViewModel(mockk<BluetoothDeviceScanner>(), obdRepo, prefs)

    @Test
    fun `connect persists device on success`() = runTest {
        val device = mockk<ScannedDevice> { every { name } returns "ELM327" }
        val prefs = mockk<AppPreferences> {
            coEvery { persistDevice(any()) } just Runs
            every { lastDevice } returns flowOf(null)
        }
        val vm = makeVm(prefs = prefs)

        vm.connect(device)
        advanceUntilIdle()

        coVerify { prefs.persistDevice(device) }
    }

    @Test
    fun `isConnecting is true during connect and false after success`() = runTest {
        val device = mockk<ScannedDevice> { every { name } returns "ELM327" }
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
            every { device } returns btDevice  // needed by lastDevice.collect → device?.device?.address
        }
        val obdRepo = mockk<ObdRepository> {
            every { this@mockk.isConnectedFlow } returns isConnectedFlow
            coEvery { connect(any()) } just Runs
        }
        // Prefs must return the device so the isConnectedFlow(true) handler can read its name
        val prefs = mockk<AppPreferences> {
            coEvery { persistDevice(any()) } just Runs
            every { lastDevice } returns flowOf(device)
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
    fun `lastDeviceAddress populated from AppPreferences`() = runTest {
        val btDevice = mockk<android.bluetooth.BluetoothDevice> {
            every { address } returns "AA:BB:CC:DD:EE:FF"
        }
        val scanned = mockk<de.adv990de.obd2.bluetooth.ScannedDevice> {
            every { device } returns btDevice
            every { name } returns "ELM"
            every { type } returns de.adv990de.obd2.bluetooth.BtType.CLASSIC
        }
        val prefs = mockk<AppPreferences> {
            coEvery { persistDevice(any()) } just Runs
            every { lastDevice } returns flowOf(scanned)
        }
        val vm = makeVm(prefs = prefs)
        advanceUntilIdle()

        assertEquals("AA:BB:CC:DD:EE:FF", vm.uiState.value.lastDeviceAddress)
    }
}
