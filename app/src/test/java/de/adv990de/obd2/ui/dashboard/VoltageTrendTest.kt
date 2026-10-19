package de.adv990de.obd2.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Zustand und Balkengeometrie der Lade-/Entlade-Anzeige (Entwurf `2026-10-19-voltage-gauge-design.md`). */
class VoltageTrendTest {

    // --- Zustand ohne Vorgeschichte -------------------------------------------------------------

    @Test
    fun `13,3 V and above is charging`() {
        assertEquals(ChargeState.Charging, chargeState(13.3f, previous = null))
        assertEquals(ChargeState.Charging, chargeState(14.4f, previous = null))
    }

    @Test
    fun `from 12,7 V up to just below 13,3 V it barely charges`() {
        assertEquals(ChargeState.Marginal, chargeState(13.29f, previous = null))
        assertEquals(ChargeState.Marginal, chargeState(13.0f, previous = null))
        assertEquals(ChargeState.Marginal, chargeState(12.7f, previous = null))
    }

    @Test
    fun `below 12,7 V it discharges`() {
        assertEquals(ChargeState.Discharging, chargeState(12.69f, previous = null))
        assertEquals(ChargeState.Discharging, chargeState(11.6f, previous = null))
    }

    // --- Hysterese -------------------------------------------------------------------------------

    @Test
    fun `charging is left only below 13,2 V`() {
        assertEquals(ChargeState.Charging, chargeState(13.25f, previous = ChargeState.Charging))
        assertEquals(ChargeState.Charging, chargeState(13.2f, previous = ChargeState.Charging))
        assertEquals(ChargeState.Marginal, chargeState(13.19f, previous = ChargeState.Charging))
    }

    @Test
    fun `charging is entered at 13,3 V and not before, whatever the history`() {
        assertEquals(ChargeState.Marginal, chargeState(13.29f, previous = ChargeState.Marginal))
        assertEquals(ChargeState.Marginal, chargeState(13.29f, previous = ChargeState.Discharging))
        assertEquals(ChargeState.Charging, chargeState(13.3f, previous = ChargeState.Marginal))
        assertEquals(ChargeState.Charging, chargeState(13.3f, previous = ChargeState.Discharging))
    }

    @Test
    fun `discharging is left only from 12,8 V`() {
        assertEquals(ChargeState.Discharging, chargeState(12.7f, previous = ChargeState.Discharging))
        assertEquals(ChargeState.Discharging, chargeState(12.79f, previous = ChargeState.Discharging))
        assertEquals(ChargeState.Marginal, chargeState(12.8f, previous = ChargeState.Discharging))
    }

    @Test
    fun `discharging is entered below 12,7 V and not before, whatever the history`() {
        assertEquals(ChargeState.Marginal, chargeState(12.7f, previous = ChargeState.Marginal))
        assertEquals(ChargeState.Discharging, chargeState(12.69f, previous = ChargeState.Marginal))
        assertEquals(ChargeState.Discharging, chargeState(12.69f, previous = ChargeState.Charging))
        // Charging with the hysteresis is left, but a value that low is a discharge, not "barely".
        assertEquals(ChargeState.Discharging, chargeState(12.0f, previous = ChargeState.Charging))
    }

    @Test
    fun `a value on the line stays on its side of the dead band, in both directions`() {
        // 13,25 V: between 13,2 and 13,3 — the state it came from decides.
        assertEquals(ChargeState.Charging, chargeState(13.25f, previous = ChargeState.Charging))
        assertEquals(ChargeState.Marginal, chargeState(13.25f, previous = ChargeState.Marginal))
        // 12,75 V: between 12,7 and 12,8 — likewise.
        assertEquals(ChargeState.Discharging, chargeState(12.75f, previous = ChargeState.Discharging))
        assertEquals(ChargeState.Marginal, chargeState(12.75f, previous = ChargeState.Marginal))
    }

    @Test
    fun `a value that is not finite is refused`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            try {
                chargeState(bad, previous = null)
                fail("$bad must be refused")
            } catch (expected: IllegalArgumentException) {
                // the caller checks first; a state for "no value" would be an invented statement
            }
        }
    }

    // --- Balkengeometrie ---------------------------------------------------------------------------

    @Test
    fun `the zero line sits at 42,5 percent of the height from the bottom`() {
        assertEquals((12.7f - 11f) / 4f, VOLTAGE_BAR_ZERO_FRACTION, 1e-6f)
        assertEquals(0.425f, VOLTAGE_BAR_ZERO_FRACTION, 1e-6f)
    }

    @Test
    fun `the scale runs from 11 to 15 V with the zero line at 12,7 V`() {
        assertEquals(11f, VOLTAGE_BAR_MIN_V)
        assertEquals(15f, VOLTAGE_BAR_MAX_V)
        assertEquals(12.7f, VOLTAGE_BAR_ZERO_V)
    }

    @Test
    fun `a value on the zero line fills nothing`() {
        val fill = voltageBarFill(12.7f)!!
        assertEquals(VOLTAGE_BAR_ZERO_FRACTION, fill.fromFraction, 1e-6f)
        assertEquals(VOLTAGE_BAR_ZERO_FRACTION, fill.toFraction, 1e-6f)
    }

    @Test
    fun `a value above the zero line fills upwards from it`() {
        val fill = voltageBarFill(13.3f)!!
        assertEquals(0.425f, fill.fromFraction, 1e-6f)
        assertEquals((13.3f - 11f) / 4f, fill.toFraction, 1e-6f)
        assertEquals(0.575f, fill.toFraction, 1e-6f)
    }

    @Test
    fun `a value below the zero line fills downwards from it`() {
        val fill = voltageBarFill(12.0f)!!
        assertEquals(0.25f, fill.fromFraction, 1e-6f)
        assertEquals(0.425f, fill.toFraction, 1e-6f)
    }

    @Test
    fun `the ends of the scale are the ends of the bar and values beyond them are cut off`() {
        assertEquals(1f, voltageBarFill(15f)!!.toFraction, 1e-6f)
        assertEquals(1f, voltageBarFill(17.5f)!!.toFraction, 1e-6f)
        assertEquals(0f, voltageBarFill(11f)!!.fromFraction, 1e-6f)
        assertEquals(0f, voltageBarFill(7.8f)!!.fromFraction, 1e-6f)
    }

    @Test
    fun `from never exceeds to`() {
        var volts = 6f
        while (volts <= 18f) {
            val fill = voltageBarFill(volts)!!
            assertTrue("$volts V: ${fill.fromFraction} > ${fill.toFraction}", fill.fromFraction <= fill.toFraction)
            assertTrue(fill.fromFraction in 0f..1f && fill.toFraction in 0f..1f)
            volts += 0.1f
        }
    }

    @Test
    fun `no fill for a value that is not finite`() {
        assertNull(voltageBarFill(Float.NaN))
        assertNull(voltageBarFill(Float.POSITIVE_INFINITY))
        assertNull(voltageBarFill(Float.NEGATIVE_INFINITY))
        assertNotNull(voltageBarFill(0f))
    }

    @Test
    fun `the display window and the minimum samples are the specified ones`() {
        assertEquals(10_000L, TREND_WINDOW_MS)
        assertEquals(3, TREND_MIN_SAMPLES)
    }
}
