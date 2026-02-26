package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Test

class BleConnectionTest {

    private fun makeConn() = BleConnection(
        context = mockk(relaxed = true),
        device  = mockk<BluetoothDevice>(relaxed = true)
    )

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
}
