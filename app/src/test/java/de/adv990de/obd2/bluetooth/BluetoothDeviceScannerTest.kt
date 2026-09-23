package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import de.adv990de.obd2.BuildConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class BluetoothDeviceScannerTest {

    private val btAdapter = mockk<BluetoothAdapter>()
    private val scanner = BluetoothDeviceScanner(mockk(relaxed = true), btAdapter)

    private fun makeDevice(name: String?): BluetoothDevice = mockk {
        every { this@mockk.name } returns name
    }

    private fun makeBleDevice(name: String?, address: String): BluetoothDevice = mockk {
        every { this@mockk.name } returns name
        every { this@mockk.address } returns address
    }

    private fun scanResultFor(device: BluetoothDevice): ScanResult = mockk {
        every { this@mockk.device } returns device
    }

    /** Captures the [ScanCallback] passed to `startScan` and replays [drive] against the real
     *  (non-mocked) callback instance synchronously, from within the mocked call itself — mirrors
     *  BleConnectionTest's connectGatt-style driving, since there is no real BLE stack in a JVM
     *  test to invoke callbacks later on its own thread. */
    private fun BluetoothLeScanner.stubStartScan(drive: (ScanCallback) -> Unit) {
        val callbackSlot = slot<ScanCallback>()
        every { startScan(capture(callbackSlot)) } answers { drive(callbackSlot.captured) }
    }

    @After
    fun tearDown() {
        unmockkAll()
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

    // --- scanBle(): success path via the real ScanCallback ---

    @Test
    fun `scanBle emits BLE devices discovered by the ScanCallback`() {
        val bleScanner = mockk<BluetoothLeScanner>(relaxed = true)
        every { btAdapter.bluetoothLeScanner } returns bleScanner
        val device = makeBleDevice("ELM327 BLE", "AA:BB:CC:DD:EE:01")
        bleScanner.stubStartScan { callback ->
            callback.onScanResult(0, scanResultFor(device))
        }

        val found = runBlocking { scanner.scanBle().take(1).toList() }

        assertEquals(1, found.size)
        assertEquals(BtType.BLE, found[0].type)
        assertEquals("ELM327 BLE", found[0].name)
        assertEquals("AA:BB:CC:DD:EE:01", found[0].address)
    }

    // --- scanBle(): dedup-by-address `seen` set ---

    @Test
    fun `scanBle deduplicates repeated ScanCallback reports of the same device address`() {
        val bleScanner = mockk<BluetoothLeScanner>(relaxed = true)
        every { btAdapter.bluetoothLeScanner } returns bleScanner
        val first = makeBleDevice("ELM327 BLE", "AA:BB:CC:DD:EE:01")
        val second = makeBleDevice("OBDLINK MX+", "AA:BB:CC:DD:EE:02")
        bleScanner.stubStartScan { callback ->
            callback.onScanResult(0, scanResultFor(first))
            callback.onScanResult(0, scanResultFor(first)) // same address again
            callback.onScanResult(0, scanResultFor(second))
        }

        // Only 2 distinct addresses are ever sent by the `seen` set, regardless of the 3rd (dup)
        // onScanResult call above — take(2) simply drains exactly what was produced.
        val found = runBlocking { scanner.scanBle().take(2).toList() }

        assertEquals(2, found.size)
        assertEquals(
            setOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            found.map { it.address }.toSet()
        )
    }

    // --- scanBle(): permission-denied close() ---

    @Test
    fun `scanBle closes with PermissionDenied when BLUETOOTH_SCAN is missing`() {
        mockkObject(BluetoothPermissions)
        every { BluetoothPermissions.hasScan(any()) } returns false

        assertFailsWith<BluetoothException.PermissionDenied> {
            runBlocking { scanner.scanBle().toList() }
        }
    }

    // --- scanBle(): null-scanner close() ---

    @Test
    fun `scanBle closes with ConnectionFailed when the adapter has no BLE scanner`() {
        every { btAdapter.bluetoothLeScanner } returns null

        val ex = assertFailsWith<BluetoothException.ConnectionFailed> {
            runBlocking { scanner.scanBle().toList() }
        }
        assertTrue(ex.message!!.contains("BLE"))
    }

    // --- scanBle(): SecurityException-on-startScan path ---

    @Test
    fun `scanBle closes with PermissionDenied when startScan throws SecurityException`() {
        val bleScanner = mockk<BluetoothLeScanner>(relaxed = true)
        every { btAdapter.bluetoothLeScanner } returns bleScanner
        every { bleScanner.startScan(any<ScanCallback>()) } throws SecurityException("revoked")

        assertFailsWith<BluetoothException.PermissionDenied> {
            runBlocking { scanner.scanBle().toList() }
        }
        // startScan threw before awaitClose registers its cleanup, so stopScan must never fire.
        verify(exactly = 0) { bleScanner.stopScan(any<ScanCallback>()) }
    }
}
