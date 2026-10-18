package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.alarm.AlarmSettings
import de.adv990de.obd2.withDefaultLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Die reinen Entscheidungen der Einstellungsgruppe "Alarme": Beschriftung der Spannungs-Segmente und der Hinweis auf gesperrte Benachrichtigungen. */
class AlarmSettingsLogicTest {

    // --- Segmentbeschriftung der Spannung ---

    @Test
    fun `a voltage option is written with one decimal in the locale of the device`() {
        assertEquals("12,5", formatVoltageOption(125, Locale.GERMANY))
        assertEquals("12.5", formatVoltageOption(125, Locale.US))
    }

    @Test
    fun `every selectable voltage option is written exactly - also the ones ending in zero`() {
        val shown = (AlarmSettings.VOLTAGE_NOTICE_OPTIONS_DV + AlarmSettings.VOLTAGE_WARNING_OPTIONS_DV).map { formatVoltageOption(it, Locale.US) }
        // 12,0 behält seine Null: ohne feste Nachkommastelle stünde "12" im Segment und sähe aus wie eine ganze Zahl.
        assertEquals(listOf("12.3", "12.5", "12.8", "11.8", "12.0", "12.2"), shown)
    }

    @Test
    fun `the label follows the locale it is given, not the default one of the machine`() {
        withDefaultLocale(Locale.US) { assertEquals("12,5", formatVoltageOption(125, Locale.GERMANY)) }
        withDefaultLocale(Locale.GERMANY) { assertEquals("12.5", formatVoltageOption(125, Locale.US)) }
    }

    // --- Hinweis auf gesperrte Benachrichtigungen ---
    //
    // Keine SDK-Stufe in der Entscheidung: `NotificationManagerCompat.areNotificationsEnabled()` ist falsch, wenn ab Android 13 `POST_NOTIFICATIONS` fehlt
    // UND wenn Benachrichtigungen in den Systemeinstellungen gesperrt sind (auf jeder Stufe) — es gibt keinen Unterschied, den eine Stufe ergänzte.
    // Eine echte Entscheidung steckt dagegen im ZWEITEN Eingang: ohne eingeschalteten Alarm gibt es nichts zu melden, der Hinweis wäre Lärm.

    @Test
    fun `blocked notifications show the hint while any alarm is on`() {
        assertTrue(alarmNotificationHint(AlarmSettings(), notificationsEnabled = false))
    }

    @Test
    fun `enabled notifications never show the hint`() {
        assertFalse(alarmNotificationHint(AlarmSettings(), notificationsEnabled = true))
        assertFalse(alarmNotificationHint(AlarmSettings(voltageEnabled = false, coolantEnabled = false, faultsEnabled = false), notificationsEnabled = true))
    }

    @Test
    fun `each single alarm is enough to show the hint`() {
        val allOff = AlarmSettings(voltageEnabled = false, coolantEnabled = false, faultsEnabled = false)
        assertTrue(alarmNotificationHint(allOff.copy(voltageEnabled = true), notificationsEnabled = false))
        assertTrue(alarmNotificationHint(allOff.copy(coolantEnabled = true), notificationsEnabled = false))
        assertTrue(alarmNotificationHint(allOff.copy(faultsEnabled = true), notificationsEnabled = false))
    }

    @Test
    fun `with every alarm off there is nothing to notify - no hint even with blocked notifications`() {
        val allOff = AlarmSettings(voltageEnabled = false, coolantEnabled = false, faultsEnabled = false)
        assertFalse(alarmNotificationHint(allOff, notificationsEnabled = false))
    }
}
