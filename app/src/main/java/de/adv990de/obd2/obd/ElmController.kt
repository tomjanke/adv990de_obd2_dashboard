package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.ObdException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
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

    /**
     * Initializes the ELM327 adapter with standard AT commands.
     * Must be called once after BT connection is established.
     */
    suspend fun initialize() {
        // ATZ = reset, ATE0 = echo off, ATL0 = linefeed off,
        // ATS0 = spaces off, ATSP0 = auto-detect protocol
        val initSequence = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")
        for (cmd in initSequence) {
            val response = sendCommand(cmd)
            if (response.contains("ERROR", ignoreCase = true) && cmd == "ATZ") {
                throw ObdException.InitializationFailed("ATZ returned: $response")
            }
        }
    }

    /**
     * Sends a command and reads the response up to the '>' prompt.
     * Returns the last non-empty line of the response.
     */
    suspend fun sendCommand(command: String): String = withContext(Dispatchers.IO) {
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

    private fun readResponse(): String {
        val sb = StringBuilder()
        var ch: Int
        var gotAny = false
        try {
            while (inputStream.read().also { ch = it } != -1) {
                gotAny = true
                if (ch == '>'.code) break
                sb.append(ch.toChar())
            }
        } catch (e: IOException) {
            throw ObdException.ConnectionLost()
        }
        if (!gotAny) throw ObdException.ConnectionLost()
        return sb.toString()
            .split("\r", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .lastOrNull() ?: ""
    }
}
