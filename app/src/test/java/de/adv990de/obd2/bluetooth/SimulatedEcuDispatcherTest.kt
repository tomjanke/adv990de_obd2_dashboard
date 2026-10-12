package de.adv990de.obd2.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedEcuDispatcherTest {

    private companion object {
        /** Ein Vielfaches der 20-s-Periode: dort beginnt die Drehzahlwelle bei 900 rpm. */
        const val START_MS = 40_000L
    }

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
    fun `0140 bitmap declares board voltage (42) support`() {
        // 0x42 liegt im Bereich 0x41..0x60, wird also von 0140 angekuendigt — nicht von 0120.
        assertPidSupported(range = "0140", rangeStart = 0x40, pid = 0x42)
    }

    @Test
    fun `0142 returns a plausible board voltage`() {
        val d = SimulatedEcuDispatcher()
        val response = d.respond("0142").replace(" ", "")
        assertTrue("Erwartet Mode-01-Antwort auf 42, war: $response", response.startsWith("4142"))
        val a = response.substring(4, 6).toInt(16)
        val b = response.substring(6, 8).toInt(16)
        val volts = (a * 256 + b) / 1000f
        assertTrue("Bordspannung ausserhalb des plausiblen Bereichs: $volts V", volts in 11.8f..15.0f)
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
        assertTrue("RPM $rpm not in 900..8500", rpm in 900..8500)
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

    // ── Die Kurven an festen Zeitpunkten (eine Uhr von Hand statt Thread.sleep, TST-13) ─────────────────────────

    /** Eine Uhr, die nur vorrueckt, wenn der Test es sagt. Beginnt bei einem Vielfachen der 20-s-Periode. */
    private class FakeClock(var nowMs: Long = 40_000L) {
        fun read(): Long = nowMs
    }

    private fun simulator(clock: FakeClock) = SimulatedEcuDispatcher(clock::read)

    private fun rpmAt(clock: FakeClock, d: SimulatedEcuDispatcher, offsetMs: Long): Int {
        clock.nowMs = START_MS + offsetMs
        val parts = d.respond("010C").split(" ")
        return (parts[2].toInt(16) * 256 + parts[3].toInt(16)) / 4
    }

    @Test
    fun `RPM follows a triangle wave with a period of 20 seconds`() {
        val clock = FakeClock(); val d = simulator(clock)

        assertEquals("Anfang der Periode", 900, rpmAt(clock, d, 0))
        assertEquals("ein Viertel", 4_700, rpmAt(clock, d, 5_000))
        assertEquals("Scheitel nach 10 s", 8_500, rpmAt(clock, d, 10_000))
        assertEquals("abfallend", 4_700, rpmAt(clock, d, 15_000))
        assertEquals("eine volle Periode spaeter", 900, rpmAt(clock, d, 20_000))
        assertEquals("und es wiederholt sich", 4_700, rpmAt(clock, d, 25_000))
    }

    @Test
    fun `speed is derived from RPM - 0 at the bottom, 120 at the top`() {
        val clock = FakeClock(); val d = simulator(clock)

        fun speedAt(offsetMs: Long): Int { clock.nowMs = START_MS + offsetMs; return d.respond("010D").split(" ")[2].toInt(16) }

        assertEquals(0, speedAt(0))
        assertEquals(60, speedAt(5_000))
        assertEquals(120, speedAt(10_000))
    }

    @Test
    fun `coolant ramps from 20 to 90 degrees over 90 seconds and stays there`() {
        val clock = FakeClock(); val d = simulator(clock)

        fun coolantAt(secondsSinceConnect: Long): Int {
            clock.nowMs = START_MS + secondsSinceConnect * 1_000
            return d.respond("0105").split(" ")[2].toInt(16) - 40
        }

        assertEquals("beim Verbinden", 20, coolantAt(0))
        assertEquals("nach 45 s die Haelfte", 55, coolantAt(45))
        assertEquals("nach 90 s oben", 90, coolantAt(90))
        assertEquals("danach bleibt es", 90, coolantAt(300))
    }

    @Test
    fun `the coolant ramp starts when the dispatcher is created, not at a fixed clock time`() {
        val clock = FakeClock(nowMs = 123_456_789L); val d = simulator(clock)

        clock.nowMs += 45_000L

        assertEquals(55, d.respond("0105").split(" ")[2].toInt(16) - 40)
    }

    @Test
    fun `load, throttle, MAP and MAF follow RPM at both ends of the wave`() {
        val clock = FakeClock(); val d = simulator(clock)

        clock.nowMs = START_MS          // 900 rpm
        assertEquals("Last unten (20 % = 0x33)", "41 04 33", d.respond("0104"))
        assertEquals("Drosselklappe unten", "41 11 00", d.respond("0111"))
        assertEquals("MAP unten (30 kPa)", "41 0B 1E", d.respond("010B"))
        assertEquals("MAF unten (0,50 g/s = 0x0032)", "41 10 00 32", d.respond("0110"))

        clock.nowMs = START_MS + 10_000 // 8500 rpm
        assertEquals("Last oben (95 % = 0xF2)", "41 04 F2", d.respond("0104"))
        assertEquals("Drosselklappe oben", "41 11 FF", d.respond("0111"))
        assertEquals("MAP oben (100 kPa)", "41 0B 64", d.respond("010B"))
        assertEquals("MAF oben, auf 49,99 g/s begrenzt (0x1387)", "41 10 13 87", d.respond("0110"))
    }

    @Test
    fun `a healthy ECU answers the fault code reads with no codes, not with NO DATA`() {
        val d = SimulatedEcuDispatcher()

        assertEquals("43 00", d.respond("03"))
        assertEquals("47 00", d.respond("07"))
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
    fun `Load response has correct format and value in range`() {
        val d = SimulatedEcuDispatcher()
        val parts = d.respond("0104").split(" ")
        assertEquals("41", parts[0])
        assertEquals("04", parts[1])
        assertEquals(3, parts.size)
        val raw = parts[2].toInt(16)
        val loadPct = raw * 100 / 255
        assertTrue("Load $loadPct% not in 20..95 (raw=$raw)", loadPct in 20..95)
    }

    @Test
    fun `Throttle response has correct format and value in range`() {
        val d = SimulatedEcuDispatcher()
        val parts = d.respond("0111").split(" ")
        assertEquals("41", parts[0])
        assertEquals("11", parts[1])
        assertEquals(3, parts.size)
        val raw = parts[2].toInt(16)
        val throttlePct = raw * 100 / 255
        assertTrue("Throttle $throttlePct% not in 0..100 (raw=$raw)", throttlePct in 0..100)
    }

    @Test
    fun `MAP response has correct format and value in range`() {
        val d = SimulatedEcuDispatcher()
        val parts = d.respond("010B").split(" ")
        assertEquals("41", parts[0])
        assertEquals("0B", parts[1])
        assertEquals(3, parts.size)
        // MAP is reported as a raw kPa byte, unlike Load/Throttle there is no *255/100 scaling.
        val kPa = parts[2].toInt(16)
        assertTrue("MAP $kPa kPa not in 30..100", kPa in 30..100)
    }

    @Test
    fun `MAF response has correct format and value in range`() {
        val d = SimulatedEcuDispatcher()
        val parts = d.respond("0110").split(" ")
        assertEquals("41", parts[0])
        assertEquals("10", parts[1])
        assertEquals(4, parts.size)
        val raw = parts[2].toInt(16) * 256 + parts[3].toInt(16)
        val gramsPerSec = raw / 100.0
        assertTrue("MAF $gramsPerSec g/s not in 0.50..49.99 (raw=$raw)", gramsPerSec in 0.50..49.99)
    }

    @Test
    fun `Ambient temp is constant 20 degrees`() {
        val d = SimulatedEcuDispatcher()
        val parts = d.respond("0146").split(" ")
        assertEquals("41", parts[0])
        assertEquals("46", parts[1])
        assertEquals(3, parts.size)
        val tempC = parts[2].toInt(16) - 40
        assertEquals(20, tempC)
    }

    @Test
    fun `Timing advance is constant 10 degrees`() {
        val d = SimulatedEcuDispatcher()
        val parts = d.respond("010E").split(" ")
        assertEquals("41", parts[0])
        assertEquals("0E", parts[1])
        assertEquals(3, parts.size)
        // Standard OBD-II PID 0x0E formula: advance = A/2 - 64.
        val raw = parts[2].toInt(16)
        val advanceDeg = raw / 2 - 64
        assertEquals(10, advanceDeg)
    }

    @Test
    fun `unknown PID returns NO DATA`() {
        val d = SimulatedEcuDispatcher()
        assertEquals("NO DATA", d.respond("01FF"))
        assertEquals("NO DATA", d.respond("0199"))
    }

    // ── buildBitmap() require() validation ──────────────────────────────────────
    //
    // buildBitmap()'s `require(offset in 1..32)` guards against a PID literal outside its
    // declared range. It is private and called exactly 3 times, all from field initializers
    // (BITMAP_0100/0120/0140) with fixed, hardcoded PID sets that are always in range by
    // construction — there is no code path (not even via respond()/respondPid()) that reaches
    // it with a value chosen at runtime or by a caller. It is a compile-time-fixed internal
    // invariant, not something exercisable through the class's public API, so no test forces an
    // artificial call into it (e.g. via reflection) — doing so would test the `require` call in
    // isolation rather than any real, reachable behavior of SimulatedEcuDispatcher.

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
