package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.ObdException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Wie lange ein Befehl auf seine Antwort wartet, bevor er als [ObdException.Timeout] gilt. */
internal const val DEFAULT_READ_TIMEOUT_MS = 5_000L

class ElmController(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    // Parameter statt privater Konstante (B38): So laesst sich die Zeitueberschreitung mit 100 ms testen statt gar
    // nicht. Die Produktion (ClassicBtConnection, Simulator) nutzt den Standard.
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
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
        // ATS1 = spaces ON, ATSP0 = auto-detect protocol
        //
        // ATS1, nicht ATS0 (B9): Alle Parser (PIDs, Discovery, DTCs, Freeze-Frame) splitten an Leerzeichen. Mit ATS0
        // liefert ein Adapter, der den Befehl befolgt, "410C1AF8" — kein Parser haette es gelesen (Kommentare an anderer
        // Stelle behaupteten das Gegenteil). ATZ stellt ohnehin die Voreinstellung "Leerzeichen an" her; das explizite
        // ATS1 haelt die Absicht fest und schuetzt vor Klonen, die Einstellungen ueber ATZ hinweg behalten.
        val initSequence = listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATSP0")
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
                withTimeout(readTimeoutMs) {
                    // Der Job des Zeitlimits: Ist er abgebrochen (Zeitlimit oder Abbruch von aussen), stammt ein
                    // IO-Fehler aus dem blockierenden Lesen von unserem EIGENEN Interrupt, nicht von einer
                    // verlorenen Verbindung (s. readResponse).
                    val scopeJob = coroutineContext.job
                    runInterruptible {
                        // Ein Schreibfehler heisst dasselbe wie ein Lesefehler: Die Verbindung ist weg (der
                        // Adapter oder das Funkmodul hat sie waehrend der Pause des Pollers geschlossen,
                        // naechster Schreibzugriff: "Broken pipe"). Als rohe IOException entkaeme das der
                        // ConnectionLost-Kette — kein Reconnect, die Anzeige bliebe "verbunden".
                        try {
                            outputStream.write("$command\r".toByteArray())
                            outputStream.flush()
                        } catch (e: IOException) {
                            throw ObdException.ConnectionLost()
                        }
                        readResponse(cancelled = { scopeJob.isCancelled })
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw ObdException.Timeout(command)
            }
        }
    }

    /**
     * @param cancelled ob die aufrufende Coroutine inzwischen abgebrochen ist (Zeitlimit oder Abbruch von aussen).
     */
    private fun readResponse(cancelled: () -> Boolean): String {
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
            // runInterruptible unterbricht den Thread, sobald die Coroutine abgebrochen wird. Ein unterbrechbares
            // Lesen (BLE-Pipe, Simulator) wirft dann eine InterruptedIOException — als ConnectionLost gemeldet, ueberstimmte
            // sie bis 1.24.5 das Zeitlimit (Kotlin stuft eine echte Ausnahme ueber die Abbruchursache): Ein stiller Adapter
            // loeste sofort einen Reconnect aus statt des toleranten Timeout-Wegs mit Watchdog. Nach dem Abbruch ist der
            // IO-Fehler also unser eigener Interrupt: als InterruptedException weiterreichen, die runInterruptible in
            // eine saubere Abbruch-Ausnahme verwandelt (-> Timeout bzw. CancellationException).
            if (cancelled()) throw InterruptedException("Lesen durch Abbruch unterbrochen")
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
