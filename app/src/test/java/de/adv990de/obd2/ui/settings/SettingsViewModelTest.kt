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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
