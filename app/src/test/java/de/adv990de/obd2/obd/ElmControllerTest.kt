package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.ObdException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ElmControllerTest {

    private fun makeStreams(responses: List<String>): Pair<ByteArrayInputStream, ByteArrayOutputStream> {
        // Simulate ELM327: each response ends with ">" prompt
        val combined = responses.joinToString("") { "$it\r>" }
        return ByteArrayInputStream(combined.toByteArray()) to ByteArrayOutputStream()
    }

    @Test
    fun `sendCommand returns response line`() = runTest {
        val (input, output) = makeStreams(listOf("OK", "41 0C 1F 40"))
        val controller = ElmController(input, output)
        val response = controller.sendCommand("010C")
        assertEquals("41 0C 1F 40", response)
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
        assertTrue(sent.contains("ATSP0"))
    }

    @Test(expected = ObdException.InitializationFailed::class)
    fun `initialize throws when ATZ fails`() = runTest {
        val (input, output) = makeStreams(listOf("ERROR"))
        val controller = ElmController(input, output)
        controller.initialize()
    }
}
