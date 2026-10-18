package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Gelaende-Kachel braucht den GPS-Schalter ("Geschwindigkeit per GPS"), und die Einstellung muss das sagen (Nutzerwunsch 2026-10-18): wer den Schalter ausstellt,
 * soll wissen, dass die Kachel dann nichts zeigt. Gelesen wird direkt aus den Ressourcendateien — der Text steht in beiden Sprachen, und der Name der Kachel darin ist
 * der, den die Slot-Auswahl zeigt (`pid_terrain`), nicht ein zweites, abweichendes Wort.
 */
class TerrainStringsTest {

    private fun strings(locale: String): Map<String, String> {
        val xml = projectRoot.resolve("app/src/main/res/$locale/strings.xml").readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `the GPS speed setting names the terrain tile in German`() {
        val de = strings("values-de")
        assertTrue(de.getValue("settings_gps_speed_desc"), de.getValue("settings_gps_speed_desc").contains(de.getValue("pid_terrain")))
    }

    @Test
    fun `the GPS speed setting names the terrain tile in English`() {
        val en = strings("values")
        assertTrue(en.getValue("settings_gps_speed_desc"), en.getValue("settings_gps_speed_desc").contains(en.getValue("pid_terrain")))
    }

    @Test
    fun `the hint on an empty terrain slot points at the same setting`() {
        // Der Slot sagt "GPS-Geschwindigkeit in den Einstellungen einschalten": das muss der Name der Einstellung sein.
        assertEquals("Geschwindigkeit per GPS", strings("values-de").getValue("settings_gps_speed"))
        assertTrue(strings("values-de").getValue("dashboard_slot_terrain_gps_off").contains("GPS-Geschwindigkeit"))
    }
}
