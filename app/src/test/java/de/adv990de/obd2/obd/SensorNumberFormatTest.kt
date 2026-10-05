package de.adv990de.obd2.obd

import de.adv990de.obd2.withDefaultLocale
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * QS C / UID-14: `"%.0f".format(-0.4f)` ergibt "-0" — ein Vorzeichen vor nichts (kalte Aussentemperatur, Kuehlmittel
 * um den Gefrierpunkt, Kraftstoff-Trim). Drei Stellen formatierten gleich (Kachel, Slot-Auswahl, Aufzeichnungs-Liste);
 * jetzt eine, mit dieser Regel.
 */
class SensorNumberFormatTest {

    private fun us(value: Float, unit: String = "°C") = withDefaultLocale(Locale.US) { formatSensorNumber(value, unit) }

    @Test fun `a whole number is rounded half up`() {
        assertEquals("87", us(87.4f))
        assertEquals("88", us(87.5f))
        assertEquals("0", us(0f))
    }

    @Test fun `a small negative value shows 0, not -0`() {
        assertEquals("0", us(-0.4f))
        assertEquals("0", us(-0.49f))
        assertEquals("0", us(-0.0f))
    }

    @Test fun `a negative value that rounds away from zero keeps its sign`() {
        assertEquals("-1", us(-0.5f))
        assertEquals("-1", us(-0.6f))
        assertEquals("-13", us(-12.6f))
        assertEquals("-5", us(-5f))
    }

    @Test fun `volts have one decimal, in the locale of the device`() {
        assertEquals("14.2", withDefaultLocale(Locale.US) { formatSensorNumber(14.24f, "V") })
        assertEquals("14,2", withDefaultLocale(Locale.GERMANY) { formatSensorNumber(14.24f, "V") })
    }

    @Test fun `a tiny negative voltage shows 0 point 0, not -0 point 0`() {
        assertEquals("0.0", withDefaultLocale(Locale.US) { formatSensorNumber(-0.04f, "V") })
        assertEquals("0,0", withDefaultLocale(Locale.GERMANY) { formatSensorNumber(-0.04f, "V") })
        assertEquals("-0.1", withDefaultLocale(Locale.US) { formatSensorNumber(-0.06f, "V") })
    }

    @Test fun `the unit decides the precision, not the value`() {
        assertEquals("14", us(14.24f, "kPa"))
        assertEquals("14.2", us(14.24f, "V"))
    }
}
