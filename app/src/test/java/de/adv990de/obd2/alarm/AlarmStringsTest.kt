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
        "alarm_severity_notice",
        "alarm_severity_warning",
        "notif_alarm_channel_name",
        "alarm_acknowledge_action",
        "alarm_banner_desc",
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
