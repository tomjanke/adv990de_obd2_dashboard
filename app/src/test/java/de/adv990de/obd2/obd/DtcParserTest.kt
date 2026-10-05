package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.ObdException
import org.junit.Assert.*
import org.junit.Test

class DtcParserTest {

    private val parser = DtcParser()

    // Mode 03 response for P0301, P0420
    // "43 03 01 04 20" → 0x03=P, 0x01 → P0301; 0x04 → P0420
    @Test
    fun `parse mode 03 returns confirmed DTCs`() {
        val dtcs = parser.parse("43 03 01 04 20", isPending = false)
        assertEquals(2, dtcs.size)
        assertEquals("P0301", dtcs[0].code)
        assertEquals("P0420", dtcs[1].code)
        assertFalse(dtcs[0].isPending)
    }

    @Test
    fun `parse mode 07 returns pending DTCs`() {
        val dtcs = parser.parse("47 03 01", isPending = true)
        assertEquals(1, dtcs.size)
        assertEquals("P0301", dtcs[0].code)
        assertTrue(dtcs[0].isPending)
    }

    @Test
    fun `parse no DTCs returns empty list`() {
        val dtcs = parser.parse("43 00", isPending = false)
        assertTrue(dtcs.isEmpty())
    }

    @Test
    fun `DTC type byte encodes correctly`() {
        // 0x00xx = P, 0x40xx = C, 0x80xx = B, 0xC0xx = U
        val dtcs = parser.parse("43 40 01 80 02 C0 03", isPending = false)
        assertEquals("C0001", dtcs[0].code)
        assertEquals("B0002", dtcs[1].code)
        assertEquals("U0003", dtcs[2].code)
    }

    // "43 01 00 07 40" → 0x01,0x00 → P0100; 0x07,0x40 → P0740 (both in the lookup table)
    @Test
    fun `parse resolves known descriptions from the lookup table`() {
        val dtcs = parser.parse("43 01 00 07 40", isPending = false)
        assertEquals(2, dtcs.size)
        assertEquals("P0100", dtcs[0].code)
        assertEquals("Mass or Volume Air Flow Circuit Malfunction", dtcs[0].description)
        assertEquals("P0740", dtcs[1].code)
        assertEquals("Torque Converter Clutch Circuit Malfunction", dtcs[1].description)
    }

    // "43 09 99" → 0x09,0x99 → P0999, which is not in the lookup table
    @Test
    fun `parse falls back to Unknown DTC for a code not in the lookup table`() {
        val dtcs = parser.parse("43 09 99", isPending = false)
        assertEquals(1, dtcs.size)
        assertEquals("P0999", dtcs[0].code)
        assertEquals("Unknown DTC", dtcs[0].description)
    }

    @Test(expected = ObdException.UnknownResponse::class)
    fun `parse rejects a pending header when confirmed DTCs were requested`() {
        // Header is "47" (pending) but isPending=false expects "43": not an answer to THIS question
        parser.parse("47 03 01", isPending = false)
    }

    @Test(expected = ObdException.UnknownResponse::class)
    fun `parse rejects a confirmed header when pending DTCs were requested`() {
        // Header is "43" (confirmed) but isPending=true expects "47"
        parser.parse("43 03 01", isPending = true)
    }

    // "43 ZZ 01 04 20" → "ZZ" is not valid hex, so the first pair (ZZ, 01) is skipped
    // entirely; parsing resumes at the next pair (04, 20) → P0420.
    @Test
    fun `parse skips a malformed non-hex byte pair and continues parsing`() {
        val dtcs = parser.parse("43 ZZ 01 04 20", isPending = false)
        assertEquals(1, dtcs.size)
        assertEquals("P0420", dtcs[0].code)
    }

    // "43 03 01 04" → 3 DTC bytes after the header is an odd count. The loop only
    // advances in pairs, so the trailing "04" is silently dropped, leaving just P0301.
    @Test
    fun `parse silently drops an odd trailing byte`() {
        val dtcs = parser.parse("43 03 01 04", isPending = false)
        assertEquals(1, dtcs.size)
        assertEquals("P0301", dtcs[0].code)
    }

    // "43 03 01 00 00 04 20" → 0x03,0x01 → P0301; 0x00,0x00 → an empty DTC slot
    // (e.g. padding between real codes) that must be skipped via the
    // "high == 0 && low == 0" branch, not the loop's i+1<size bound — there are
    // still two more real bytes ("04 20" → P0420) after it.
    @Test
    fun `parse skips a genuine empty DTC slot interspersed between real DTCs`() {
        val dtcs = parser.parse("43 03 01 00 00 04 20", isPending = false)
        assertEquals(2, dtcs.size)
        assertEquals("P0301", dtcs[0].code)
        assertEquals("P0420", dtcs[1].code)
    }

    // ── A9 (Teil 1): "keine Antwort" und "Fehler" sind NICHT "keine Fehlercodes" ───────────────────────────────
    //
    // Bis 1.24.5 lieferte der Parser fuer JEDE Antwort, die nicht mit 43/47 begann, eine leere Liste — NO DATA,
    // UNABLE TO CONNECT, CAN ERROR, "?" — und die Seite zeigte den gruenen Haken "Keine Fehlercodes". Eine positive
    // Antwort ohne Codes ("43 00") ist eine Aussage des Steuergeraets; alles andere ist keine.

    private fun assertThrows(expected: Class<out ObdException>, raw: String, isPending: Boolean = false) {
        val failure = runCatching { parser.parse(raw, isPending) }.exceptionOrNull()
        assertTrue("'$raw' muss ${expected.simpleName} werfen, war: $failure", expected.isInstance(failure))
    }

    @Test
    fun `NO DATA is not an all-clear - the ECU did not answer`() {
        assertThrows(ObdException.NoData::class.java, "NO DATA")
        assertThrows(ObdException.NoData::class.java, "no data")
        assertThrows(ObdException.NoData::class.java, "NO DATA", isPending = true)
    }

    @Test
    fun `adapter bus errors are errors, not an empty list`() {
        for (raw in listOf("UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY", "FB ERROR", "BUS INIT: ...ERROR")) {
            assertThrows(ObdException.BusInit::class.java, raw)
        }
    }

    @Test
    fun `other adapter complaints and garbage are unknown responses`() {
        for (raw in listOf("?", "ERROR", "DATA ERROR", "<DATA ERROR", "BUFFER FULL", "STOPPED", "ELM327 v1.5", "OK", "")) {
            assertThrows(ObdException.UnknownResponse::class.java, raw)
        }
        assertThrows(ObdException.UnknownResponse::class.java, "   ")
    }

    @Test
    fun `the protocol search banner in front of a real answer is ignored`() {
        val dtcs = parser.parse("SEARCHING... 43 03 01", isPending = false)
        assertEquals(listOf("P0301"), dtcs.map { it.code })
    }

    @Test
    fun `the protocol search banner in front of an error is still an error`() {
        assertThrows(ObdException.NoData::class.java, "SEARCHING... NO DATA")
        assertThrows(ObdException.BusInit::class.java, "SEARCHING... UNABLE TO CONNECT")
    }

    @Test
    fun `a positive answer without any code is a real all-clear`() {
        assertTrue(parser.parse("43 00", isPending = false).isEmpty())
        assertTrue(parser.parse("47 00", isPending = true).isEmpty())
        // K-Line/ISO 9141 pads the answer with zero pairs
        assertTrue(parser.parse("43 00 00 00 00 00 00", isPending = false).isEmpty())
        // only the mode byte
        assertTrue(parser.parse("43", isPending = false).isEmpty())
    }

    @Test
    fun `the NoData message names the mode so the log shows which question went unanswered`() {
        val failure = runCatching { parser.parse("NO DATA", isPending = true) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("07") == true)
    }

    @Test
    fun `an unknown response keeps the raw text for the log`() {
        val failure = runCatching { parser.parse("WHAT IS THIS", isPending = false) }.exceptionOrNull()
        assertTrue(failure is ObdException.UnknownResponse)
        assertTrue(failure?.message?.contains("WHAT IS THIS") == true)
    }
}
