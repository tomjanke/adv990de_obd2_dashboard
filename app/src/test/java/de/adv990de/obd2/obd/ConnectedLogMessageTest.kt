package de.adv990de.obd2.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectedLogMessageTest {

    @Test
    fun `without a reset result the line is the plain one`() {
        assertEquals("Connected to MWT_OBD2", connectedLogMessage("MWT_OBD2", null))
    }

    @Test
    fun `the line names the answer to ATZ and how long it took`() {
        assertEquals(
            "Connected to MWT_OBD2 (ATZ: 'ELM327 v1.0', 940 ms)",
            connectedLogMessage("MWT_OBD2", InitResult("ELM327 v1.0", 940)),
        )
    }

    @Test
    fun `a silent adapter shows an empty answer, not a missing one`() {
        assertEquals(
            "Connected to X (ATZ: '', 12 ms)",
            connectedLogMessage("X", InitResult("", 12)),
        )
    }

    @Test
    fun `line breaks and runs of blanks in the answer become single blanks`() {
        assertEquals(
            "Connected to X (ATZ: 'ATZ ELM327 v1.5', 1010 ms)",
            connectedLogMessage("X", InitResult("  ATZ\r\n  ELM327   v1.5 ", 1010)),
        )
    }

    @Test
    fun `a long answer is cut at 40 characters`() {
        val answer = "A".repeat(100)
        assertEquals(
            "Connected to X (ATZ: '${"A".repeat(40)}', 5 ms)",
            connectedLogMessage("X", InitResult(answer, 5)),
        )
    }
}
