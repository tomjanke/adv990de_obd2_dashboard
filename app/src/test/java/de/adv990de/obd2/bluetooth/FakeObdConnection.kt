package de.adv990de.obd2.bluetooth

import de.adv990de.obd2.obd.ElmController
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * An in-process [ObdConnection] backed by piped streams.
 *
 * Queue responses before connecting; each queued response will be returned for one incoming
 * command. Falls back to "OK" if the queue is empty.
 *
 * Wiring:
 *   [ElmController] writes commands → [fromController] PipedOutputStream
 *     → [fromControllerSource] PipedInputStream (read by dispatcher thread)
 *   Dispatcher thread writes responses → [toControllerSink] PipedOutputStream
 *     → [toControllerSource] PipedInputStream (read by [ElmController])
 */
class FakeObdConnection : ObdConnection {

    // ElmController reads responses from this stream
    private val toControllerSource = PipedInputStream()
    private val toControllerSink   = PipedOutputStream(toControllerSource)

    // ElmController writes commands into this stream
    private val fromController       = PipedOutputStream()
    private val fromControllerSource = PipedInputStream(fromController)

    private val pendingResponses = ArrayDeque<String>()
    private var _isConnected = false

    override val isConnected: Boolean get() = _isConnected

    override suspend fun connect(): ElmController {
        _isConnected = true
        val controller = ElmController(toControllerSource, fromController)

        Thread {
            val buf = ByteArray(512)
            try {
                while (true) {
                    val n = fromControllerSource.read(buf)
                    if (n < 0) break
                    val received = String(buf, 0, n)
                    // Commands are terminated with '\r'; respond once per command terminator.
                    // A single read() may contain multiple bytes of one command (or in rare
                    // cases multiple commands) — we respond once for each '\r' found.
                    var count = received.count { it == '\r' }
                    while (count-- > 0) {
                        val response = synchronized(pendingResponses) {
                            pendingResponses.removeFirstOrNull() ?: "OK"
                        }
                        toControllerSink.write("$response\r>".toByteArray())
                        toControllerSink.flush()
                    }
                }
            } catch (_: Exception) {
                // Pipe was closed — normal shutdown path
            }
        }.also { it.isDaemon = true }.start()

        return controller
    }

    override fun close() {
        _isConnected = false
        try { fromController.close() } catch (_: Exception) {}
        try { toControllerSink.close() } catch (_: Exception) {}
    }

    /**
     * Enqueues a single response string. The dispatcher returns it (plus `\r>` terminator)
     * for the next incoming command.
     */
    fun queueResponse(response: String) {
        synchronized(pendingResponses) { pendingResponses.addLast(response) }
    }

    /** Convenience overload to queue multiple responses at once. */
    fun queueResponses(vararg responses: String) = responses.forEach { queueResponse(it) }
}
