@file:Suppress("DEPRECATION") // Build.VERSION.SDK_INT == 0 in plain JVM tests (see below), so
// every driven path here takes BleConnection's pre-API-33 branches, which call the deprecated
// BluetoothGatt/BluetoothGattCharacteristic/BluetoothGattDescriptor overloads.

package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import de.adv990de.obd2.obd.ElmController
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlin.test.assertFailsWith

// Mirrors the private top-level UUID constants declared in BleConnection.kt. Kotlin's top-level
// `private` is file-scoped, so those constants cannot be imported here — the literals must match.
private val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
private val WRITE_CHAR_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
private val NOTIFY_CHAR_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** A fully wired gatt/service/characteristic/descriptor tree mirroring a healthy OBD2 BLE adapter. */
private class GattFixture(
    val gatt: BluetoothGatt,
    val notifyChar: BluetoothGattCharacteristic,
    val writeChar: BluetoothGattCharacteristic,
    val cccd: BluetoothGattDescriptor,
)

private fun fullGattFixture(): GattFixture {
    val cccd = mockk<BluetoothGattDescriptor>(relaxed = true)
    val notifyChar = mockk<BluetoothGattCharacteristic>(relaxed = true) {
        every { getDescriptor(CCCD_UUID) } returns cccd
    }
    val writeChar = mockk<BluetoothGattCharacteristic>(relaxed = true)
    val service = mockk<BluetoothGattService>(relaxed = true) {
        every { getCharacteristic(NOTIFY_CHAR_UUID) } returns notifyChar
        every { getCharacteristic(WRITE_CHAR_UUID) } returns writeChar
    }
    val gatt = mockk<BluetoothGatt>(relaxed = true) {
        every { getService(SERVICE_UUID) } returns service
    }
    return GattFixture(gatt, notifyChar, writeChar, cccd)
}

/**
 * Stubs `device.connectGatt(...)` to synchronously replay [drive] against the captured callback
 * before returning [gatt]. There is no real BLE stack in a JVM test to invoke the callback later
 * on its own thread, so the whole state-machine sequence for a given test is run inline, inside
 * the mocked call itself — exactly like `connectGatt` would appear to behave from `connect()`'s
 * point of view, just without the asynchronous gap.
 */
private fun BluetoothDevice.stubConnectGatt(
    gatt: BluetoothGatt,
    callbackSlot: CapturingSlot<BluetoothGattCallback> = slot(),
    drive: (BluetoothGattCallback) -> Unit
): CapturingSlot<BluetoothGattCallback> {
    every { connectGatt(any(), any(), capture(callbackSlot)) } answers {
        drive(callbackSlot.captured)
        gatt
    }
    return callbackSlot
}

// ElmController does not expose the streams it was built with, so reflection reaches into its
// private fields — this keeps the test focused on BleConnection's own OutputStream/pipe logic
// instead of dragging ElmController's unrelated command/response protocol into the picture
// (that protocol already has its own dedicated coverage in ElmControllerTest.kt).
private fun elmField(controller: ElmController, name: String): Any {
    val field = ElmController::class.java.getDeclaredField(name)
    field.isAccessible = true
    return field.get(controller)!!
}

private fun outputStreamOf(controller: ElmController) = elmField(controller, "outputStream") as OutputStream
private fun inputStreamOf(controller: ElmController) = elmField(controller, "inputStream") as InputStream

class BleConnectionTest {

    private val context: Context = mockk(relaxed = true)
    private val device: BluetoothDevice = mockk(relaxed = true)

    private fun makeConn() = BleConnection(context, device)

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- pre-existing coverage ---

    @Test
    fun `isConnected is false before any connect call`() {
        assertFalse(makeConn().isConnected)
    }

    @Test
    fun `close on fresh instance does not throw`() {
        makeConn().close()
    }

    @Test
    fun `close is idempotent`() {
        val conn = makeConn()
        conn.close()
        conn.close() // second call must not throw
        assertFalse(conn.isConnected)
    }

    // --- guards before any GATT interaction ---

    @Test
    fun `connect throws PermissionDenied when BLUETOOTH_CONNECT is missing`() {
        mockkObject(BluetoothPermissions)
        every { BluetoothPermissions.hasConnect(any()) } returns false

        assertFailsWith<BluetoothException.PermissionDenied> {
            runBlocking { makeConn().connect() }
        }
        verify(exactly = 0) { device.connectGatt(any(), any(), any()) }
    }

    @Test
    fun `connect throws PermissionDenied when connectGatt itself throws SecurityException`() {
        every { device.connectGatt(any(), any(), any()) } throws SecurityException("revoked")

        assertFailsWith<BluetoothException.PermissionDenied> {
            runBlocking { makeConn().connect() }
        }
    }

    // --- full success path through the BluetoothGattCallback state machine ---

    @Test
    fun `connect completes and isConnected becomes true after the full callback sequence`() {
        val fx = fullGattFixture()
        device.stubConnectGatt(fx.gatt) { cb ->
            cb.onConnectionStateChange(fx.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(fx.gatt, BluetoothGatt.GATT_SUCCESS)
            cb.onDescriptorWrite(fx.gatt, fx.cccd, BluetoothGatt.GATT_SUCCESS)
        }
        val conn = makeConn()

        val controller = runBlocking { conn.connect() }

        assertNotNull(controller)
        assertTrue(conn.isConnected)
        verify { fx.gatt.setCharacteristicNotification(fx.notifyChar, true) }
        verify { fx.cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
        verify { fx.gatt.writeDescriptor(fx.cccd) }
    }

    // --- onConnectionStateChange ---

    @Test
    fun `connect throws ConnectionFailed when onConnectionStateChange reports a non-connected state`() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        device.stubConnectGatt(gatt) { cb ->
            cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_DISCONNECTED)
        }
        val conn = makeConn()

        assertFailsWith<BluetoothException.ConnectionFailed> {
            runBlocking { conn.connect() }
        }
        assertFalse(conn.isConnected)
        verify { gatt.close() } // connect()'s outer catch cleans up the raw gatt
    }

    @Test
    fun `connect throws PermissionDenied when discoverServices throws SecurityException`() {
        val gatt = mockk<BluetoothGatt>(relaxed = true) {
            every { discoverServices() } throws SecurityException("revoked")
        }
        device.stubConnectGatt(gatt) { cb ->
            cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        }

        assertFailsWith<BluetoothException.PermissionDenied> {
            runBlocking { makeConn().connect() }
        }
    }

    // --- onServicesDiscovered ---

    @Test
    fun `connect throws ConnectionFailed when service discovery status is not GATT_SUCCESS`() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        device.stubConnectGatt(gatt) { cb ->
            cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_FAILURE)
        }

        assertFailsWith<BluetoothException.ConnectionFailed> {
            runBlocking { makeConn().connect() }
        }
        verify { gatt.close() }
    }

    @Test
    fun `connect throws ConnectionFailed when the OBD2 service is not found`() {
        val gatt = mockk<BluetoothGatt>(relaxed = true) {
            every { getService(SERVICE_UUID) } returns null
        }
        device.stubConnectGatt(gatt) { cb ->
            cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        }

        val ex = assertFailsWith<BluetoothException.ConnectionFailed> {
            runBlocking { makeConn().connect() }
        }
        assertTrue(ex.message!!.contains("OBD2 service not found"))
    }

    @Test
    fun `connect throws ConnectionFailed when the notify characteristic is missing`() {
        val service = mockk<BluetoothGattService>(relaxed = true) {
            every { getCharacteristic(NOTIFY_CHAR_UUID) } returns null
        }
        val gatt = mockk<BluetoothGatt>(relaxed = true) {
            every { getService(SERVICE_UUID) } returns service
        }
        device.stubConnectGatt(gatt) { cb ->
            cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        }

        val ex = assertFailsWith<BluetoothException.ConnectionFailed> {
            runBlocking { makeConn().connect() }
        }
        assertTrue(ex.message!!.contains("Notify characteristic not found"))
    }

    @Test
    fun `connect throws ConnectionFailed when the CCCD descriptor is missing`() {
        val notifyChar = mockk<BluetoothGattCharacteristic>(relaxed = true) {
            every { getDescriptor(CCCD_UUID) } returns null
        }
        val service = mockk<BluetoothGattService>(relaxed = true) {
            every { getCharacteristic(NOTIFY_CHAR_UUID) } returns notifyChar
        }
        val gatt = mockk<BluetoothGatt>(relaxed = true) {
            every { getService(SERVICE_UUID) } returns service
        }
        device.stubConnectGatt(gatt) { cb ->
            cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        }

        val ex = assertFailsWith<BluetoothException.ConnectionFailed> {
            runBlocking { makeConn().connect() }
        }
        assertTrue(ex.message!!.contains("CCCD descriptor not found"))
    }

    @Test
    fun `connect throws PermissionDenied when setCharacteristicNotification throws SecurityException`() {
        val notifyChar = mockk<BluetoothGattCharacteristic>(relaxed = true)
        val service = mockk<BluetoothGattService>(relaxed = true) {
            every { getCharacteristic(NOTIFY_CHAR_UUID) } returns notifyChar
        }
        val gatt = mockk<BluetoothGatt>(relaxed = true) {
            every { getService(SERVICE_UUID) } returns service
            every { setCharacteristicNotification(notifyChar, true) } throws SecurityException("revoked")
        }
        device.stubConnectGatt(gatt) { cb ->
            cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        }

        assertFailsWith<BluetoothException.PermissionDenied> {
            runBlocking { makeConn().connect() }
        }
    }

    // --- onDescriptorWrite ---

    @Test
    fun `connect throws ConnectionFailed when the CCCD write status is not GATT_SUCCESS`() {
        val fx = fullGattFixture()
        device.stubConnectGatt(fx.gatt) { cb ->
            cb.onConnectionStateChange(fx.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(fx.gatt, BluetoothGatt.GATT_SUCCESS)
            cb.onDescriptorWrite(fx.gatt, fx.cccd, BluetoothGatt.GATT_FAILURE)
        }
        val conn = makeConn()

        val ex = assertFailsWith<BluetoothException.ConnectionFailed> {
            runBlocking { conn.connect() }
        }
        assertTrue(ex.message!!.contains("CCCD write failed"))
        assertFalse(conn.isConnected)
    }

    @Test
    fun `connect reports PermissionDenied instead of the real CCCD failure when close() throws during cleanup`() {
        // Documents an existing quirk rather than asserting desired behavior (not changing
        // production code per the task): onDescriptorWrite's failure branch calls g.close()
        // before completing the deferred exceptionally with ConnectionFailed("CCCD write
        // failed..."). If close() itself throws SecurityException, the SAME try/catch around
        // that branch catches it and overwrites the outcome with PermissionDenied instead —
        // silently masking the real CCCD failure reason. The same shape (close() call guarded
        // by the same catch as the "real" failure) repeats in onConnectionStateChange,
        // onServicesDiscovered and here.
        val fx = fullGattFixture()
        every { fx.gatt.close() } throws SecurityException("revoked mid-cleanup")
        device.stubConnectGatt(fx.gatt) { cb ->
            cb.onConnectionStateChange(fx.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(fx.gatt, BluetoothGatt.GATT_SUCCESS)
            cb.onDescriptorWrite(fx.gatt, fx.cccd, BluetoothGatt.GATT_FAILURE)
        }

        assertFailsWith<BluetoothException.PermissionDenied> {
            runBlocking { makeConn().connect() }
        }
    }

    // --- onCharacteristicChanged: notification plumbing into the piped input stream ---

    @Test
    fun `onCharacteristicChanged (API 33+ signature) forwards bytes into the piped input stream`() {
        val fx = fullGattFixture()
        val callbackSlot = device.stubConnectGatt(fx.gatt) { cb ->
            cb.onConnectionStateChange(fx.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(fx.gatt, BluetoothGatt.GATT_SUCCESS)
            cb.onDescriptorWrite(fx.gatt, fx.cccd, BluetoothGatt.GATT_SUCCESS)
        }
        val controller = runBlocking { makeConn().connect() }
        val input = inputStreamOf(controller)

        val payload = "41 0C 1F 40\r>".toByteArray()
        callbackSlot.captured.onCharacteristicChanged(fx.gatt, fx.notifyChar, payload)

        val buf = ByteArray(payload.size)
        assertEquals(payload.size, input.read(buf))
        assertArrayEquals(payload, buf)
    }

    @Test
    fun `deprecated onCharacteristicChanged signature forwards characteristic value bytes`() {
        val fx = fullGattFixture()
        val callbackSlot = device.stubConnectGatt(fx.gatt) { cb ->
            cb.onConnectionStateChange(fx.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(fx.gatt, BluetoothGatt.GATT_SUCCESS)
            cb.onDescriptorWrite(fx.gatt, fx.cccd, BluetoothGatt.GATT_SUCCESS)
        }
        val controller = runBlocking { makeConn().connect() }
        val input = inputStreamOf(controller)

        val payload = "OK\r>".toByteArray()
        every { fx.notifyChar.value } returns payload
        callbackSlot.captured.onCharacteristicChanged(fx.gatt, fx.notifyChar)

        val buf = ByteArray(payload.size)
        assertEquals(payload.size, input.read(buf))
        assertArrayEquals(payload, buf)
    }

    @Test
    fun `onCharacteristicChanged swallows IOException once the pipe is closed`() {
        val fx = fullGattFixture()
        val callbackSlot = device.stubConnectGatt(fx.gatt) { cb ->
            cb.onConnectionStateChange(fx.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(fx.gatt, BluetoothGatt.GATT_SUCCESS)
            cb.onDescriptorWrite(fx.gatt, fx.cccd, BluetoothGatt.GATT_SUCCESS)
        }
        val conn = makeConn()
        runBlocking { conn.connect() }
        conn.close() // closes the piped output stream underneath the callback

        // Must not throw even though the pipe is now closed.
        callbackSlot.captured.onCharacteristicChanged(fx.gatt, fx.notifyChar, byteArrayOf(1, 2, 3))
    }

    // --- write-output-stream (the OutputStream handed to ElmController) ---

    @Test
    fun `output stream write sends bytes via writeCharacteristic and returns once the ack callback fires`() {
        val fx = fullGattFixture()
        val callbackSlot = device.stubConnectGatt(fx.gatt) { cb ->
            cb.onConnectionStateChange(fx.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(fx.gatt, BluetoothGatt.GATT_SUCCESS)
            cb.onDescriptorWrite(fx.gatt, fx.cccd, BluetoothGatt.GATT_SUCCESS)
        }
        // Simulates the real adapter's async ack by firing onCharacteristicWrite synchronously
        // from within the write call itself — otherwise the semaphore below would only be
        // released after a real ~5s timeout, since nothing else drives this callback in a JVM test.
        every { fx.gatt.writeCharacteristic(fx.writeChar) } answers {
            callbackSlot.captured.onCharacteristicWrite(fx.gatt, fx.writeChar, BluetoothGatt.GATT_SUCCESS)
            true
        }
        val controller = runBlocking { makeConn().connect() }
        val outputStream = outputStreamOf(controller)
        val payload = "010C\r".toByteArray()

        val elapsedMs = measureTimeMillis { outputStream.write(payload) }

        verify { fx.writeChar.value = payload }
        verify { fx.gatt.writeCharacteristic(fx.writeChar) }
        assertTrue("write() should return as soon as the ack arrives, took ${elapsedMs}ms", elapsedMs < 1000)
    }

    @Test
    fun `output stream write also unblocks when onCharacteristicWrite reports a failure status`() {
        val fx = fullGattFixture()
        val callbackSlot = device.stubConnectGatt(fx.gatt) { cb ->
            cb.onConnectionStateChange(fx.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(fx.gatt, BluetoothGatt.GATT_SUCCESS)
            cb.onDescriptorWrite(fx.gatt, fx.cccd, BluetoothGatt.GATT_SUCCESS)
        }
        every { fx.gatt.writeCharacteristic(fx.writeChar) } answers {
            callbackSlot.captured.onCharacteristicWrite(fx.gatt, fx.writeChar, BluetoothGatt.GATT_FAILURE)
            true
        }
        val controller = runBlocking { makeConn().connect() }
        val outputStream = outputStreamOf(controller)

        val elapsedMs = measureTimeMillis { outputStream.write("010C\r".toByteArray()) }

        assertTrue("a failed write must still release the semaphore, took ${elapsedMs}ms", elapsedMs < 1000)
    }

    @Test
    fun `output stream write wraps a SecurityException as IOException`() {
        val fx = fullGattFixture()
        device.stubConnectGatt(fx.gatt) { cb ->
            cb.onConnectionStateChange(fx.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(fx.gatt, BluetoothGatt.GATT_SUCCESS)
            cb.onDescriptorWrite(fx.gatt, fx.cccd, BluetoothGatt.GATT_SUCCESS)
        }
        every { fx.gatt.writeCharacteristic(fx.writeChar) } throws SecurityException("revoked mid-session")
        val controller = runBlocking { makeConn().connect() }
        val outputStream = outputStreamOf(controller)

        val ex = assertFailsWith<IOException> { outputStream.write("010C\r".toByteArray()) }
        assertEquals("Bluetooth permission revoked", ex.message)
    }

    @Test
    fun `output stream write is a no-op once close() has cleared gatt`() {
        val fx = fullGattFixture()
        device.stubConnectGatt(fx.gatt) { cb ->
            cb.onConnectionStateChange(fx.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            cb.onServicesDiscovered(fx.gatt, BluetoothGatt.GATT_SUCCESS)
            cb.onDescriptorWrite(fx.gatt, fx.cccd, BluetoothGatt.GATT_SUCCESS)
        }
        val conn = makeConn()
        val controller = runBlocking { conn.connect() }
        val outputStream = outputStreamOf(controller)
        conn.close()

        outputStream.write("010C\r".toByteArray()) // must not throw and must not touch the GATT

        verify(exactly = 0) { fx.gatt.writeCharacteristic(any<BluetoothGattCharacteristic>()) }
    }
}
