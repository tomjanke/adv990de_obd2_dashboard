package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class ScannedDeviceTest {

    @Test
    fun `address returns the real device's address when a non-null BluetoothDevice is present`() {
        val btDevice: BluetoothDevice = mockk {
            every { address } returns "AA:BB:CC:DD:EE:01"
        }
        val scannedDevice = ScannedDevice(device = btDevice, type = BtType.CLASSIC, name = "ELM327 v1.5")

        assertEquals("AA:BB:CC:DD:EE:01", scannedDevice.address)
    }

    @Test
    fun `address returns SIMULATOR when the device is null`() {
        val scannedDevice = ScannedDevice(device = null, type = BtType.SIMULATOR, name = "OBD Sim")

        assertEquals("SIMULATOR", scannedDevice.address)
    }
}
