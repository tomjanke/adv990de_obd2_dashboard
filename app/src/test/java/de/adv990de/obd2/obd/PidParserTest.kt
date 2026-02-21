package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.ObdException
import org.junit.Assert.*
import org.junit.Test

class PidParserTest {

    private val parser = PidParser()

    // ELM327 response for RPM 2000: "41 0C 1F 40"
    // Data bytes after "41 0C": 0x1F=31, 0x40=64 → (31*256+64)/4 = 2000
    @Test
    fun `parse RPM response returns correct value`() {
        val result = parser.parse(PidRegistry.RPM, "41 0C 1F 40")
        assertEquals(2000f, result.value, 0.1f)
        assertEquals("RPM", result.pid.name)
    }

    // Speed 87 km/h: "41 0D 57" → 0x57 = 87
    @Test
    fun `parse Speed response returns correct value`() {
        val result = parser.parse(PidRegistry.SPEED, "41 0D 57")
        assertEquals(87f, result.value, 0.1f)
    }

    // Coolant 91°C: "41 05 83" → 0x83=131, 131-40=91
    @Test
    fun `parse CoolantTemp response returns correct value`() {
        val result = parser.parse(PidRegistry.COOLANT_TEMP, "41 05 83")
        assertEquals(91f, result.value, 0.1f)
    }

    @Test(expected = ObdException.NoData::class)
    fun `parse NO DATA throws NoData exception`() {
        parser.parse(PidRegistry.RPM, "NO DATA")
    }

    @Test(expected = ObdException.UnknownResponse::class)
    fun `parse empty response throws UnknownResponse`() {
        parser.parse(PidRegistry.RPM, "")
    }

    @Test(expected = ObdException.BusInit::class)
    fun `parse BUS INIT error throws BusInit exception`() {
        parser.parse(PidRegistry.RPM, "BUS INIT... ERROR")
    }

    @Test(expected = ObdException.UnknownResponse::class)
    fun `parse throws UnknownResponse when response PID does not match requested PID`() {
        // RPM requested (010C) but response contains Speed (0D)
        parser.parse(PidRegistry.RPM, "41 0D 57")
    }

    // ── parseFreezeFrame ─────────────────────────────────────────────────────

    @Test
    fun `parseFreezeFrame returns correct value for valid mode-02 response`() {
        // "42 0C 1A F8": header=42, PID=0C, data=0x1A 0xF8 → RPM = (26*256+248)/4 = 1726.0
        val result = parser.parseFreezeFrame(PidRegistry.RPM, "42 0C 1A F8")
        assertNotNull(result)
        assertEquals(1726.0f, result!!, 0.1f)
    }

    @Test
    fun `parseFreezeFrame returns null for BUS INIT response`() {
        // BUS INIT is returned by the adapter when the bus is reinitialising.
        // parseFreezeFrame must return null (not throw) — freeze-frame reads do not escalate
        // transient bus errors to ConnectionLost.
        assertNull(parser.parseFreezeFrame(PidRegistry.RPM, "BUS INIT... ERROR"))
    }

    @Test
    fun `parseFreezeFrame returns null for NO DATA response`() {
        assertNull(parser.parseFreezeFrame(PidRegistry.RPM, "NO DATA"))
    }

    @Test
    fun `parseFreezeFrame returns null when PID byte does not match`() {
        // Response is for Speed (0D) but RPM (0C) was requested
        assertNull(parser.parseFreezeFrame(PidRegistry.RPM, "42 0D 57"))
    }
}
