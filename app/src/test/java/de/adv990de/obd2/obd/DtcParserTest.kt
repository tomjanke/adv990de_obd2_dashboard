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
}
