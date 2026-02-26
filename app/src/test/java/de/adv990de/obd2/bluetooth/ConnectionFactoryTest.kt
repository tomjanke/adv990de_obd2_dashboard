package de.adv990de.obd2.bluetooth

import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertIs

class ConnectionFactoryTest {

    private val factory = ConnectionFactory(mockk(relaxed = true))

    @Test
    fun `create returns SimulatorObdConnection for SIMULATOR type`() {
        val device = ScannedDevice(device = null, type = BtType.SIMULATOR, name = "OBD Sim")
        val connection = factory.create(device)
        assertIs<SimulatorObdConnection>(connection)
    }
}
