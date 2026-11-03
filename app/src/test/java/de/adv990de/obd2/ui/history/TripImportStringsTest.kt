package de.adv990de.obd2.ui.history

import de.adv990de.obd2.data.MAX_TRIP_IMPORT_BYTES
import de.adv990de.obd2.data.SESSION_RETENTION_MS
import de.adv990de.obd2.projectRoot
import de.adv990de.obd2.tripimport.MAX_TRIP_IMPORT_ROWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Zahlen in den Texten des Fahrten-Imports (90 Tage, 96 MB, 1,5 Millionen Zeilen) stehen fest in den Ressourcen; dieser Test haelt sie an den Konstanten fest, aus denen der Import
 * rechnet — wer eine Grenze aendert, aendert sonst den Text nicht mit und die App sagt etwas Falsches.
 */
class TripImportStringsTest {

    private fun xml(folder: String) = projectRoot.resolve("app/src/main/res/$folder/strings.xml").readText()

    private fun textOf(folder: String, name: String): String =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml(folder))!!.groupValues[1]

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

    @Test
    fun `the size limit named in the text is the one the import enforces`() {
        val mebibytes = MAX_TRIP_IMPORT_BYTES / (1024 * 1024)
        for (folder in folders) assertTrue("$folder names $mebibytes MB", textOf(folder, "trip_import_error_too_large").contains("$mebibytes MB"))
    }

    @Test
    fun `the row limit named in the text is the one the import enforces`() {
        assertEquals("the texts below spell out 1.5 million", 1_500_000, MAX_TRIP_IMPORT_ROWS)
        assertTrue(textOf("values", "trip_import_error_too_many_rows").contains("1,500,000"))
        assertTrue(textOf("values-de", "trip_import_error_too_many_rows").contains("1.500.000"))
        assertTrue(textOf("values-es", "trip_import_error_too_many_rows").contains("1 500 000"))
    }

    @Test
    fun `the error text about a wrong file names the three columns the import needs`() {
        for (folder in folders) {
            val text = textOf(folder, "trip_import_error_format")
            for (column in listOf("timestamp", "pid", "value")) assertTrue("$folder names $column", text.contains(column))
        }
    }
}
