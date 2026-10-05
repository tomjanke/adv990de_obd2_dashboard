package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import kotlin.test.assertFailsWith

class ClassicBtConnectionTest {

    @Test
    fun `isConnected is false after close`() {
        val socket = mockk<BluetoothSocket>(relaxed = true) {
            every { isConnected } returns true
            every { inputStream } returns mockk<InputStream>(relaxed = true)
            every { outputStream } returns mockk<OutputStream>(relaxed = true)
        }
        val device = mockk<BluetoothDevice> {
            every { createRfcommSocketToServiceRecord(any()) } returns socket
        }
        val conn = ClassicBtConnection(device)
        runBlocking { conn.connect() }
        assertTrue(conn.isConnected)
        conn.close()
        assertFalse(conn.isConnected)
    }

    @Test
    fun `connect throws ConnectionFailed when socket creation throws IOException`() {
        val device = mockk<BluetoothDevice> {
            every { createRfcommSocketToServiceRecord(any()) } throws IOException("refused")
        }
        val conn = ClassicBtConnection(device)
        assertFailsWith<BluetoothException.ConnectionFailed> {
            runBlocking { conn.connect() }
        }
    }

    @Test
    fun `connect throws ConnectionFailed when socket connect throws IOException`() {
        val socket = mockk<BluetoothSocket>(relaxed = true) {
            every { connect() } throws IOException("host unreachable")
        }
        val device = mockk<BluetoothDevice> {
            every { createRfcommSocketToServiceRecord(any()) } returns socket
        }
        val conn = ClassicBtConnection(device)
        assertFailsWith<BluetoothException.ConnectionFailed> {
            runBlocking { conn.connect() }
        }
    }

    @Test
    fun `connect rethrows CancellationException without wrapping`() {
        val socket = mockk<BluetoothSocket>(relaxed = true) {
            every { connect() } throws CancellationException("cancelled")
        }
        val device = mockk<BluetoothDevice> {
            every { createRfcommSocketToServiceRecord(any()) } returns socket
        }
        val conn = ClassicBtConnection(device)
        assertFailsWith<CancellationException> {
            runBlocking { conn.connect() }
        }
    }

    @Test
    fun `close is idempotent`() {
        val conn = ClassicBtConnection(mockk(relaxed = true))
        conn.close() // socket is null — no-op
        conn.close() // second call must not throw
    }

    // ── B7: nothing leaks, and close() can abort a connect() that blocks ─────

    @Test
    fun `a failed connect closes the socket it created - no leaked descriptor`() {
        val socket = mockk<BluetoothSocket>(relaxed = true) {
            every { connect() } throws IOException("host unreachable")
        }
        val device = mockk<BluetoothDevice> { every { createRfcommSocketToServiceRecord(any()) } returns socket }
        val conn = ClassicBtConnection(device)

        assertFailsWith<BluetoothException.ConnectionFailed> { runBlocking { conn.connect() } }

        verify(exactly = 1) { socket.close() }
        assertFalse(conn.isConnected)
    }

    @Test
    fun `a cancelled connect closes its socket as well`() {
        val socket = mockk<BluetoothSocket>(relaxed = true) {
            every { connect() } throws CancellationException("cancelled")
        }
        val device = mockk<BluetoothDevice> { every { createRfcommSocketToServiceRecord(any()) } returns socket }

        assertFailsWith<CancellationException> { runBlocking { ClassicBtConnection(device).connect() } }

        verify(exactly = 1) { socket.close() }
    }

    @Test
    fun `close during a blocking connect aborts it - the socket is known before connect starts`() {
        // BluetoothSocket.connect() blocks the thread (roughly 12 s on a bad link); only close() from another thread
        // frees it. Until 1.24.5 the socket was assigned AFTER connect(), so close() found nothing to close.
        val release = CountDownLatch(1)
        val socket = mockk<BluetoothSocket>(relaxed = true) {
            every { connect() } answers { release.await(); throw IOException("socket closed") }
            every { close() } answers { release.countDown() }
        }
        val device = mockk<BluetoothDevice> { every { createRfcommSocketToServiceRecord(any()) } returns socket }
        val conn = ClassicBtConnection(device)
        runBlocking {
            val connecting = async(Dispatchers.IO) { runCatching { conn.connect() } }
            try {
                delay(200)                       // real time: connect() is blocked by now

                conn.close()
                val result = withTimeout(3_000) { connecting.await() }

                assertTrue("der Aufbau endet mit Verbindungsfehler: $result", result.exceptionOrNull() is BluetoothException.ConnectionFailed)
            } finally {
                // Auch wenn der Schutz fehlt und der Test rot wird: Der blockierte Thread wird freigegeben, damit runBlocking
                // (es wartet auf sein Kind) nicht ewig haengt.
                release.countDown()
            }
        }
    }

    @Test
    fun `close swallows an IOException from the socket`() {
        val socket = mockk<BluetoothSocket>(relaxed = true) {
            every { isConnected } returns true
            every { inputStream } returns mockk<InputStream>(relaxed = true)
            every { outputStream } returns mockk<OutputStream>(relaxed = true)
            every { close() } throws IOException("already closed")
        }
        val device = mockk<BluetoothDevice> { every { createRfcommSocketToServiceRecord(any()) } returns socket }
        val conn = ClassicBtConnection(device)
        runBlocking { conn.connect() }

        conn.close()   // darf nicht werfen: close() laeuft auch im Aufraeumpfad einer Ausnahme

        assertFalse(conn.isConnected)
    }

    @Test
    fun `close after a successful connect still closes the socket exactly once`() {
        val socket = mockk<BluetoothSocket>(relaxed = true) {
            every { isConnected } returns true
            every { inputStream } returns mockk<InputStream>(relaxed = true)
            every { outputStream } returns mockk<OutputStream>(relaxed = true)
        }
        val device = mockk<BluetoothDevice> { every { createRfcommSocketToServiceRecord(any()) } returns socket }
        val conn = ClassicBtConnection(device)
        runBlocking { conn.connect() }

        conn.close()
        conn.close()

        verify(exactly = 1) { socket.close() }
        assertEquals(false, conn.isConnected)
    }
}
