package de.adv990de.obd2.bluetooth

import de.adv990de.obd2.obd.ElmController
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Stub implementation — wires [SimulatedEcuDispatcher] to [ElmController] via piped streams.
 * Full implementation provided in Task 4.
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
        _isConnected = true
        dispatchThread = Thread(::dispatchLoop, "obd-sim-dispatcher").also { it.isDaemon = true; it.start() }
        return ElmController(fromElm, toDispatcher)
    }

    private fun dispatchLoop() {
        val sb = StringBuilder()
        try {
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
        runCatching { toDispatcher.close() }
        runCatching { toClient.close() }
        dispatchThread?.interrupt()
        dispatchThread = null
    }
}
