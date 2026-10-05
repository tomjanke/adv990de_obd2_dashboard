package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import de.adv990de.obd2.obd.ElmController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

class ClassicBtConnection(private val device: BluetoothDevice) : ObdConnection {

    @Volatile private var socket: BluetoothSocket? = null

    override val isConnected: Boolean get() = socket?.isConnected == true

    override suspend fun connect(): ElmController = withContext(Dispatchers.IO) {
        var created: BluetoothSocket? = null
        try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            created = s
            // Der Socket ist ab hier bekannt — VOR connect(): BluetoothSocket.connect() blockiert den Thread (auf einer
            // schlechten Strecke rund 12 s) und laesst sich nur durch close() von aussen abbrechen (Zeitlimit des
            // Verbindungsaufbaus, Abbruch des Aufrufers, "Trennen"). Bis 1.24.5 wurde er erst NACH connect()
            // zugewiesen: close() fand dann nichts, und bei einem Fehlschlag blieb der Socket offen (Deskriptor-Leck
            // bis zur Garbage Collection, bis zu fuenf je Ausfall der Wiederverbindungs-Schleife).
            socket = s
            // BluetoothSocket has no soTimeout API; read timeout is enforced by
            // withTimeout + runInterruptible in ElmController.sendCommand instead.
            s.connect()
            ElmController(s.inputStream, s.outputStream)
        } catch (e: Exception) {
            closeQuietly(created)
            if (socket === created) socket = null
            if (e is CancellationException) throw e
            throw BluetoothException.ConnectionFailed(e.message ?: "unknown")
        }
    }

    /** Schliesst den Socket und vergisst ihn. Wirft nie — auch nicht, wenn der Socket schon zu ist (Aufraeumpfade). */
    override fun close() {
        val s = socket
        socket = null
        closeQuietly(s)
    }

    private fun closeQuietly(s: BluetoothSocket?) {
        try {
            s?.close()
        } catch (_: IOException) {
            // schon geschlossen oder Funkmodul weg: nichts mehr zu tun
        }
    }
}
