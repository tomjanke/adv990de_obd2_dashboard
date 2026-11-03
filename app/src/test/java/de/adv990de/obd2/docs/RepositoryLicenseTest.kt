package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Der eigene Quelltext steht unter der Apache License 2.0 (Entscheidung des Nutzers, 2026-11-03). GitHub erkennt die Lizenz an einer `LICENSE` im Wurzelverzeichnis;
 * die App liefert denselben Text aus `docs/licenses/` mit (Lizenzseite). Beide muessen derselbe Text bleiben, `NOTICE` nennt den Rechteinhaber, die READMEs die Lizenz.
 */
class RepositoryLicenseTest {

    private val license = File(projectRoot, "LICENSE")
    private val notice = File(projectRoot, "NOTICE")

    @Test
    fun `the LICENSE file is the Apache 2_0 text and the same one the app ships`() {
        assertTrue("LICENSE fehlt im Wurzelverzeichnis", license.isFile)
        val text = license.readText()
        assertTrue("LICENSE ist kein Apache-2.0-Text", text.contains("Apache License") && text.contains("Version 2.0, January 2004"))
        assertTrue("LICENSE ist nicht vollstaendig (Anhang mit dem Lizenzhinweis fehlt)", text.contains("APPENDIX: How to apply the Apache License to your work"))
        assertArrayEquals(
            "LICENSE weicht von docs/licenses/Apache-2.0.txt ab — beide muessen derselbe Text sein",
            File(projectRoot, "docs/licenses/Apache-2.0.txt").readBytes(),
            license.readBytes(),
        )
    }

    @Test
    fun `the NOTICE file names the copyright holder and the license`() {
        assertTrue("NOTICE fehlt im Wurzelverzeichnis", notice.isFile)
        val text = notice.readText()
        assertTrue("NOTICE nennt keine Copyright-Zeile mit Jahr und Namen", Regex("""Copyright \d{4} \S+""").containsMatchIn(text))
        assertTrue("NOTICE nennt die Lizenz nicht", text.contains("Apache License, Version 2.0"))
    }

    @Test
    fun `the README names the license and points to the files`() {
        // Die ausfuehrlichen Fassungen haben den Abschnitt (deutsch "Lizenz", englisch "License"); die Kurzfassung nennt die Lizenz in beiden Sprachen (ReadmeFilesTest).
        for ((path, heading) in listOf(ReadmeFiles.DE to "Lizenz", ReadmeFiles.EN to "License")) {
            val readme = ReadmeFiles.read(path)
            assertTrue("$path braucht den Abschnitt '## $heading'", readme.contains("\n## $heading\n"))
            val section = readme.substringAfter("\n## $heading\n").substringBefore("\n## ")
            assertTrue("Der Lizenz-Abschnitt von $path nennt die Apache License 2.0 nicht", section.contains("Apache License 2.0"))
            assertTrue("Der Lizenz-Abschnitt von $path nennt LICENSE nicht", section.contains("`LICENSE`"))
            assertTrue("Der Lizenz-Abschnitt von $path nennt NOTICE nicht", section.contains("`NOTICE`"))
        }
    }
}
