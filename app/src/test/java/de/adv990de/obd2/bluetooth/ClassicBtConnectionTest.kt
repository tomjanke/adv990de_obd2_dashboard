package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
}
