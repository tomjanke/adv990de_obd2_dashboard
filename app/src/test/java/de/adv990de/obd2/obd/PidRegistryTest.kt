package de.adv990de.obd2.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PidRegistryTest {

    // ── Registry integrity ────────────────────────────────────────────────────

    @Test
    fun `all map has 19 entries`() {
        assertEquals(19, PidRegistry.all.size)
    }

    @Test
    fun `all map keys match PID ids`() {
        PidRegistry.all.forEach { (key, pid) ->
            assertEquals("Key '$key' does not match pid.id '${pid.id}'", key, pid.id)
        }
    }

    @Test
    fun `defaults are a subset of all`() {
        PidRegistry.defaults.forEach { pid ->
            assertTrue("${pid.id} from defaults not found in all", pid.id in PidRegistry.all)
        }
    }

    // ── SHORT_TERM_FUEL_TRIM formula ──────────────────────────────────────────

    @Test
    fun `SHORT_TERM_FUEL_TRIM A=128 yields 0 percent`() {
        val result = PidRegistry.SHORT_TERM_FUEL_TRIM.formula(byteArrayOf(0x80.toByte()))
        assertEquals(0.0f, result, 0.01f)
    }

    @Test
    fun `SHORT_TERM_FUEL_TRIM A=0 yields -100 percent`() {
        val result = PidRegistry.SHORT_TERM_FUEL_TRIM.formula(byteArrayOf(0x00))
        assertEquals(-100.0f, result, 0.01f)
    }

    // ── TIMING_ADVANCE formula ────────────────────────────────────────────────

    @Test
    fun `TIMING_ADVANCE A=128 yields 0 degrees`() {
        val result = PidRegistry.TIMING_ADVANCE.formula(byteArrayOf(0x80.toByte()))
        assertEquals(0.0f, result, 0.01f)
    }

    @Test
    fun `TIMING_ADVANCE A=0 yields -64 degrees`() {
        val result = PidRegistry.TIMING_ADVANCE.formula(byteArrayOf(0x00))
        assertEquals(-64.0f, result, 0.01f)
    }

    // ── CATALYST_TEMP_B1S1 formula ────────────────────────────────────────────

    @Test
    fun `CATALYST_TEMP_B1S1 0x01 0x9C yields 1_2 degrees C`() {
        // (1×256 + 156) / 10 − 40 = 412/10 − 40 = 41.2 − 40 = 1.2
        val result = PidRegistry.CATALYST_TEMP_B1S1.formula(byteArrayOf(0x01, 0x9C.toByte()))
        assertEquals(1.2f, result, 0.01f)
    }

    @Test
    fun `CATALYST_TEMP_B1S1 0x00 0x00 yields -40 degrees C`() {
        val result = PidRegistry.CATALYST_TEMP_B1S1.formula(byteArrayOf(0x00, 0x00))
        assertEquals(-40.0f, result, 0.01f)
    }

    // ── CONTROL_MODULE_VOLTAGE formula ────────────────────────────────────────

    @Test
    fun `CONTROL_MODULE_VOLTAGE 0x38 0xD8 yields 14_552 V`() {
        // (0x38×256 + 0xD8) / 1000 = (56×256 + 216) / 1000 = 14552/1000 = 14.552
        val result = PidRegistry.CONTROL_MODULE_VOLTAGE.formula(byteArrayOf(0x38, 0xD8.toByte()))
        assertEquals(14.552f, result, 0.001f)
    }

    // ── ENGINE_FUEL_RATE formula ──────────────────────────────────────────────

    @Test
    fun `ENGINE_FUEL_RATE 0x00 0x64 yields 5_0 L per h`() {
        // (0×256 + 100) / 20 = 100/20 = 5.0
        val result = PidRegistry.ENGINE_FUEL_RATE.formula(byteArrayOf(0x00, 0x64))
        assertEquals(5.0f, result, 0.001f)
    }
}
