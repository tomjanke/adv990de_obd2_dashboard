package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.model.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Was die Gang-Box zeigt, steht als Zahl in der Aufzeichnung: 1-6 = Gang, 0 = "N", -1 = "-" (nicht bestimmbar).
 * Die Zahlen stehen als Wert in Datenbank und CSV — sie aendern sich nie (die Auswertung liest sie).
 */
class GearRecordingTest {

    @Test
    fun `a shown gear is recorded as its number`() {
        for (gear in 1..6) {
            assertEquals(gear.toFloat(), gearRecordValue(GearReading(gear = gear, isNeutral = false)), 0f)
        }
    }

    @Test
    fun `N is recorded as 0`() {
        assertEquals(0f, gearRecordValue(GearReading(gear = null, isNeutral = true)), 0f)
    }

    @Test
    fun `an undetermined gear (the dash) is recorded as -1, not left out`() {
        assertEquals(-1f, gearRecordValue(GearReading.Unknown), 0f)
    }

    @Test
    fun `N wins over a gear - the display never shows both`() {
        // GearTracker liefert bei "N" nie einen Gang; kaeme doch beides, gilt wie in der Gang-Box (gearBoxContent) "N".
        assertEquals(0f, gearRecordValue(GearReading(gear = 3, isNeutral = true)), 0f)
    }

    @Test
    fun `the recorded value carries the gear PID, the timestamp and the derived source`() {
        val value = gearSensorValue(GearReading(gear = 4, isNeutral = false), timestamp = 123_456L)

        assertEquals(DerivedPids.GEAR, value.pid)
        assertEquals(4f, value.value, 0f)
        assertEquals(123_456L, value.timestamp)
        assertEquals(ValueSource.DERIVED, value.source)
    }
}
