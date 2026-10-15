package de.adv990de.obd2.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Die Eingabe "Reichweite bei vollem Tank": ganze Kilometer von 50 bis 1000; leer loescht die Reichweite. */
class FuelRangeInputTest {

    @Test
    fun `an empty or blank field clears the range`() {
        assertEquals(FuelRangeInput.Cleared, parseFuelRange(""))
        assertEquals(FuelRangeInput.Cleared, parseFuelRange("   "))
    }

    @Test
    fun `a whole number inside the limits is valid`() {
        assertEquals(FuelRangeInput.Valid(320), parseFuelRange("320"))
        assertEquals(FuelRangeInput.Valid(320), parseFuelRange(" 320 "))
    }

    @Test
    fun `the limits 50 and 1000 are valid`() {
        assertEquals(FuelRangeInput.Valid(50), parseFuelRange("50"))
        assertEquals(FuelRangeInput.Valid(1000), parseFuelRange("1000"))
    }

    @Test
    fun `just outside the limits is invalid`() {
        assertEquals(FuelRangeInput.Invalid, parseFuelRange("49"))
        assertEquals(FuelRangeInput.Invalid, parseFuelRange("1001"))
        assertEquals(FuelRangeInput.Invalid, parseFuelRange("0"))
        assertEquals(FuelRangeInput.Invalid, parseFuelRange("5"))
    }

    @Test
    fun `leading zeros are read as the number`() {
        assertEquals(FuelRangeInput.Valid(80), parseFuelRange("080"))
    }

    @Test
    fun `decimals, signs, units and other characters are invalid`() {
        for (bad in listOf("320,5", "320.5", "-320", "+320", "320 km", "3 20", "abc", "٣٢٠", "1e3")) {
            assertEquals("'$bad'", FuelRangeInput.Invalid, parseFuelRange(bad))
        }
    }

    @Test
    fun `more than four digits is invalid even when it has leading zeros`() {
        assertEquals(FuelRangeInput.Invalid, parseFuelRange("00320"))
        assertEquals(FuelRangeInput.Invalid, parseFuelRange("99999999999999999999"))
    }
}
