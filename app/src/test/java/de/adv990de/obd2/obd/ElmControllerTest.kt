package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.ObdException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class ElmControllerTest {

    /**
     * Ein Adapter, der nur ANTWORTET: Eine Antwort wird erst freigegeben, wenn ein Befehl geschrieben (geflusht) wurde — wie beim echten
     * ELM327. Frueher stand der ganze Antwort-Text schon vor dem ersten Befehl im Eingang; seit der Controller Altbytes vor jedem Befehl
     * verwirft (PRO-13), wuerde das die Antworten mit wegwerfen. [stale] steht von Anfang an im Eingang: die verspaetete Antwort auf
     * einen frueheren Befehl.
     */
    private class ScriptedAdapter(responses: List<ByteArray>, stale: ByteArray = ByteArray(0)) {
        private val lock = Any()
        private val queue = ArrayDeque(responses)
        private var buffer = stale
        private var position = 0

        val input: InputStream = object : InputStream() {
            override fun read(): Int = synchronized(lock) {
                if (position < buffer.size) buffer[position++].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
                if (position >= buffer.size) return@synchronized -1
                val n = minOf(len, buffer.size - position)
                System.arraycopy(buffer, position, b, off, n)
                position += n
                n
            }

            override fun available(): Int = synchronized(lock) { buffer.size - position }
        }

        val output: ByteArrayOutputStream = object : ByteArrayOutputStream() {
            override fun flush() {
                super.flush()
                release()
            }
        }

        private fun release() = synchronized(lock) {
            val next = queue.removeFirstOrNull() ?: return@synchronized
            buffer = buffer.copyOfRange(position, buffer.size) + next
            position = 0
        }
    }

    private fun makeStreams(responses: List<String>): Pair<InputStream, ByteArrayOutputStream> {
        // Simulate ELM327: each response ends with ">" prompt
        val adapter = ScriptedAdapter(responses.map { "$it\r>".toByteArray() })
        return adapter.input to adapter.output
    }

    @Test
    fun `sendCommand returns response line`() = runTest {
        val (input, output) = makeStreams(listOf("41 0C 1F 40"))
        val controller = ElmController(input, output)
        val response = controller.sendCommand("010C")
        assertEquals("41 0C 1F 40", response)
        // Verify the command was actually written to the stream with the required CR terminator.
        // ELM327 ignores commands without \r — a missing terminator would cause a read timeout.
        assertEquals("010C\r", output.toString())
    }

    @Test
    fun `initialize sends AT commands in sequence`() = runTest {
        val responses = listOf("OK", "OK", "OK", "OK", "OK") // ATZ, ATE0, ATL0, ATS1, ATSP0
        val (input, output) = makeStreams(responses)
        val controller = ElmController(input, output)
        controller.initialize()
        val sent = output.toString()
        // Der ganze String, in dieser Reihenfolge: ATZ (Reset) muss ZUERST kommen, sonst setzt er die uebrigen Einstellungen wieder zurueck;
        // ATSP0 (Protokollsuche) zuletzt. Ein "contains" je Befehl liesse eine Umstellung oder einen doppelten Befehl durch (TST-14).
        // B9: ATS1 = Leerzeichen AN. Alle Parser (PIDs, Discovery, DTCs, Freeze-Frame) splitten an Leerzeichen; mit ATS0 lieferte
        // ein Adapter, der den Befehl befolgt, "410C1AF8" — und kein Parser haette es gelesen.
        assertEquals("ATZ\rATE0\rATL0\rATS1\rATSP0\r", sent)
    }

    @Test(expected = ObdException.InitializationFailed::class)
    fun `initialize throws when ATZ fails`() = runTest {
        val (input, output) = makeStreams(listOf("ERROR"))
        val controller = ElmController(input, output)
        controller.initialize()
    }

    @Test
    fun `sendCommand joins all lines for multi-frame response`() = runTest {
        // Simulate a two-frame VIN response separated by \r inside the same prompt block.
        // makeStreams appends "\r>" after the response, so the stream is:
        //   "49 02 01 57 41\r49 02 02 5A 5A 5A\r>"
        val (input, output) = makeStreams(listOf("49 02 01 57 41\r49 02 02 5A 5A 5A"))
        val controller = ElmController(input, output)
        val response = controller.sendCommand("0902")
        assertEquals("49 02 01 57 41 49 02 02 5A 5A 5A", response)
    }

    @Test(expected = ObdException.InitializationFailed::class)
    fun `initialize throws when ATZ returns STOPPED`() = runTest {
        val (input, output) = makeStreams(listOf("STOPPED"))
        val controller = ElmController(input, output)
        controller.initialize()
    }

    // --- ConnectionLost / Timeout edge-case tests ---

    @Test(expected = ObdException.ConnectionLost::class)
    fun `sendCommand throws ConnectionLost when IOException occurs`() = runTest {
        val failingInput = object : InputStream() {
            override fun read(): Int = throw IOException("pipe broken")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("pipe broken")
        }
        ElmController(failingInput, ByteArrayOutputStream()).sendCommand("010C")
    }

    @Test(expected = ObdException.ConnectionLost::class)
    fun `sendCommand throws ConnectionLost when writing to the adapter fails`() = runTest {
        // Der Adapter (oder das Funkmodul) hat die Verbindung geschlossen, waehrend der Poller pausierte:
        // der naechste Schreibzugriff scheitert ("Broken pipe"). Das muss wie ein Lesefehler als
        // Verbindungsverlust gelten, damit der Reconnect greift — nicht als rohe IOException.
        val failingOutput = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Broken pipe")
        }
        ElmController(ByteArrayInputStream(ByteArray(0)), failingOutput).sendCommand("010C")
    }

    @Test(expected = ObdException.ConnectionLost::class)
    fun `sendCommand throws ConnectionLost when flushing to the adapter fails`() = runTest {
        val failingFlush = object : OutputStream() {
            override fun write(b: Int) {}
            override fun flush() = throw IOException("socket closed")
        }
        ElmController(ByteArrayInputStream(ByteArray(0)), failingFlush).sendCommand("010C")
    }

    @Test(expected = ObdException.ConnectionLost::class)
    fun `sendCommand throws ConnectionLost when stream is empty`() = runTest {
        val emptyInput = ByteArrayInputStream(ByteArray(0))
        ElmController(emptyInput, ByteArrayOutputStream()).sendCommand("010C")
    }

    @Test(expected = ObdException.ConnectionLost::class)
    fun `sendCommand throws ConnectionLost when the stream ends before the prompt`() = runTest {
        // Android 17 (targetSdk 37): read() auf einem RFCOMM-Socket liefert beim Abriss der Verbindung -1 statt einer IOException (BluetoothSocket,
        // „Bluetooth RFCOMM read() returns -1“). Kam davor schon ein Teil der Antwort an, hielt readResponse die halbe Antwort fuer eine ganze
        // und meldete keinen Verlust — der Abriss fiel erst beim naechsten Befehl auf. Eine Antwort endet mit dem Prompt '>'; Ende des
        // Datenstroms davor ist nie eine Antwort.
        // ScriptedAdapter statt eines vorab gefuellten ByteArrayInputStream: der Controller verwirft Altbytes vor dem Befehl (PRO-13), ein fertig
        // gefuellter Eingang wuerde mit weggeworfen und der Test bestuende aus dem falschen Grund.
        val adapter = ScriptedAdapter(listOf("41 0C 1A".toByteArray())) // keine Prompt-Zeile; danach liefert read() -1
        ElmController(adapter.input, adapter.output).sendCommand("010C")
    }

    @Test
    fun `an answer that is complete before the stream ends is still an answer`() = runTest {
        // Gegenprobe zum Test davor: das Ende des Datenstroms HINTER dem Prompt ist keine Stoerung der Antwort.
        val adapter = ScriptedAdapter(listOf("41 0C 1A F8\r>".toByteArray()))
        assertEquals("41 0C 1A F8", ElmController(adapter.input, adapter.output).sendCommand("010C"))
    }

    // B38: Die Zeitueberschreitung ist pruefbar, seit das Lese-Timeout ein Konstruktor-Parameter ist. Sie laeuft in
    // ECHTER Zeit (withTimeout in withContext(Dispatchers.IO) kennt die virtuelle Uhr nicht) — mit 100 ms statt der
    // 5 s der Produktion kostet der Test eine Zehntelsekunde statt die Suite zu verlangsamen.

    @Test
    fun `sendCommand throws Timeout when the adapter stays silent past the read timeout`() = runTest {
        // Ein Adapter, der den Befehl annimmt und nie antwortet: der Eingang ist verbunden, aber leer und offen.
        val silent = PipedInputStream()
        PipedOutputStream(silent).use {
            val controller = ElmController(silent, ByteArrayOutputStream(), readTimeoutMs = 100)
            val started = System.nanoTime()

            val failure = runCatching { controller.sendCommand("010C") }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue("Timeout erwartet, war: $failure", failure is ObdException.Timeout)
            assertTrue("der injizierte Wert gilt (100 ms), nicht die 5 s der Produktion: $elapsedMs ms", elapsedMs < 2_000)
            assertTrue("die Meldung nennt den Befehl: ${failure?.message}", failure?.message?.contains("010C") == true)
        }
    }

    @Test
    fun `cancelling a pending command is a cancellation - not a lost connection`() = runTest {
        val silent = PipedInputStream()
        PipedOutputStream(silent).use {
            val controller = ElmController(silent, ByteArrayOutputStream(), readTimeoutMs = 60_000)
            val pending = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                controller.sendCommand("010C")
            }
            kotlinx.coroutines.delay(50)   // der Lesevorgang blockiert jetzt in der Pipe

            pending.cancel()
            val failure = runCatching { pending.await() }.exceptionOrNull()

            assertTrue("Abbruch erwartet, war: $failure", failure is kotlinx.coroutines.CancellationException)
        }
    }

    @Test
    fun `a response that arrives within the read timeout is not a timeout`() = runTest {
        val (input, output) = makeStreams(listOf("41 0C 1F 40"))

        val response = ElmController(input, output, readTimeoutMs = 2_000).sendCommand("010C")

        assertEquals("41 0C 1F 40", response)
    }

    // B38: die Meldungen, die ein ELM327 VOR die eigentliche Antwort stellt (Datenblatt: der erste OBD-Befehl nach
    // ATSP0 loest die Protokollsuche aus und meldet "SEARCHING..."; bei ISO/KWP zusaetzlich "BUS INIT: OK").
    // Der Controller reicht alle Zeilen unveraendert durch — die Auswertung muss damit umgehen koennen.

    @Test
    fun `sendCommand keeps the SEARCHING banner as the first part of the joined response`() = runTest {
        val (input, output) = makeStreams(listOf("SEARCHING...\r41 00 BE 3F A8 13"))

        val response = ElmController(input, output).sendCommand("0100")

        assertEquals("SEARCHING... 41 00 BE 3F A8 13", response)
    }

    @Test
    fun `sendCommand keeps a BUS INIT banner as the first part of the joined response`() = runTest {
        val (input, output) = makeStreams(listOf("SEARCHING...\rBUS INIT: OK\r41 00 BE 3F A8 13"))

        val response = ElmController(input, output).sendCommand("0100")

        assertEquals("SEARCHING... BUS INIT: OK 41 00 BE 3F A8 13", response)
    }

    @Test
    fun `sendCommand assembles response spanning multiple physical read calls`() = runTest {
        // ElmController.readResponse() buffers reads into a 64-byte readBuffer and only calls
        // inputStream.read() again once bufferPos catches up to bufferEnd. Build a two-frame
        // response whose bytes -- plus the "\r>" terminator makeStreams appends -- exceed 64
        // bytes, so ByteArrayInputStream.read(byteArray) fills the 64-byte buffer completely on
        // the first call and readResponse() must hit the bufferPos >= bufferEnd refill branch to
        // read the remainder before finding the '>' prompt.
        val frame1 = "49 02 01 57 41 55 5A 5A 5A"
        val frame2 = "49 02 02 30 30 30 30 30 30 30 30 30 30 30 30"
        val longResponse = "$frame1\r$frame2"
        val combinedLength = longResponse.length + 2 // + the "\r>" makeStreams appends
        assertTrue(
            "fixture must exceed the 64-byte read buffer to exercise the refill branch: $combinedLength bytes",
            combinedLength > 64
        )
        val (input, output) = makeStreams(listOf(longResponse))
        val controller = ElmController(input, output)

        val response = controller.sendCommand("0902")

        assertEquals("$frame1 $frame2", response)
    }

    // ── Altbytes vor einem Befehl (PRO-13) ────────────────────────────────────
    //
    // Der ELM327 kennt keine Antwort-Kennung: Wer nach einem Zeitlimit die verspaetete Antwort des alten Befehls erst beim NAECHSTEN
    // Befehl liest, haelt sie fuer dessen Antwort — und jede weitere ist dann um eins verschoben.

    @Test
    fun `a late answer to an earlier command is discarded, not taken for the answer to this one`() = runTest {
        // Die Antwort auf "010C" kam erst nach dem Zeitlimit — jetzt steht sie im Eingang, bevor "010D" gesendet wird.
        val adapter = ScriptedAdapter(listOf("41 0D 28\r>".toByteArray()), stale = "41 0C 1F 40\r>".toByteArray())

        val response = ElmController(adapter.input, adapter.output).sendCommand("010D")

        assertEquals("41 0D 28", response)
    }

    @Test
    fun `bytes the adapter sent behind a prompt do not leak into the next answer`() = runTest {
        val adapter = ScriptedAdapter(listOf("41 0D 28\r>JUNK\r".toByteArray(), "41 0C 1F 40\r>".toByteArray()))
        val controller = ElmController(adapter.input, adapter.output)

        assertEquals("41 0D 28", controller.sendCommand("010D"))
        assertEquals("ohne das Verwerfen stuende hier \"JUNK 41 0C 1F 40\"", "41 0C 1F 40", controller.sendCommand("010C"))
    }

    @Test
    fun `an input that cannot say how much is waiting does not fail the command`() = runTest {
        val adapter = ScriptedAdapter(listOf("41 0D 28\r>".toByteArray()))
        val noAvailable = object : InputStream() {
            override fun read(): Int = adapter.input.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = adapter.input.read(b, off, len)
            override fun available(): Int = throw IOException("closed")
        }

        // Ein toter Link zeigt sich beim Schreiben/Lesen als ConnectionLost, nicht schon beim Aufraeumen davor.
        assertEquals("41 0D 28", ElmController(noAvailable, adapter.output).sendCommand("010D"))
    }

    @Test
    fun `the answer to the command itself is never thrown away`() = runTest {
        // Das Verwerfen geschieht VOR dem Schreiben: alles, was danach eintrifft, gehoert zur Antwort — auch eine lange.
        val long = "49 02 01 57 41 55 5A 5A 5A\r49 02 02 30 30 30 30 30 30 30 30 30 30 30 30\r49 02 03 31 31 31"
        val adapter = ScriptedAdapter(listOf("$long\r>".toByteArray()))

        val response = ElmController(adapter.input, adapter.output).sendCommand("0902")

        assertEquals(long.replace("\r", " "), response)
    }
}
