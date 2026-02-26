package de.adv990de.obd2.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DELTA = 0.01f

class PidRegistryTest {

    // ── Registry integrity ────────────────────────────────────────────────
    @Test fun `all map has 19 entries`() = assertEquals(19, PidRegistry.all.size)
    @Test fun `all map keys match PID ids`() {
        for ((key, pid) in PidRegistry.all) assertEquals(key, pid.id)
    }
    @Test fun `defaults are a subset of all`() {
        for (pid in PidRegistry.defaults) assertTrue(pid.id in PidRegistry.all)
    }


    private val parser = PidParser()

    private fun parse(pid: de.adv990de.obd2.obd.model.PidDefinition, vararg bytes: Int): Float {
        val byteHex = bytes.joinToString(" ") { "%02X".format(it) }
        val pidHex = pid.id.takeLast(2)
        val raw = "41 $pidHex $byteHex"
        return parser.parse(pid, raw).value
    }

    // ── RPM: ((A*256 + B) / 4) ────────────────────────────────────────────
    @Test fun `RPM formula zero`()    = assertEquals(0f,        parse(PidRegistry.RPM, 0x00, 0x00), DELTA)
    @Test fun `RPM formula typical`() = assertEquals(1726f,     parse(PidRegistry.RPM, 0x1A, 0xF8), DELTA)
    @Test fun `RPM formula maximum`() = assertEquals(16383.75f, parse(PidRegistry.RPM, 0xFF, 0xFF), DELTA)

    // ── SPEED: (A) ────────────────────────────────────────────────────────
    @Test fun `Speed formula zero`()    = assertEquals(0f,   parse(PidRegistry.SPEED, 0x00), DELTA)
    @Test fun `Speed formula maximum`() = assertEquals(255f, parse(PidRegistry.SPEED, 0xFF), DELTA)

    // ── COOLANT_TEMP: (A - 40) ────────────────────────────────────────────
    @Test fun `CoolantTemp formula minimum`() = assertEquals(-40f, parse(PidRegistry.COOLANT_TEMP, 0x00), DELTA)
    @Test fun `CoolantTemp formula typical`() = assertEquals(50f,  parse(PidRegistry.COOLANT_TEMP, 0x5A), DELTA)
    @Test fun `CoolantTemp formula maximum`() = assertEquals(215f, parse(PidRegistry.COOLANT_TEMP, 0xFF), DELTA)

    // ── ENGINE_LOAD: (A*100/255) ──────────────────────────────────────────
    @Test fun `EngineLoad formula zero`()    = assertEquals(0f,   parse(PidRegistry.ENGINE_LOAD, 0x00), DELTA)
    @Test fun `EngineLoad formula maximum`() = assertEquals(100f, parse(PidRegistry.ENGINE_LOAD, 0xFF), DELTA)

    // ── THROTTLE: (A*100/255) ─────────────────────────────────────────────
    @Test fun `Throttle formula zero`()    = assertEquals(0f,   parse(PidRegistry.THROTTLE, 0x00), DELTA)
    @Test fun `Throttle formula maximum`() = assertEquals(100f, parse(PidRegistry.THROTTLE, 0xFF), DELTA)

    // ── INTAKE_AIR_TEMP: (A - 40) ─────────────────────────────────────────
    @Test fun `IntakeAirTemp formula minimum`() = assertEquals(-40f, parse(PidRegistry.INTAKE_AIR_TEMP, 0x00), DELTA)
    @Test fun `IntakeAirTemp formula typical`() = assertEquals(25f,  parse(PidRegistry.INTAKE_AIR_TEMP, 0x41), DELTA)
    @Test fun `IntakeAirTemp formula maximum`() = assertEquals(215f, parse(PidRegistry.INTAKE_AIR_TEMP, 0xFF), DELTA)

    // ── MAF: ((A*256 + B) / 100) ─────────────────────────────────────────
    @Test fun `MAF formula zero`()    = assertEquals(0f,      parse(PidRegistry.MAF, 0x00, 0x00), DELTA)
    @Test fun `MAF formula typical`() = assertEquals(3.0f,    parse(PidRegistry.MAF, 0x01, 0x2C), DELTA)
    @Test fun `MAF formula maximum`() = assertEquals(655.35f, parse(PidRegistry.MAF, 0xFF, 0xFF), DELTA)

    // ── SHORT_TERM_FUEL_TRIM: (A*100/128 - 100) ──────────────────────────
    // Boundary artefact: 0xFF yields 99.21875 (spec-known overshoot, documented in PidRegistry)
    @Test fun `ShortFuelTrim formula zero byte`()      = assertEquals(-100f,     parse(PidRegistry.SHORT_TERM_FUEL_TRIM, 0x00), DELTA)
    @Test fun `ShortFuelTrim formula neutral (0x80)`() = assertEquals(0f,        parse(PidRegistry.SHORT_TERM_FUEL_TRIM, 0x80), DELTA)
    @Test fun `ShortFuelTrim formula max byte`()       = assertEquals(99.21875f, parse(PidRegistry.SHORT_TERM_FUEL_TRIM, 0xFF), DELTA)

    // ── LONG_TERM_FUEL_TRIM: identical formula ────────────────────────────
    @Test fun `LongFuelTrim formula zero byte`() = assertEquals(-100f,     parse(PidRegistry.LONG_TERM_FUEL_TRIM, 0x00), DELTA)
    @Test fun `LongFuelTrim formula neutral`()   = assertEquals(0f,        parse(PidRegistry.LONG_TERM_FUEL_TRIM, 0x80), DELTA)
    @Test fun `LongFuelTrim formula max byte`()  = assertEquals(99.21875f, parse(PidRegistry.LONG_TERM_FUEL_TRIM, 0xFF), DELTA)

    // ── INTAKE_MANIFOLD_PRESSURE: (A) ────────────────────────────────────
    @Test fun `MAP formula zero`()    = assertEquals(0f,   parse(PidRegistry.INTAKE_MANIFOLD_PRESSURE, 0x00), DELTA)
    @Test fun `MAP formula typical`() = assertEquals(101f, parse(PidRegistry.INTAKE_MANIFOLD_PRESSURE, 0x65), DELTA)
    @Test fun `MAP formula maximum`() = assertEquals(255f, parse(PidRegistry.INTAKE_MANIFOLD_PRESSURE, 0xFF), DELTA)

    // ── TIMING_ADVANCE: (A/2 - 64) ───────────────────────────────────────
    @Test fun `TimingAdvance formula minimum`() = assertEquals(-64f,  parse(PidRegistry.TIMING_ADVANCE, 0x00), DELTA)
    @Test fun `TimingAdvance formula zero`()    = assertEquals(0f,    parse(PidRegistry.TIMING_ADVANCE, 0x80), DELTA)
    @Test fun `TimingAdvance formula maximum`() = assertEquals(63.5f, parse(PidRegistry.TIMING_ADVANCE, 0xFF), DELTA)

    // ── FUEL_TANK_LEVEL: (A*100/255) ─────────────────────────────────────
    @Test fun `FuelLevel formula zero`() = assertEquals(0f,   parse(PidRegistry.FUEL_TANK_LEVEL, 0x00), DELTA)
    @Test fun `FuelLevel formula full`() = assertEquals(100f, parse(PidRegistry.FUEL_TANK_LEVEL, 0xFF), DELTA)

    // ── BAROMETRIC_PRESSURE: (A) ──────────────────────────────────────────
    @Test fun `BaroPressure formula zero`()    = assertEquals(0f,   parse(PidRegistry.BAROMETRIC_PRESSURE, 0x00), DELTA)
    @Test fun `BaroPressure formula typical`() = assertEquals(101f, parse(PidRegistry.BAROMETRIC_PRESSURE, 0x65), DELTA)
    @Test fun `BaroPressure formula maximum`() = assertEquals(255f, parse(PidRegistry.BAROMETRIC_PRESSURE, 0xFF), DELTA)

    // ── CATALYST_TEMP_B1S1: ((A*256 + B) / 10 - 40) ─────────────────────
    // 0x19 0x00 = (25*256+0)/10 - 40 = 6400/10 - 40 = 640 - 40 = 600
    @Test fun `CatalystTemp formula minimum`() = assertEquals(-40f,    parse(PidRegistry.CATALYST_TEMP_B1S1, 0x00, 0x00), DELTA)
    @Test fun `CatalystTemp formula typical`() = assertEquals(600f,    parse(PidRegistry.CATALYST_TEMP_B1S1, 0x19, 0x00), DELTA)
    @Test fun `CatalystTemp formula maximum`() = assertEquals(6513.5f, parse(PidRegistry.CATALYST_TEMP_B1S1, 0xFF, 0xFF), DELTA)

    // ── CONTROL_MODULE_VOLTAGE: ((A*256 + B) / 1000) ─────────────────────
    // 0x2E 0xE0 = (46*256+224)/1000 = 12000/1000 = 12.0
    @Test fun `ControlModuleVoltage formula typical`() = assertEquals(12.0f, parse(PidRegistry.CONTROL_MODULE_VOLTAGE, 0x2E, 0xE0), 0.001f)

    // ── AMBIENT_AIR_TEMP: (A - 40) ───────────────────────────────────────
    // 0x3C = 60, 60 - 40 = 20
    @Test fun `AmbientTemp formula minimum`() = assertEquals(-40f, parse(PidRegistry.AMBIENT_AIR_TEMP, 0x00), DELTA)
    @Test fun `AmbientTemp formula typical`() = assertEquals(20f,  parse(PidRegistry.AMBIENT_AIR_TEMP, 0x3C), DELTA)
    @Test fun `AmbientTemp formula maximum`() = assertEquals(215f, parse(PidRegistry.AMBIENT_AIR_TEMP, 0xFF), DELTA)

    // ── ACCELERATOR_PEDAL_POS: (A*100/255) ───────────────────────────────
    @Test fun `AccelPedalPos formula zero`()    = assertEquals(0f,   parse(PidRegistry.ACCELERATOR_PEDAL_POS, 0x00), DELTA)
    @Test fun `AccelPedalPos formula maximum`() = assertEquals(100f, parse(PidRegistry.ACCELERATOR_PEDAL_POS, 0xFF), DELTA)

    // ── ENGINE_OIL_TEMP: (A - 40) ────────────────────────────────────────
    // 0x78 = 120, 120 - 40 = 80
    @Test fun `EngineOilTemp formula minimum`() = assertEquals(-40f, parse(PidRegistry.ENGINE_OIL_TEMP, 0x00), DELTA)
    @Test fun `EngineOilTemp formula typical`() = assertEquals(80f,  parse(PidRegistry.ENGINE_OIL_TEMP, 0x78), DELTA)
    @Test fun `EngineOilTemp formula maximum`() = assertEquals(215f, parse(PidRegistry.ENGINE_OIL_TEMP, 0xFF), DELTA)

    // ── ENGINE_FUEL_RATE: ((A*256 + B) / 20) ─────────────────────────────
    // 0x00 0xC8 = (0*256+200)/20 = 200/20 = 10.0
    @Test fun `EngineFuelRate formula zero`()    = assertEquals(0f,    parse(PidRegistry.ENGINE_FUEL_RATE, 0x00, 0x00), DELTA)
    @Test fun `EngineFuelRate formula typical`() = assertEquals(10.0f, parse(PidRegistry.ENGINE_FUEL_RATE, 0x00, 0xC8), DELTA)
}
