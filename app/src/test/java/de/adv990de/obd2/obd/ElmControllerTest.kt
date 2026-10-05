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

    private fun makeStreams(responses: List<String>): Pair<ByteArrayInputStream, ByteArrayOutputStream> {
        // Simulate ELM327: each response ends with ">" prompt
        val combined = responses.joinToString("") { "$it\r>" }
        return ByteArrayInputStream(combined.toByteArray()) to ByteArrayOutputStream()
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
        assertTrue(sent.contains("ATZ"))
        assertTrue(sent.contains("ATE0"))
        assertTrue(sent.contains("ATL0"))
        // B9: Leerzeichen AN. Alle Parser (PIDs, Discovery, DTCs, Freeze-Frame) splitten an Leerzeichen; mit ATS0 lieferte
        // ein Adapter, der den Befehl befolgt, "410C1AF8" — und kein Parser haette es gelesen.
        assertTrue(sent.contains("ATS1"))
        assertFalse("ATS0 darf nicht mehr gesendet werden: $sent", sent.contains("ATS0"))
        assertTrue(sent.contains("ATSP0"))
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
}
