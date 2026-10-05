package de.adv990de.obd2.docs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QS C / SEC-10: Zu jeder mitgelieferten Schrift liegt der Lizenztext im Repository (vorher fehlte der von Space
 * Grotesk, nur `IBMPlexMono-OFL.txt` war da). Die Schriftdateien tragen Urheberrecht und Lizenz zusaetzlich in ihrer
 * Namenstabelle; der Vermerk in `docs/licenses/` ist der lesbare.
 */
class LicenseFilesTest {

    private val projectRoot: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("settings.gradle.kts nicht gefunden")

    /** Schrift-Familie (Praefix der Dateien in `res/font`) -> erwartete Lizenzdatei. */
    private val licenseOfFamily = mapOf(
        "ibm_plex_mono" to "IBMPlexMono-OFL.txt",
        "space_grotesk" to "SpaceGrotesk-OFL.txt",
    )

    @Test
    fun `every bundled font family has its licence text`() {
        val families = File(projectRoot, "app/src/main/res/font").listFiles { f -> f.extension == "ttf" }!!
            .map { f -> licenseOfFamily.keys.firstOrNull { f.name.startsWith(it) } ?: error("Schrift ohne bekannte Familie: ${f.name} — Lizenz klaeren und hier eintragen") }
            .toSet()

        for (family in families) {
            val file = File(projectRoot, "docs/licenses/${licenseOfFamily.getValue(family)}")
            assertTrue("Lizenztext fehlt: ${file.relativeTo(projectRoot)}", file.isFile)
            assertTrue("kein OFL-Text in ${file.name}", file.readText().contains("SIL OPEN FONT LICENSE Version 1.1"))
        }
    }

    @Test
    fun `the Space Grotesk notice names the project authors as the font files do`() {
        val text = File(projectRoot, "docs/licenses/SpaceGrotesk-OFL.txt").readText()
        assertTrue(text.startsWith("Copyright 2020 The Space Grotesk Project Authors"))
    }
}
