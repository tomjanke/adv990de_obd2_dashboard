package de.adv990de.obd2.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Die Eingabe "Reserve" der Tankanzeige: ganze Kilometer von 5 bis 500, leer = aus (wie die Reichweite, nur mit anderen Grenzen). */
class FuelReserveInputTest {

    @Test
    fun `an empty or blank field switches the reserve off`() {
        assertEquals(FuelRangeInput.Cleared, parseFuelReserve(""))
        assertEquals(FuelRangeInput.Cleared, parseFuelReserve("   "))
    }

    @Test
    fun `a whole number in the limits is valid`() {
        assertEquals(FuelRangeInput.Valid(40), parseFuelReserve("40"))
        assertEquals(FuelRangeInput.Valid(5), parseFuelReserve("5"))
        assertEquals(FuelRangeInput.Valid(500), parseFuelReserve("500"))
        assertEquals(FuelRangeInput.Valid(40), parseFuelReserve(" 40 "))
    }

    @Test
    fun `a number outside 5 to 500 is invalid`() {
        for (bad in listOf("0", "4", "501", "1000")) {
            assertEquals("$bad", FuelRangeInput.Invalid, parseFuelReserve(bad))
        }
    }

    @Test
    fun `anything but ascii digits is invalid`() {
        for (bad in listOf("40,5", "40.5", "-40", "40 km", "abc", "４０", "4 0")) {
            assertEquals(bad, FuelRangeInput.Invalid, parseFuelReserve(bad))
        }
    }

    @Test
    fun `a long run of digits is invalid and does not overflow`() {
        assertEquals(FuelRangeInput.Invalid, parseFuelReserve("0040"))
        assertEquals(FuelRangeInput.Invalid, parseFuelReserve("99999999999999999999"))
    }

    @Test
    fun `the range input keeps its own limits`() {
        assertEquals(FuelRangeInput.Invalid, parseFuelRange("49"))
        assertEquals(FuelRangeInput.Valid(50), parseFuelRange("50"))
        assertEquals(FuelRangeInput.Valid(1000), parseFuelRange("1000"))
        assertEquals(FuelRangeInput.Invalid, parseFuelRange("1001"))
    }
}
