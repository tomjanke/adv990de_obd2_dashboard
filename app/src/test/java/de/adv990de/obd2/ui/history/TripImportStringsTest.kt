package de.adv990de.obd2.ui.history

import de.adv990de.obd2.data.SESSION_RETENTION_MS
import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Zahl in den Texten des Fahrten-Imports (90 Tage) steht fest in den Ressourcen; dieser Test haelt sie an der Konstante fest, aus der der Import
 * rechnet — wer eine Grenze aendert, aendert sonst den Text nicht mit und die App sagt etwas Falsches.
 */
class TripImportStringsTest {

    private fun xml(folder: String) = projectRoot.resolve("app/src/main/res/$folder/strings.xml").readText()

    private fun pluralTextsOf(folder: String, name: String): List<String> {
        val block = Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(xml(folder))!!.groupValues[1]
        return Regex("""<item[^>]*>(.*?)</item>""").findAll(block).map { it.groupValues[1] }.toList()
    }

    private val folders = listOf("values", "values-de", "values-es")

    @Test
    fun `the retention named in the texts is the one the app deletes by`() {
        val days = (SESSION_RETENTION_MS / (24L * 60 * 60 * 1000)).toInt()
        for (folder in folders) {
            val items = pluralTextsOf(folder, "trip_import_expired")
            assertTrue("$folder: every form names $days days", items.isNotEmpty() && items.all { Regex("""\b$days\b""").containsMatchIn(it) })
        }
    }
}
