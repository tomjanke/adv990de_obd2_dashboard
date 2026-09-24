package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.ObdException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

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
        val responses = listOf("OK", "OK", "OK", "OK", "OK") // ATZ, ATE0, ATL0, ATS0, ATSP0
        val (input, output) = makeStreams(responses)
        val controller = ElmController(input, output)
        controller.initialize()
        val sent = output.toString()
        assertTrue(sent.contains("ATZ"))
        assertTrue(sent.contains("ATE0"))
        assertTrue(sent.contains("ATL0"))
        assertTrue(sent.contains("ATS0"))   // spaces off — required for consistent token-based parsing
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
    fun `sendCommand throws ConnectionLost when stream is empty`() = runTest {
        val emptyInput = ByteArrayInputStream(ByteArray(0))
        ElmController(emptyInput, ByteArrayOutputStream()).sendCommand("010C")
    }

    // NOTE: The Timeout path (ObdException.Timeout) cannot be covered with virtual time because
    // withTimeout inside withContext(Dispatchers.IO) uses real wall-clock time. advanceTimeBy()
    // only advances kotlinx-coroutines-test's virtual clock, which does not affect blocking
    // threads on the IO dispatcher. A real-time test would make the suite fragile (5 s wait),
    // so this case is intentionally left untested here.

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
