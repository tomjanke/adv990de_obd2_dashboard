package de.adv990de.obd2.alarm

import de.adv990de.obd2.withDefaultLocale
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Die Zahl bzw. die Codes eines Alarms als Text — die eine Stelle, aus der `alarmMessage` den Platzhalter füllt und das Alarmband die Stelle für die Zahlenschrift findet.
 * Die Zahlen gehen durch `formatSensorNumber`: Volt mit einer Nachkommastelle, sonst ganze Zahl, Schreibweise nach der Locale des Geräts.
 */
class AlarmValueTextTest {

    @Test
    fun `a voltage has one decimal and the decimal separator of the locale`() {
        val alarm = Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.34f)

        assertEquals("12,3", withDefaultLocale(Locale.GERMANY) { alarmValueText(alarm) })
        assertEquals("12.3", withDefaultLocale(Locale.US) { alarmValueText(alarm) })
    }

    @Test
    fun `a coolant temperature is a whole number`() {
        val alarm = Alarm(AlarmKind.COOLANT, AlarmSeverity.WARNING, value = 97.4f)

        assertEquals("97", alarmValueText(alarm))
    }

    @Test
    fun `fault codes are listed with a comma and a space, in the order of the alarm`() {
        val alarm = Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.NOTICE, codes = listOf("P0301", "P0420"))

        assertEquals("P0301, P0420", alarmValueText(alarm))
    }

    @Test
    fun `a missing or not finite value is dashes, never an invented number`() {
        assertEquals("--", alarmValueText(Alarm(AlarmKind.VOLTAGE, AlarmSeverity.NOTICE, value = null)))
        assertEquals("--", alarmValueText(Alarm(AlarmKind.COOLANT, AlarmSeverity.NOTICE, value = Float.NaN)))
        assertEquals("--", alarmValueText(Alarm(AlarmKind.COOLANT, AlarmSeverity.NOTICE, value = Float.POSITIVE_INFINITY)))
    }
}
