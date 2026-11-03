package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Der Name des Projekts (2026-11-03, Auftrag des Nutzers: "OBD2 Monitor" und "OBD2 diagnostic app" sollen "OBD Dashboard" heissen, und README und About sollen klar sagen, dass die App
 * in erster Linie fuer die KTM 990 Adventure des Nutzers geschrieben wurde, um Fahrt- und OBD-Daten zu zeigen). Der Name steht an drei Stellen, die zusammenbleiben muessen: in der
 * App (`app_name` in der App-Info, `app_label` im Launcher, beim Debug-Build mit dem Zusatz "Debug"), in den drei READMEs samt `NOTICE`, und im About-Text bei GitHub (den kann kein
 * Test lesen; er steht im Skript `publish-public.sh` des internen Ordners und in CLAUDE.md).
 *
 * Nicht umbenannt sind die Namen, die das System oder ein Dritter sich merkt: die Theme-Namen `Theme.OBD2Monitor…` (der System-Splash speichert sie app-weit), der Gradle-Projektname
 * `OBD2Monitor` und der User-Agent der Wetterabfrage `OBD2Monitor/<Version>` — sie sind Kennungen, nicht der Name, den ein Mensch liest.
 */
class ProjectNameTest {

    private val name = "OBD Dashboard"
    private val debugLabel = "$name Debug"
    private val oldNames = listOf("OBD2 Monitor", "OBD2 diagnostic", "Bike OBD2", "BOM Debug")

    private fun stringResource(path: String, key: String): String? =
        Regex("""<string name="$key"[^>]*>([^<]*)</string>""").find(File(projectRoot, path).readText())?.groupValues?.get(1)

    private fun section(text: String, heading: String): String = text.substringAfter("\n## $heading\n").substringBefore("\n## ")

    @Test
    fun `the app and its launcher carry the project name in every language`() {
        for (folder in listOf("values", "values-de", "values-es")) {
            val path = "app/src/main/res/$folder/strings.xml"
            assertEquals("app_name in $path", name, stringResource(path, "app_name"))
            assertEquals("app_label in $path", name, stringResource(path, "app_label"))
        }
        assertEquals("app_label des Debug-Builds", debugLabel, stringResource("app/src/debug/res/values/strings.xml", "app_label"))
    }

    @Test
    fun `the READMEs and NOTICE use the project name and none names an old one`() {
        for (path in ReadmeFiles.all) {
            val text = ReadmeFiles.read(path)
            assertTrue("$path beginnt nicht mit der Ueberschrift '# $name'", text.startsWith("# $name\n"))
            for (old in oldNames) assertFalse("$path nennt noch den alten Namen '$old'", text.contains(old))
        }
        val notice = File(projectRoot, "NOTICE").readText()
        assertEquals("NOTICE beginnt nicht mit dem Namen", name, notice.lineSequence().first())
        for (old in oldNames) assertFalse("NOTICE nennt noch den alten Namen '$old'", notice.contains(old))
    }

    @Test
    fun `the READMEs say what the app is for - the KTM 990 Adventure of its author`() {
        val short = ReadmeFiles.read(ReadmeFiles.SHORT)
        val parts = mapOf("englisch" to short.substringBefore("\n## Deutsch\n"), "deutsch" to short.substringAfter("\n## Deutsch\n"))
        for ((language, part) in parts) assertTrue("Der $language Teil von README.md nennt die KTM 990 Adventure nicht", part.contains("KTM 990 Adventure"))
        assertTrue("README.md (englisch) sagt nicht, dass es KTM nicht verbunden ist", parts.getValue("englisch").contains("Not affiliated with ELM Electronics, KTM"))
        assertTrue("README.md (deutsch) sagt nicht, dass es mit KTM nicht verbunden ist", parts.getValue("deutsch").contains("Keine Verbindung zu ELM Electronics, KTM"))

        for ((path, heading) in listOf(ReadmeFiles.DE to "Namen und Marken", ReadmeFiles.EN to "Names and trademarks")) {
            val text = ReadmeFiles.read(path)
            assertTrue("$path nennt die KTM 990 Adventure nicht", text.contains("KTM 990 Adventure"))
            val names = section(text, heading)
            assertTrue("Der Abschnitt '$heading' in $path nennt KTM nicht", names.contains("**KTM**"))
            assertTrue("Der Abschnitt '$heading' in $path nennt 990 Adventure nicht", names.contains("**990 Adventure**"))
        }
    }

    @Test
    fun `the launcher name of the debug build named in the READMEs is the real one`() {
        for (path in ReadmeFiles.detailed) assertTrue("$path nennt den Launcher-Namen '$debugLabel' des Debug-Builds nicht", ReadmeFiles.read(path).contains("**$debugLabel**"))
    }
}
