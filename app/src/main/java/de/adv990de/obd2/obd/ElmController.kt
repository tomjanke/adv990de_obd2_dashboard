package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.ObdException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val READ_TIMEOUT_MS = 5_000L

class ElmController(
    private val inputStream: InputStream,
    private val outputStream: OutputStream
) {
    // Serialises all command/response exchanges; prevents concurrent callers from
    // interleaving writes and reads on the same stream pair.
    private val commandMutex = Mutex()

    // Buffer for batch reading
    private val readBuffer = ByteArray(64)
    private var bufferPos = 0
    private var bufferEnd = 0

    /**
     * Initializes the ELM327 adapter with standard AT commands.
     * Must be called once after BT connection is established, before any PID queries.
     *
     * Each AT command acquires [commandMutex] individually. In principle a concurrent
     * caller could interleave commands between init steps. In practice this cannot
     * happen: [initialize] is called inside [de.adv990de.obd2.data.ObdRepository.connect],
     * which holds [de.adv990de.obd2.data.ObdRepository.connectMutex], and callers only
     * obtain a reference to [ElmController] after [connect] returns — i.e., after
     * initialization is complete.
     */
    suspend fun initialize() {
        // ATZ = reset, ATE0 = echo off, ATL0 = linefeed off,
        // ATS0 = spaces off, ATSP0 = auto-detect protocol
        val initSequence = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")
        for (cmd in initSequence) {
            val response = sendCommand(cmd)
            // "STOPPED" is returned by some clone adapters after ATZ when the hardware reset
            // gets stuck; treat it as an init failure like "ERROR" and "?".
            if (response.contains("ERROR", ignoreCase = true)
                    || response.contains("STOPPED", ignoreCase = true)
                    || response == "?") {
                throw ObdException.InitializationFailed("$cmd returned: $response")
            }
        }
    }

    /**
     * Sends a command and reads the response up to the '>' prompt.
     * Returns all non-empty lines joined by a single space. Single-line responses are
     * unaffected; multi-frame responses (e.g. VIN mode 09) are returned as a complete string
     * rather than only the last frame.
     */
    suspend fun sendCommand(command: String): String = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                withTimeout(READ_TIMEOUT_MS) {
                    runInterruptible {
                        outputStream.write("$command\r".toByteArray())
                        outputStream.flush()
                        readResponse()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw ObdException.Timeout(command)
            }
        }
    }

    private fun readResponse(): String {
        val sb = StringBuilder()
        var gotAny = false
        try {
            while (true) {
                // Refill buffer if needed
                if (bufferPos >= bufferEnd) {
                    bufferEnd = inputStream.read(readBuffer)
                    bufferPos = 0
                    if (bufferEnd == -1) break
                }
                gotAny = true
                // Process one byte from the buffer
                val b = readBuffer[bufferPos++].toInt().toChar()
                if (b == '>') {
                    // Found the prompt; return everything collected so far
                    return buildResult(sb)
                }
                sb.append(b)
            }
        } catch (e: IOException) {
            throw ObdException.ConnectionLost()
        }
        if (!gotAny) throw ObdException.ConnectionLost()
        return buildResult(sb)
    }

    private fun buildResult(sb: StringBuilder): String =
        sb.toString()
            .split("\r", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
}
