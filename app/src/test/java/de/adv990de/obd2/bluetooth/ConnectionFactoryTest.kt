package de.adv990de.obd2.bluetooth

import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectionFactoryTest {

    private val factory = ConnectionFactory(mockk(relaxed = true))

    @Test
    fun `create returns SimulatorObdConnection for SIMULATOR type`() {
        val device = ScannedDevice(device = null, type = BtType.SIMULATOR, name = "OBD Sim")
        val connection = factory.create(device)
        assertIs<SimulatorObdConnection>(connection)
    }

    @Test
    fun `create throws for CLASSIC device with null BluetoothDevice`() {
        val device = ScannedDevice(device = null, type = BtType.CLASSIC, name = "Bad")
        val ex = assertFailsWith<IllegalArgumentException> { factory.create(device) }
        assertTrue(ex.message?.contains("CLASSIC") == true)
    }

    @Test
    fun `create throws for BLE device with null BluetoothDevice`() {
        val device = ScannedDevice(device = null, type = BtType.BLE, name = "Bad")
        val ex = assertFailsWith<IllegalArgumentException> { factory.create(device) }
        assertTrue(ex.message?.contains("BLE") == true)
    }
}
