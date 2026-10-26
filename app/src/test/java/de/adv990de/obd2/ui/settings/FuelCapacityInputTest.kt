package de.adv990de.obd2.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Die Eingabe "Tankinhalt" der Tankanzeige: Liter mit Komma oder Punkt und hoechstens einer Nachkommastelle, 3,0 bis 60,0 l (gespeichert in Zehntelliter), leer = loeschen.
 */
class FuelCapacityInputTest {

    @Test
    fun `an empty or blank field clears the tank content`() {
        assertEquals(FuelCapacityInput.Cleared, parseFuelCapacity(""))
        assertEquals(FuelCapacityInput.Cleared, parseFuelCapacity("   "))
    }

    @Test
    fun `whole liters and one decimal place with comma or point are valid`() {
        assertEquals(FuelCapacityInput.Valid(200), parseFuelCapacity("20"))
        assertEquals(FuelCapacityInput.Valid(200), parseFuelCapacity("20,0"))
        assertEquals(FuelCapacityInput.Valid(200), parseFuelCapacity("20.0"))
        assertEquals(FuelCapacityInput.Valid(205), parseFuelCapacity("20,5"))
        assertEquals(FuelCapacityInput.Valid(205), parseFuelCapacity("20.5"))
        assertEquals(FuelCapacityInput.Valid(205), parseFuelCapacity(" 20,5 "))
    }

    @Test
    fun `a trailing separator is accepted while typing`() {
        assertEquals(FuelCapacityInput.Valid(200), parseFuelCapacity("20,"))
        assertEquals(FuelCapacityInput.Valid(200), parseFuelCapacity("20."))
    }

    @Test
    fun `the limits 3,0 and 60,0 are valid`() {
        assertEquals(FuelCapacityInput.Valid(30), parseFuelCapacity("3"))
        assertEquals(FuelCapacityInput.Valid(30), parseFuelCapacity("3,0"))
        assertEquals(FuelCapacityInput.Valid(600), parseFuelCapacity("60"))
        assertEquals(FuelCapacityInput.Valid(600), parseFuelCapacity("60,0"))
    }

    @Test
    fun `a content outside 3,0 to 60,0 is invalid`() {
        for (bad in listOf("0", "0,5", "2,9", "2.9", "60,1", "61", "100", "1000")) {
            assertEquals(bad, FuelCapacityInput.Invalid, parseFuelCapacity(bad))
        }
    }

    @Test
    fun `two decimal places are invalid - a tenth of a liter is the resolution`() {
        assertEquals(FuelCapacityInput.Invalid, parseFuelCapacity("20,55"))
        assertEquals(FuelCapacityInput.Invalid, parseFuelCapacity("20.05"))
    }

    @Test
    fun `anything but digits and one separator is invalid`() {
        for (bad in listOf("abc", "-5", "+20", "20 l", "2 0", "1e1", "20,5,5", "20..5", ",5", ".5", "２０", "20,٥")) {
            assertEquals(bad, FuelCapacityInput.Invalid, parseFuelCapacity(bad))
        }
    }

    @Test
    fun `a huge number is invalid and does not overflow`() {
        assertEquals(FuelCapacityInput.Invalid, parseFuelCapacity("99999999999999999999"))
        assertEquals(FuelCapacityInput.Invalid, parseFuelCapacity("99999999999999999999,9"))
    }

    @Test
    fun `the stored tenths are shown with one decimal place in the language of the device`() {
        assertEquals("20,0", formatTankCapacity(200, Locale.GERMANY))
        assertEquals("20.0", formatTankCapacity(200, Locale.US))
        assertEquals("20,5", formatTankCapacity(205, Locale.GERMANY))
        assertEquals("3,0", formatTankCapacity(30, Locale.GERMANY))
        assertEquals("60,0", formatTankCapacity(600, Locale.GERMANY))
    }

    @Test
    fun `the shown text reads back as the same value`() {
        for (dl in listOf(30, 123, 200, 205, 599, 600)) {
            for (locale in listOf(Locale.GERMANY, Locale.US)) {
                assertEquals("$dl in $locale", FuelCapacityInput.Valid(dl), parseFuelCapacity(formatTankCapacity(dl, locale)))
            }
        }
    }
}
