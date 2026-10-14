package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.model.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Was der Oel-Balken zeigt, steht als Zahl in der Aufzeichnung: 0 = rot, 1 = gelb, 2 = gruen, -1 = grau (noch kein Kuehlmittelwert).
 * Die Zahlen stehen als Wert in Datenbank und CSV — sie aendern sich nie (die Auswertung liest sie).
 */
class OilBarRecordingTest {

    @Test
    fun `the three colours are recorded as 0, 1 and 2`() {
        assertEquals(0f, oilBarRecordValue(OilBarColor.Red), 0f)
        assertEquals(1f, oilBarRecordValue(OilBarColor.Yellow), 0f)
        assertEquals(2f, oilBarRecordValue(OilBarColor.Green), 0f)
    }

    @Test
    fun `a bar without a colour (no coolant value yet, grey) is recorded as -1, not left out`() {
        assertEquals(-1f, oilBarRecordValue(null), 0f)
    }

    @Test
    fun `every colour has its own number - none shares one with the grey`() {
        val numbers = OilBarColor.entries.map { oilBarRecordValue(it) } + oilBarRecordValue(null)

        assertEquals("jede Farbe eine eigene Zahl: $numbers", numbers.size, numbers.toSet().size)
    }

    @Test
    fun `the recorded value carries the oil bar PID, the timestamp and the derived source`() {
        val value = oilBarSensorValue(OilBarColor.Green, timestamp = 123_456L)

        assertEquals(DerivedPids.OIL_BAR, value.pid)
        assertEquals(2f, value.value, 0f)
        assertEquals(123_456L, value.timestamp)
        assertEquals(ValueSource.DERIVED, value.source)
    }
}
