package de.adv990de.obd2.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GearSettingsScreenTest {

    @Test
    fun `a decimal comma parses the same as a decimal point`() {
        // German-locale keyboards (e.g. Samsung's) can offer only "," as the decimal key on a
        // KeyboardType.Decimal pad — without normalization a German-speaking user could not type
        // a fractional value like this at all.
        assertEquals(parseGearValue("1.9143", GearLimits.PRIMARY_RATIO), parseGearValue("1,9143", GearLimits.PRIMARY_RATIO))
        assertEquals(1.9143f, parseGearValue("1,9143", GearLimits.PRIMARY_RATIO)!!, 0.0001f)
    }

    @Test
    fun `non-numeric text and negative numbers are rejected`() {
        assertNull(parseGearValue("abc", GearLimits.GEAR_RATIO, allowZero = true))
        assertNull(parseGearValue("", GearLimits.GEAR_RATIO, allowZero = true))
        assertNull(parseGearValue("-5", GearLimits.GEAR_RATIO, allowZero = true))
    }

    @Test
    fun `Infinity and NaN are rejected`() {
        // Float.POSITIVE_INFINITY > 0f is true, so a plain positivity check lets "Infinity" through —
        // a value no gear-ratio math should ever receive.
        assertNull(parseGearValue("Infinity", GearLimits.GEAR_RATIO, allowZero = true))
        assertNull(parseGearValue("-Infinity", GearLimits.GEAR_RATIO, allowZero = true))
        assertNull(parseGearValue("NaN", GearLimits.GEAR_RATIO, allowZero = true))
    }

    // ── B22: plausible ranges, and "0" switches a gear off ─────────────────────────────────────────────

    @Test
    fun `a plausible wheel circumference is accepted`() {
        assertEquals(2096f, parseGearValue("2096", GearLimits.WHEEL_CIRCUMFERENCE_MM)!!, 0.001f)
        assertEquals(1800.5f, parseGearValue("1800,5", GearLimits.WHEEL_CIRCUMFERENCE_MM)!!, 0.001f)
    }

    @Test
    fun `a German thousands separator misread as a decimal point is rejected - 2_096 is not 2 millimetres`() {
        // "2.096" meint 2096 mm; als 2,096 mm gespeichert blieb die Ganganzeige fuer immer "–", ohne Hinweis.
        assertNull(parseGearValue("2.096", GearLimits.WHEEL_CIRCUMFERENCE_MM))
        assertNull(parseGearValue("2,096", GearLimits.WHEEL_CIRCUMFERENCE_MM))
    }

    @Test
    fun `values outside the range are rejected at both ends`() {
        assertNull(parseGearValue("999", GearLimits.WHEEL_CIRCUMFERENCE_MM))
        assertNull(parseGearValue("4001", GearLimits.WHEEL_CIRCUMFERENCE_MM))
        assertEquals(1000f, parseGearValue("1000", GearLimits.WHEEL_CIRCUMFERENCE_MM)!!, 0.001f)
        assertEquals(4000f, parseGearValue("4000", GearLimits.WHEEL_CIRCUMFERENCE_MM)!!, 0.001f)
    }

    @Test
    fun `zero is rejected unless the field may switch off`() {
        assertNull(parseGearValue("0", GearLimits.WHEEL_CIRCUMFERENCE_MM))
        assertNull(parseGearValue("0", GearLimits.PRIMARY_RATIO))
        assertNull(parseGearValue("0", GearLimits.GEAR_RATIO))
        assertEquals(0f, parseGearValue("0", GearLimits.GEAR_RATIO, allowZero = true)!!, 0f)
        assertEquals(0f, parseGearValue("0,0", GearLimits.GEAR_RATIO, allowZero = true)!!, 0f)
    }

    @Test
    fun `a gear ratio is bounded as well, and the default gears are all inside`() {
        assertNull(parseGearValue("9", GearLimits.GEAR_RATIO, allowZero = true))
        assertNull(parseGearValue("0.1", GearLimits.GEAR_RATIO, allowZero = true))
        de.adv990de.obd2.data.AppPreferences.DEFAULT_GEAR_RATIOS.forEach {
            assertEquals(it, parseGearValue(it.toString(), GearLimits.GEAR_RATIO, allowZero = true)!!, 0.0001f)
        }
    }

    @Test
    fun `the defaults of the other fields are inside their ranges`() {
        assertEquals(de.adv990de.obd2.data.AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM,
            parseGearValue(de.adv990de.obd2.data.AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM.toString(), GearLimits.WHEEL_CIRCUMFERENCE_MM)!!, 0.001f)
        assertEquals(de.adv990de.obd2.data.AppPreferences.DEFAULT_PRIMARY_RATIO,
            parseGearValue(de.adv990de.obd2.data.AppPreferences.DEFAULT_PRIMARY_RATIO.toString(), GearLimits.PRIMARY_RATIO)!!, 0.0001f)
        assertEquals(de.adv990de.obd2.data.AppPreferences.DEFAULT_SECONDARY_RATIO,
            parseGearValue(de.adv990de.obd2.data.AppPreferences.DEFAULT_SECONDARY_RATIO.toString(), GearLimits.SECONDARY_RATIO)!!, 0.0001f)
    }

    @Test
    fun `the limits are shown without a trailing decimal for whole numbers`() {
        assertEquals("1000", formatLimit(1000f))
        assertEquals("0.5", formatLimit(0.5f))
        assertEquals("0.2", formatLimit(0.2f))
    }
}
