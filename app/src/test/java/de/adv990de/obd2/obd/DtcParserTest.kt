package de.adv990de.obd2.obd

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

    @Test
    fun `parse rejects a pending header when confirmed DTCs were requested`() {
        // Header is "47" (pending) but isPending=false expects "43"
        val dtcs = parser.parse("47 03 01", isPending = false)
        assertTrue(dtcs.isEmpty())
    }

    @Test
    fun `parse rejects a confirmed header when pending DTCs were requested`() {
        // Header is "43" (confirmed) but isPending=true expects "47"
        val dtcs = parser.parse("43 03 01", isPending = true)
        assertTrue(dtcs.isEmpty())
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
}
