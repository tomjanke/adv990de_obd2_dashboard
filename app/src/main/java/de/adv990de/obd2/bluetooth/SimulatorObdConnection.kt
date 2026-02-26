package de.adv990de.obd2.bluetooth

import de.adv990de.obd2.obd.ElmController
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * An [ObdConnection] that simulates an ELM327 adapter with animated sensor values.
 *
 * Wiring (identical to [de.adv990de.obd2.bluetooth.FakeObdConnection]):
 *   [ElmController] writes commands → [toDispatcher] PipedOutputStream
 *     → [fromClient] PipedInputStream (read by dispatcher thread)
 *   Dispatcher thread writes responses → [toClient] PipedOutputStream
 *     → [fromElm] PipedInputStream (read by [ElmController])
 */
class SimulatorObdConnection : ObdConnection {

    @Volatile private var _isConnected = false
    override val isConnected: Boolean get() = _isConnected

    // Streams handed to ElmController
    private val toClient = PipedOutputStream()
    private val fromElm  = PipedInputStream(toClient)

    // Streams read by the dispatcher loop
    private val toDispatcher = PipedOutputStream()
    private val fromClient   = PipedInputStream(toDispatcher)

    private val dispatcher = SimulatedEcuDispatcher()

    @Volatile private var dispatchThread: Thread? = null

    override suspend fun connect(): ElmController {
        check(!_isConnected) { "SimulatorObdConnection is already connected" }
        _isConnected = true
        dispatchThread = Thread(::dispatchLoop, "obd-sim-dispatcher").also { it.isDaemon = true; it.start() }
        return ElmController(fromElm, toDispatcher)
    }

    private fun dispatchLoop() {
        try {
            // ElmController writes commands terminated with '\r'. BufferedReader.readLine()
            // treats '\r', '\n', and '\r\n' as line terminators, so '\r'-only commands
            // are read correctly as single lines.
            val reader = fromClient.bufferedReader()
            while (_isConnected) {
                val line = reader.readLine() ?: break
                val cmd = line.trimEnd('\r', '\n')
                if (cmd.isBlank()) continue
                val response = dispatcher.respond(cmd)
                val bytes = "$response\r>".toByteArray(Charsets.US_ASCII)
                toClient.write(bytes)
                toClient.flush()
            }
        } catch (_: Exception) {
            // Stream closed — exit gracefully
        }
    }

    override fun close() {
        _isConnected = false
        dispatchThread?.interrupt()
        try { toDispatcher.close() }  catch (_: Exception) {}
        try { toClient.close() }      catch (_: Exception) {}
        try { fromElm.close() }       catch (_: Exception) {}
        try { fromClient.close() }    catch (_: Exception) {}
    }
}
