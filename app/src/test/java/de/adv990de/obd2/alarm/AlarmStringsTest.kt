package de.adv990de.obd2.alarm

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Texte der Alarme stehen in beiden Sprachen und sind wirklich übersetzt. Gelesen wird direkt aus den Ressourcendateien (wie `TerrainStringsTest`): Android
 * fällt bei einer fehlenden deutschen Zeichenkette still auf die englische zurück, ein Gerät mit deutscher Sprache zeigte den Alarm dann auf Englisch.
 */
class AlarmStringsTest {

    private fun resourceFile(locale: String): String = projectRoot.resolve("app/src/main/res/$locale/strings.xml").readText()

    private fun strings(locale: String): Map<String, String> =
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(resourceFile(locale)).associate { it.groupValues[1] to it.groupValues[2] }

    /** Menge je Plural-Name → Zeichenkette je `quantity`. */
    private fun plurals(locale: String): Map<String, Map<String, String>> =
        Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(resourceFile(locale)).associate { plural ->
                plural.groupValues[1] to Regex("""<item quantity="([^"]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(plural.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
            }

    private val alarmStrings = listOf(
        "alarm_voltage_low",
        "alarm_coolant_hot",
        "alarm_voltage_low_short",
        "alarm_coolant_hot_short",
        "alarm_severity_notice",
        "alarm_severity_warning",
        "notif_alarm_channel_name",
        "alarm_acknowledge_action",
        "alarm_banner_desc",
        // Einstellungsgruppe "Alarme" (Aufgabe 9)
        "settings_alarms_section",
        "settings_alarm_voltage_title",
        "settings_alarm_voltage_summary",
        "settings_alarm_coolant_title",
        "settings_alarm_coolant_summary",
        "settings_alarm_faults_title",
        "settings_alarm_faults_summary",
        "settings_alarm_voltage_notice",
        "settings_alarm_voltage_notice_desc",
        "settings_alarm_voltage_warning",
        "settings_alarm_voltage_warning_desc",
        "settings_alarm_coolant_notice",
        "settings_alarm_coolant_notice_desc",
        "settings_alarm_coolant_warning",
        "settings_alarm_coolant_warning_desc",
        "settings_alarm_notifications_hint",
        "settings_alarm_volts_a11y",
        "settings_alarm_celsius_a11y",
    )

    /** Texte ohne Wörter („+1“): in beiden Sprachen derselbe, aber in beiden vorhanden und gefüllt. */
    private val languageNeutralStrings = listOf("alarm_more_count")

    @Test
    fun `every alarm string exists in both languages`() {
        val de = strings("values-de")
        val en = strings("values")
        for (name in alarmStrings + languageNeutralStrings) {
            assertTrue("$name fehlt in values-de", name in de)
            assertTrue("$name fehlt in values", name in en)
        }
    }

    @Test
    fun `every German alarm string is filled and differs from the English one`() {
        val de = strings("values-de")
        val en = strings("values")
        for (name in alarmStrings) {
            assertTrue("$name ist deutsch leer", de.getValue(name).isNotBlank())
            assertNotEquals("$name ist nicht übersetzt", en.getValue(name), de.getValue(name))
        }
        for (name in languageNeutralStrings) {
            assertTrue("$name ist deutsch leer", de.getValue(name).isNotBlank())
            assertTrue("$name ist englisch leer", en.getValue(name).isNotBlank())
        }
    }

    @Test
    fun `the fault plural has one and other in both languages, filled and translated`() {
        val de = plurals("values-de").getValue("alarm_fault_new")
        val en = plurals("values").getValue("alarm_fault_new")
        for (quantity in listOf("one", "other")) {
            assertTrue("alarm_fault_new/$quantity fehlt in values-de", quantity in de)
            assertTrue("alarm_fault_new/$quantity fehlt in values", quantity in en)
            assertTrue("alarm_fault_new/$quantity ist deutsch leer", de.getValue(quantity).isNotBlank())
            assertNotEquals("alarm_fault_new/$quantity ist nicht übersetzt", en.getValue(quantity), de.getValue(quantity))
        }
    }

    @Test
    fun `the texts that take a value carry the placeholder in both languages`() {
        // Ohne %1$s ginge der Wert (Volt, °C, die Codes) beim Formatieren still verloren.
        for (locale in listOf("values", "values-de")) {
            val strings = strings(locale)
            for (name in listOf("alarm_voltage_low", "alarm_coolant_hot")) {
                assertTrue("$name in $locale ohne %1\$s", strings.getValue(name).contains("%1\$s"))
            }
            for ((quantity, text) in plurals(locale).getValue("alarm_fault_new")) {
                assertTrue("alarm_fault_new/$quantity in $locale ohne %1\$s", text.contains("%1\$s"))
            }
            // Das Band nennt Stufe (%1$s) und Text (%2$s) und der Mehr-Zähler die Zahl (%1$d): fehlt ein Platzhalter, ginge die Angabe still verloren.
            val description = strings.getValue("alarm_banner_desc")
            assertTrue("alarm_banner_desc in $locale ohne %1\$s", description.contains("%1\$s"))
            assertTrue("alarm_banner_desc in $locale ohne %2\$s", description.contains("%2\$s"))
            assertTrue("alarm_more_count in $locale ohne %1\$d", strings.getValue("alarm_more_count").contains("%1\$d"))
        }
    }

    @Test
    fun `the short texts for the screen reader carry no placeholder - they must not change with the number`() {
        // Das Band ist eine Assertive-Live-Region: aendert sich seine Beschreibung, sagt TalkBack sie neu an. Die Kurztexte tragen deshalb keinen Wert.
        for (locale in listOf("values", "values-de")) {
            val strings = strings(locale)
            for (name in listOf("alarm_voltage_low_short", "alarm_coolant_hot_short")) {
                assertTrue("$name in $locale hat einen Platzhalter", !strings.getValue(name).contains("%"))
            }
        }
    }

    @Test
    fun `the vocalised segment descriptions carry their value in both languages`() {
        // Das Segment zeigt nur "12,5" bzw. "95"; die Vorlese-Beschreibung nennt die Einheit. Ohne Platzhalter ginge der Wert beim Vorlesen verloren.
        for (locale in listOf("values", "values-de")) {
            val strings = strings(locale)
            assertTrue("settings_alarm_volts_a11y in $locale ohne %1\$s", strings.getValue("settings_alarm_volts_a11y").contains("%1\$s"))
            assertTrue("settings_alarm_celsius_a11y in $locale ohne %1\$s", strings.getValue("settings_alarm_celsius_a11y").contains("%1\$s"))
        }
    }

    @Test
    fun `the wording of the alarm settings is the agreed one`() {
        // Der Wortlaut der Einstellungsgruppe ist eine Produktentscheidung (Aufgabe 9); er steht hier fest, damit eine Umformulierung bewusst geschieht.
        val de = strings("values-de")
        assertEquals("Alarme", de.getValue("settings_alarms_section"))
        assertEquals("Spannungsalarm", de.getValue("settings_alarm_voltage_title"))
        assertEquals("Meldet eine zu niedrige Spannung bei laufendem Motor. Die Spannung wird ab der nächsten Verbindung mitgelesen.", de.getValue("settings_alarm_voltage_summary"))
        assertEquals("Kühlmittelalarm", de.getValue("settings_alarm_coolant_title"))
        assertEquals("Meldet ein zu heißes Kühlmittel bei laufendem Motor.", de.getValue("settings_alarm_coolant_summary"))
        assertEquals("Fehler unterwegs melden", de.getValue("settings_alarm_faults_title"))
        assertEquals("Liest die Fehlercodes alle 60 Sekunden und meldet neue.", de.getValue("settings_alarm_faults_summary"))
        assertEquals("Spannung: Hinweis unter", de.getValue("settings_alarm_voltage_notice"))
        assertEquals("Volt, gelb, nach 20 Sekunden", de.getValue("settings_alarm_voltage_notice_desc"))
        assertEquals("Spannung: Warnung unter", de.getValue("settings_alarm_voltage_warning"))
        assertEquals("Volt, rot, nach 10 Sekunden", de.getValue("settings_alarm_voltage_warning_desc"))
        assertEquals("Kühlmittel: Hinweis ab", de.getValue("settings_alarm_coolant_notice"))
        assertEquals("°C, gelb, nach 10 Sekunden", de.getValue("settings_alarm_coolant_notice_desc"))
        assertEquals("Kühlmittel: Warnung ab", de.getValue("settings_alarm_coolant_warning"))
        assertEquals("°C, rot, nach 5 Sekunden", de.getValue("settings_alarm_coolant_warning_desc"))
        assertEquals("Ohne Benachrichtigungen erscheinen Alarme nur im Dashboard.", de.getValue("settings_alarm_notifications_hint"))
        assertEquals("%1\$s Volt", de.getValue("settings_alarm_volts_a11y"))
        assertEquals("%1\$s Grad Celsius", de.getValue("settings_alarm_celsius_a11y"))
        val en = strings("values")
        assertEquals("Alarms", en.getValue("settings_alarms_section"))
        assertEquals("Voltage alarm", en.getValue("settings_alarm_voltage_title"))
        assertEquals("Reports a voltage that is too low while the engine is running. The voltage is read from the next connection on.", en.getValue("settings_alarm_voltage_summary"))
        assertEquals("Coolant alarm", en.getValue("settings_alarm_coolant_title"))
        assertEquals("Reports coolant that is too hot while the engine is running.", en.getValue("settings_alarm_coolant_summary"))
        assertEquals("Report faults while riding", en.getValue("settings_alarm_faults_title"))
        assertEquals("Reads the fault codes every 60 seconds and reports new ones.", en.getValue("settings_alarm_faults_summary"))
        assertEquals("Voltage: notice below", en.getValue("settings_alarm_voltage_notice"))
        assertEquals("Volts, yellow, after 20 seconds", en.getValue("settings_alarm_voltage_notice_desc"))
        assertEquals("Voltage: warning below", en.getValue("settings_alarm_voltage_warning"))
        assertEquals("Volts, red, after 10 seconds", en.getValue("settings_alarm_voltage_warning_desc"))
        assertEquals("Coolant: notice from", en.getValue("settings_alarm_coolant_notice"))
        assertEquals("°C, yellow, after 10 seconds", en.getValue("settings_alarm_coolant_notice_desc"))
        assertEquals("Coolant: warning from", en.getValue("settings_alarm_coolant_warning"))
        assertEquals("°C, red, after 5 seconds", en.getValue("settings_alarm_coolant_warning_desc"))
        assertEquals("Without notifications, alarms only appear in the dashboard.", en.getValue("settings_alarm_notifications_hint"))
        assertEquals("%1\$s volts", en.getValue("settings_alarm_volts_a11y"))
        assertEquals("%1\$s degrees Celsius", en.getValue("settings_alarm_celsius_a11y"))
    }

    @Test
    fun `the wording of the alarms is the agreed one`() {
        // Der Wortlaut ist eine Produktentscheidung (Spezifikation "Alarmband"); er steht hier fest, damit eine Umformulierung bewusst geschieht.
        val de = strings("values-de")
        assertEquals("Spannung niedrig: %1\$s V", de.getValue("alarm_voltage_low"))
        assertEquals("Kühlmittel heiß: %1\$s °C", de.getValue("alarm_coolant_hot"))
        assertEquals("Neuer Fehlercode: %1\$s", plurals("values-de").getValue("alarm_fault_new").getValue("one"))
        assertEquals("Neue Fehlercodes: %1\$s", plurals("values-de").getValue("alarm_fault_new").getValue("other"))
        assertEquals("Alarme", de.getValue("notif_alarm_channel_name"))
        assertEquals("Alarm quittieren", de.getValue("alarm_acknowledge_action"))
        assertEquals("+%1\$d", de.getValue("alarm_more_count"))
        assertEquals("%1\$s: %2\$s. Zum Quittieren tippen.", de.getValue("alarm_banner_desc"))
        val en = strings("values")
        assertEquals("Low voltage: %1\$s V", en.getValue("alarm_voltage_low"))
        assertEquals("Coolant hot: %1\$s °C", en.getValue("alarm_coolant_hot"))
        assertEquals("New fault code: %1\$s", plurals("values").getValue("alarm_fault_new").getValue("one"))
        assertEquals("New fault codes: %1\$s", plurals("values").getValue("alarm_fault_new").getValue("other"))
        assertEquals("Alarms", en.getValue("notif_alarm_channel_name"))
        assertEquals("Acknowledge alarm", en.getValue("alarm_acknowledge_action"))
        assertEquals("+%1\$d", en.getValue("alarm_more_count"))
        assertEquals("%1\$s: %2\$s. Tap to acknowledge.", en.getValue("alarm_banner_desc"))
    }
}
