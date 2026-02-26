package de.adv990de.obd2.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedEcuDispatcherTest {

    // ── AT commands ──────────────────────────────────────────────────────────

    @Test
    fun `ATZ returns ELM327 identifier`() {
        val d = SimulatedEcuDispatcher()
        assertTrue("Expected ELM327 in ATZ response", d.respond("ATZ").contains("ELM327"))
    }

    @Test
    fun `AT commands other than ATZ return OK`() {
        val d = SimulatedEcuDispatcher()
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATSP0")) {
            assertEquals("Expected OK for $cmd", "OK", d.respond(cmd))
        }
    }

    @Test
    fun `commands are case-insensitive`() {
        val d = SimulatedEcuDispatcher()
        assertEquals("OK", d.respond("ate0"))
    }

    // ── PID bitmaps ───────────────────────────────────────────────────────────

    @Test
    fun `0100 response has correct header`() {
        val d = SimulatedEcuDispatcher()
        val parts = d.respond("0100").split(" ")
        assertEquals("41", parts[0])
        assertEquals("00", parts[1])
        assertEquals(6, parts.size)
    }

    @Test
    fun `0100 bitmap declares RPM (0C) support`() {
        assertPidSupported(range = "0100", rangeStart = 0x00, pid = 0x0C)
    }

    @Test
    fun `0100 bitmap declares Speed (0D) support`() {
        assertPidSupported(range = "0100", rangeStart = 0x00, pid = 0x0D)
    }

    @Test
    fun `0100 bitmap marks 0120 range as supported`() {
        assertPidSupported(range = "0100", rangeStart = 0x00, pid = 0x20)
    }

    @Test
    fun `0120 bitmap declares fuel level (2F) support`() {
        assertPidSupported(range = "0120", rangeStart = 0x20, pid = 0x2F)
    }

    @Test
    fun `0140 bitmap declares ambient temp (46) support`() {
        assertPidSupported(range = "0140", rangeStart = 0x40, pid = 0x46)
    }

    // ── PID responses ─────────────────────────────────────────────────────────

    @Test
    fun `RPM response has correct format and value in range`() {
        val d = SimulatedEcuDispatcher()
        val parts = d.respond("010C").split(" ")
        assertEquals("41", parts[0])
        assertEquals("0C", parts[1])
        assertEquals(4, parts.size)
        val raw = parts[2].toInt(16) * 256 + parts[3].toInt(16)
        val rpm = raw / 4
        assertTrue("RPM $rpm not in 800..4500", rpm in 800..4500)
    }

    @Test
    fun `Speed response has correct format and value in range`() {
        val d = SimulatedEcuDispatcher()
        val parts = d.respond("010D").split(" ")
        assertEquals("41", parts[0])
        assertEquals("0D", parts[1])
        assertEquals(3, parts.size)
        val speed = parts[2].toInt(16)
        assertTrue("Speed $speed not in 0..120", speed in 0..120)
    }

    @Test
    fun `Coolant temp response has correct format`() {
        val d = SimulatedEcuDispatcher()
        val parts = d.respond("0105").split(" ")
        assertEquals("41", parts[0])
        assertEquals("05", parts[1])
        assertEquals(3, parts.size)
        val temp = parts[2].toInt(16) - 40
        assertTrue("Coolant $temp°C not in 20..90", temp in 20..90)
    }

    @Test
    fun `coolant temp increases after time passes`() {
        val d = SimulatedEcuDispatcher()
        val t1 = d.respond("0105").split(" ")[2].toInt(16) - 40
        Thread.sleep(500)
        val t2 = d.respond("0105").split(" ")[2].toInt(16) - 40
        assertTrue("Coolant should not decrease: $t1 -> $t2", t2 >= t1)
    }

    @Test
    fun `Fuel level is constant 65 percent`() {
        val d = SimulatedEcuDispatcher()
        val parts = d.respond("012F").split(" ")
        assertEquals("41", parts[0])
        assertEquals("2F", parts[1])
        val levelPct = parts[2].toInt(16) * 100 / 255
        assertEquals(65, levelPct)
    }

    @Test
    fun `unknown PID returns NO DATA`() {
        val d = SimulatedEcuDispatcher()
        assertEquals("NO DATA", d.respond("01FF"))
        assertEquals("NO DATA", d.respond("0199"))
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun assertPidSupported(range: String, rangeStart: Int, pid: Int) {
        val d = SimulatedEcuDispatcher()
        val parts = d.respond(range).split(" ")
        // parts[2..5] are the 4 bitmap bytes
        val bitmap = parts.drop(2).joinToString("").toLong(16)
        val bitPos = 31 - (pid - rangeStart - 1)
        val mask = 1L shl bitPos
        assertTrue("PID 0x${pid.toString(16)} not declared in $range bitmap (bitmap=0x${bitmap.toString(16)})", bitmap and mask != 0L)
    }
}
