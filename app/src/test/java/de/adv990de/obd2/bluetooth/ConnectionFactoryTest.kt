package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// ClassicBtConnection exposes nicht das BluetoothDevice, mit dem sie gebaut wurde — Reflection erreicht das private
// Feld, um zu bestaetigen, dass die Factory das echte Geraet durchreicht.
private fun privateField(target: Any, name: String): Any? {
    val field = target.javaClass.getDeclaredField(name)
    field.isAccessible = true
    return field.get(target)
}

class ConnectionFactoryTest {

    private val factory = ConnectionFactory()

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
    fun `create returns ClassicBtConnection wired to the given device for CLASSIC type`() {
        val btDevice: BluetoothDevice = mockk(relaxed = true)
        val device = ScannedDevice(device = btDevice, type = BtType.CLASSIC, name = "Real Classic")

        val connection = factory.create(device)

        assertTrue("Expected ClassicBtConnection", connection is ClassicBtConnection)
        assertSame(btDevice, privateField(connection, "device"))
    }

    @Test
    fun `there are exactly two connection types - BLE is gone`() {
        // Die Geraeteliste hat nie BLE-Adapter angeboten; der tote Pfad wurde in 1.24.9 entfernt. Ein neuer Typ
        // muss hier bewusst eingetragen werden.
        assertEquals(listOf("CLASSIC", "SIMULATOR"), BtType.values().map { it.name })
    }
}
