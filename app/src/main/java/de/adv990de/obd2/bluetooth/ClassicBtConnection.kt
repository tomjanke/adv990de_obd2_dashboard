package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import de.adv990de.obd2.obd.ElmController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

class ClassicBtConnection(private val device: BluetoothDevice) : ObdConnection {

    private var socket: BluetoothSocket? = null

    override val isConnected: Boolean get() = socket?.isConnected == true

    override suspend fun connect(): ElmController = withContext(Dispatchers.IO) {
        try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            ElmController(s.inputStream, s.outputStream)
        } catch (e: Exception) {
            throw BluetoothException.ConnectionFailed(e.message ?: "unknown")
        }
    }

    override fun close() {
        socket?.close()
        socket = null
    }
}
