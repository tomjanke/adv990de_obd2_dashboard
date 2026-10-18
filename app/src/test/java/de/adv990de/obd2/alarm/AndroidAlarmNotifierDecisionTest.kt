package de.adv990de.obd2.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Entscheidungen der Systembenachrichtigung, die ohne Gerät prüfbar sind. Das Posten selbst (Kanal, Text, Kategorie, Ersetzen, Antippen) prüft
 * `AndroidAlarmNotifierDeviceTest` am Pixel.
 */
class AndroidAlarmNotifierDecisionTest {

    // --- Zweig "Benachrichtigungen gesperrt" ---

    @Test
    fun `nothing is posted while notifications are blocked`() {
        // Der Zweig lässt sich am Gerät nicht erzwingen: die Berechtigung zur Laufzeit zu entziehen beendet den Testprozess.
        assertFalse(shouldPost(enabled = false))
    }

    @Test
    fun `an alarm is posted while notifications are enabled`() {
        assertTrue(shouldPost(enabled = true))
    }

    // --- Benachrichtigungs-ID je Art ---

    @Test
    fun `each alarm kind has its own fixed notification id`() {
        assertEquals(2001, notificationId(AlarmKind.VOLTAGE))
        assertEquals(2002, notificationId(AlarmKind.COOLANT))
        assertEquals(2003, notificationId(AlarmKind.FAULT_CODE))
    }

    @Test
    fun `the notification ids of the kinds are all different`() {
        // Gleiche Ids ließen eine Art die andere ersetzen: ein Fehler-Alarm löschte die Spannungswarnung aus der Leiste.
        val ids = AlarmKind.entries.map { notificationId(it) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the alarm channel id is the persisted one`() {
        // Der Kanal liegt beim Nutzer im System (Ton, Nicht stören): eine geänderte Id legte einen zweiten Kanal an und ließe seine Wahl zurück.
        assertEquals("obd2_alarms", ALARM_CHANNEL_ID)
    }
}
