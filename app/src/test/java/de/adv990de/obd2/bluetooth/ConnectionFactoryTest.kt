package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// ClassicBtConnection/BleConnection don't expose the BluetoothDevice/Context they were built
// with, so reflection reaches into their private fields to confirm the factory actually wires
// the real device (and, for BLE, the real context) through — mirrors the `elmField` helper in
// BleConnectionTest.kt.
private fun privateField(target: Any, name: String): Any? {
    val field = target.javaClass.getDeclaredField(name)
    field.isAccessible = true
    return field.get(target)
}

class ConnectionFactoryTest {

    private val context: Context = mockk(relaxed = true)
    private val factory = ConnectionFactory(context)

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

    @Test
    fun `create returns ClassicBtConnection wired to the given device for CLASSIC type`() {
        val btDevice: BluetoothDevice = mockk(relaxed = true)
        val device = ScannedDevice(device = btDevice, type = BtType.CLASSIC, name = "Real Classic")

        val connection = factory.create(device)

        assertTrue("Expected ClassicBtConnection", connection is ClassicBtConnection)
        assertSame(btDevice, privateField(connection, "device"))
    }

    @Test
    fun `create returns BleConnection wired to the given context and device for BLE type`() {
        val btDevice: BluetoothDevice = mockk(relaxed = true)
        val device = ScannedDevice(device = btDevice, type = BtType.BLE, name = "Real BLE")

        val connection = factory.create(device)

        assertTrue("Expected BleConnection", connection is BleConnection)
        assertSame(btDevice, privateField(connection, "device"))
        assertSame(context, privateField(connection, "context"))
    }
}
