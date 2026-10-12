package de.adv990de.obd2.obd

import de.adv990de.obd2.R
import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [dtcDescriptionRes] is the public, pure form of what used to be a private helper of [DtcParser]: `DtcEntry` and the fault history
 * store only the code and resolve the text when it is shown, so the description needs no database column and no Hilt binding. The
 * text is a string resource (it follows the device language); these tests keep the list and the resources from drifting apart.
 */
class DtcDescriptionTest {

    private val resDir = File(projectRoot, "app/src/main/res")

    /** `name -> text` of every `<string name="dtc_p....">` of one locale folder. */
    private fun dtcStrings(folder: String): Map<String, String> =
        Regex("""<string name="(dtc_[a-z0-9_]+)">([^<]*)</string>""")
            .findAll(File(resDir, "$folder/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
            .filterKeys { it.startsWith("dtc_p") || it == "dtc_unknown" }

    @Test
    fun `a known code resolves to its own string`() {
        assertEquals(R.string.dtc_p0301, dtcDescriptionRes("P0301"))
        assertEquals(R.string.dtc_p0420, dtcDescriptionRes("P0420"))
        assertEquals(R.string.dtc_p0730, dtcDescriptionRes("P0730"))
    }

    @Test
    fun `a code outside the known ones is reported as unknown`() {
        assertEquals(R.string.dtc_unknown, dtcDescriptionRes("P1234"))
        assertEquals(R.string.dtc_unknown, dtcDescriptionRes("U0100"))
        assertEquals(R.string.dtc_unknown, dtcDescriptionRes(""))
    }

    @Test
    fun `lookup is exact - no trimming and no case folding`() {
        assertEquals(R.string.dtc_unknown, dtcDescriptionRes("p0301"))
        assertEquals(R.string.dtc_unknown, dtcDescriptionRes(" P0301"))
    }

    @Test
    fun `the parser reads the code and the description follows from it`() {
        val parsed = DtcParser().parse("43 03 01", isPending = false).single()

        assertEquals("P0301", parsed.code)
        assertEquals(R.string.dtc_p0301, dtcDescriptionRes(parsed.code))
    }

    @Test
    fun `every description string is reachable from its code and no code points at a missing string`() {
        val names = dtcStrings("values").keys.filter { it != "dtc_unknown" }
        assertTrue("es gibt Beschreibungen", names.isNotEmpty())
        names.forEach { name ->
            val code = name.removePrefix("dtc_").uppercase()
            val id = R.string::class.java.getField(name).getInt(null)
            assertEquals("$code muss auf $name zeigen", id, dtcDescriptionRes(code))
        }
        // Umgekehrt: kein Code in der Funktion verweist auf etwas anderes als eine eigene Beschreibung (also nicht auf dtc_unknown).
        val source = File(projectRoot, "app/src/main/java/de/adv990de/obd2/obd/DtcDescriptions.kt").readText()
        val mapped = Regex(""""(P\d{4})" ->""").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals("die Liste der Codes und die Strings stimmen ueberein", names.map { it.removePrefix("dtc_").uppercase() }.sorted(), mapped.sorted())
    }

    @Test
    fun `every description exists in English and German, with the same names`() {
        val en = dtcStrings("values")
        val de = dtcStrings("values-de")

        assertEquals("gleiche Namen in beiden Sprachen", en.keys, de.keys)
        assertTrue("dtc_unknown ist uebersetzt", "dtc_unknown" in de)
        en.forEach { (name, english) ->
            val german = de.getValue(name)
            assertFalse("$name: der deutsche Text ist nicht leer", german.isBlank())
            assertFalse("$name: der deutsche Text ist nicht der englische", german == english)
        }
    }
}
