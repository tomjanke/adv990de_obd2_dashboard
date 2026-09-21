package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import de.adv990de.obd2.BuildConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BluetoothDeviceScannerTest {

    private val btAdapter = mockk<BluetoothAdapter>()
    private val scanner = BluetoothDeviceScanner(mockk(relaxed = true), btAdapter)

    private fun makeDevice(name: String?): BluetoothDevice = mockk {
        every { this@mockk.name } returns name
    }

    // --- Test 1 ---

    @Test
    fun `bondedDevices filters out non-OBD devices when showAll is false`() {
        val obdDevice = makeDevice("ELM327 v1.5")
        val otherDevice = makeDevice("My Headphones")
        every { btAdapter.bondedDevices } returns setOf(obdDevice, otherDevice)

        val result = scanner.bondedDevices(showAll = false)

        val classicDevices = result.filter { it.type == BtType.CLASSIC }
        assertEquals(1, classicDevices.size)
        assertEquals("ELM327 v1.5", classicDevices[0].name)
    }

    // --- Test 2 ---

    @Test
    fun `bondedDevices includes all devices when showAll is true`() {
        val obdDevice = makeDevice("ELM327 v1.5")
        val otherDevice = makeDevice("My Headphones")
        every { btAdapter.bondedDevices } returns setOf(obdDevice, otherDevice)

        val result = scanner.bondedDevices(showAll = true)

        val classicDevices = result.filter { it.type == BtType.CLASSIC }
        assertEquals(2, classicDevices.size)
    }

    // --- Test 3 ---

    @Test
    fun `bondedDevices prepends simulator entry in DEBUG build`() {
        // This test only applies to debug builds; release builds omit the simulator entry.
        assumeTrue("Requires BuildConfig.DEBUG=true (run testDebugUnitTest)", BuildConfig.DEBUG)
        every { btAdapter.bondedDevices } returns emptySet()

        val result = scanner.bondedDevices()

        assertEquals(1, result.size)
        assertEquals(BtType.SIMULATOR, result[0].type)
        assertEquals("OBD Sim", result[0].name)
    }

    // --- Test 4 ---

    @Test
    fun `bondedDevices uses Unknown for device with null name`() {
        val nullNameDevice = makeDevice(null)
        every { btAdapter.bondedDevices } returns setOf(nullNameDevice)

        val result = scanner.bondedDevices(showAll = true)

        val classicDevices = result.filter { it.type == BtType.CLASSIC }
        assertEquals(1, classicDevices.size)
        assertEquals("Unknown", classicDevices[0].name)
    }

    // --- Test 5 ---

    @Test
    fun `isObdLike matches known keywords`() {
        val viecarDevice = makeDevice("VIECAR BT")
        val obdlinkDevice = makeDevice("OBDLINK MX+")
        every { btAdapter.bondedDevices } returns setOf(viecarDevice, obdlinkDevice)

        val result = scanner.bondedDevices(showAll = false)

        val classicDevices = result.filter { it.type == BtType.CLASSIC }
        assertEquals("Expected exactly 2 CLASSIC devices", 2, classicDevices.size)
        val classicNames = classicDevices.map { it.name }
        assertTrue("VIECAR BT should be included", "VIECAR BT" in classicNames)
        assertTrue("OBDLINK MX+ should be included", "OBDLINK MX+" in classicNames)
    }

    // --- Test 6 ---

    @Test
    fun `bondedDevices skips a device whose name access is denied`() {
        // BLUETOOTH_CONNECT entzogen: der Namenszugriff wirft, statt null zu liefern.
        val denied = mockk<BluetoothDevice> { every { name } throws SecurityException("permission denied") }
        val allowed = makeDevice("ELM327 v1.5")
        every { btAdapter.bondedDevices } returns setOf(denied, allowed)

        val result = scanner.bondedDevices(showAll = false)

        val classicDevices = result.filter { it.type == BtType.CLASSIC }
        assertEquals(1, classicDevices.size)
        assertEquals("ELM327 v1.5", classicDevices[0].name)
    }

    // --- Test 7 ---

    @Test
    fun `bondedDevices falls back to Unknown when the name is denied and showAll is true`() {
        val denied = mockk<BluetoothDevice> { every { name } throws SecurityException("permission denied") }
        every { btAdapter.bondedDevices } returns setOf(denied)

        val result = scanner.bondedDevices(showAll = true)

        val classicDevices = result.filter { it.type == BtType.CLASSIC }
        assertEquals(1, classicDevices.size)
        assertEquals("Unknown", classicDevices[0].name)
    }
}
