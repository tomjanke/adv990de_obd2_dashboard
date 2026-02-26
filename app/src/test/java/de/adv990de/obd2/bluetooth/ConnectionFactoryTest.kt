package de.adv990de.obd2.bluetooth

import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFactoryTest {

    private val factory = ConnectionFactory(mockk(relaxed = true))

    @Test
    fun `create returns SimulatorObdConnection for SIMULATOR type`() {
        val device = ScannedDevice(device = null, type = BtType.SIMULATOR, name = "OBD Sim")
        val connection = factory.create(device)
        assertTrue("Expected SimulatorObdConnection", connection is SimulatorObdConnection)
    }

    @Test
    fun `create throws for CLASSIC device with null BluetoothDevice`() {
        val device = ScannedDevice(device = null, type = BtType.CLASSIC, name = "Bad")
        val ex = runCatching { factory.create(device) }.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException", ex is IllegalArgumentException)
        assertTrue(ex!!.message?.contains("CLASSIC") == true)
    }

    @Test
    fun `create throws for BLE device with null BluetoothDevice`() {
        val device = ScannedDevice(device = null, type = BtType.BLE, name = "Bad")
        val ex = runCatching { factory.create(device) }.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException", ex is IllegalArgumentException)
        assertTrue(ex!!.message?.contains("BLE") == true)
    }
}
