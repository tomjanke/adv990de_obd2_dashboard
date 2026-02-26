package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.IOException

class ClassicBtConnectionTest {

    @Test
    fun `isConnected is false when not yet connected`() {
        val conn = ClassicBtConnection(mockk(relaxed = true))
        assertFalse(conn.isConnected)
    }

    @Test
    fun `connect throws ConnectionFailed when socket creation throws IOException`() {
        val device = mockk<BluetoothDevice> {
            every { createRfcommSocketToServiceRecord(any()) } throws IOException("refused")
        }
        val conn = ClassicBtConnection(device)
        var caught: BluetoothException.ConnectionFailed? = null
        runBlocking {
            try { conn.connect() } catch (e: BluetoothException.ConnectionFailed) { caught = e }
        }
        assertNotNull("Expected ConnectionFailed", caught)
    }

    @Test
    fun `close is idempotent`() {
        val conn = ClassicBtConnection(mockk(relaxed = true))
        conn.close() // socket is null — no-op
        conn.close() // second call must not throw
    }
}
