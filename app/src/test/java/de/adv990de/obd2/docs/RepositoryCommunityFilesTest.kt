package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Dateien, die ein oeffentliches Repository fuer Fremde braucht (Backlog A10, 2026-11-03): `SECURITY.md`, `CONTRIBUTING.md`, ein Workflow fuer die JVM-Tests und die
 * Konfiguration von Dependabot. Entscheidungen des Nutzers: Sicherheitsmeldungen ueber die private Meldung von GitHub (keine E-Mail-Adresse im Repository), Pull Requests nur
 * nach Absprache in einem Issue, ein Workflow mit Aktionen auf festen Commit-SHAs. Der Test haelt die Regeln fest, die sich nicht von selbst verstehen — vor allem die
 * Lieferkette des Workflows (ein Versions-Tag laesst sich verschieben, ein Commit nicht).
 */
class RepositoryCommunityFilesTest {

    private fun file(path: String) = File(projectRoot, path)
    private fun read(path: String): String {
        val f = file(path)
        assertTrue("$path fehlt", f.isFile)
        return f.readText()
    }

    private val emailAddress = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+""")
    private val workflowPath = ".github/workflows/jvm-tests.yml"

    @Test
    fun `SECURITY md sends reports to the private reporting of GitHub in both languages and names no mail address`() {
        val text = read("SECURITY.md")
        assertTrue("SECURITY.md nennt die private Meldung (Report a vulnerability) nicht in beiden Sprachen", Regex("Report a vulnerability").findAll(text).count() >= 2)
        assertTrue("SECURITY.md sagt nicht, dass keine oeffentlichen Issues fuer Luecken gedacht sind (Englisch)", text.contains("do not open a public issue"))
        assertTrue("SECURITY.md sagt nicht, dass keine oeffentlichen Issues fuer Luecken gedacht sind (Deutsch)", text.contains("kein öffentliches Issue"))
        assertFalse("SECURITY.md nennt eine E-Mail-Adresse — Entscheidung des Nutzers war die private GitHub-Meldung", emailAddress.containsMatchIn(text))
        assertTrue("SECURITY.md nennt nicht, dass die App nie ins Fahrzeug schreibt", text.contains("never writes to the vehicle") && text.contains("schreibt nie ins Fahrzeug"))
    }

    @Test
    fun `SECURITY md points to the certificate fingerprint that exists`() {
        val text = read("SECURITY.md")
        assertTrue(text.contains("scripts/release-cert.sha256"))
        assertTrue("scripts/release-cert.sha256 fehlt, SECURITY.md verweist darauf", file("scripts/release-cert.sha256").isFile)
    }

    @Test
    fun `CONTRIBUTING md asks for agreement before a pull request and points to files that exist`() {
        val text = read("CONTRIBUTING.md")
        assertTrue("CONTRIBUTING.md verlangt keine Absprache vor einem Pull Request (Englisch)", text.contains("Pull requests only after we agreed on the change in an issue"))
        assertTrue("CONTRIBUTING.md verlangt keine Absprache vor einem Pull Request (Deutsch)", text.contains("Pull Requests nur, nachdem wir die Änderung in einem Issue abgesprochen haben"))
        assertFalse("CONTRIBUTING.md nennt eine E-Mail-Adresse", emailAddress.containsMatchIn(text))
        for (target in listOf("SECURITY.md", "LICENSE", "CLAUDE.md")) {
            assertTrue("CONTRIBUTING.md verweist auf $target", text.contains("]($target)"))
            assertTrue("$target fehlt, CONTRIBUTING.md verweist darauf", file(target).isFile)
        }
        assertTrue("CONTRIBUTING.md nennt die Lizenz der Beitraege nicht", text.contains("Apache License 2.0"))
        // Die Abschnitte von CLAUDE.md, die CONTRIBUTING.md nennt, gibt es.
        val claude = read("CLAUDE.md")
        for (heading in listOf("## Code-Konventionen", "### Teststufen", "### Build-Integrität")) {
            assertTrue("CLAUDE.md hat den Abschnitt '$heading' nicht, CONTRIBUTING.md nennt ihn", claude.contains("\n$heading"))
        }
    }

    @Test
    fun `the README points to both files and to the workflow`() {
        val readme = read("README.md")
        assertTrue(readme.contains("[`CONTRIBUTING.md`](CONTRIBUTING.md)"))
        assertTrue(readme.contains("[`SECURITY.md`](SECURITY.md)"))
        assertTrue("README nennt den Workflow nicht", readme.contains(workflowPath))
        assertTrue("$workflowPath fehlt", file(workflowPath).isFile)
    }

    @Test
    fun `every action in the workflow stands on a full commit SHA with a version comment`() {
        val uses = read(workflowPath).lines().filter { it.trim().startsWith("- uses:") || it.trim().startsWith("uses:") }
        assertTrue("Der Workflow nutzt keine Aktion?", uses.isNotEmpty())
        for (line in uses) {
            assertTrue("Aktion nicht auf einer vollen Commit-SHA (ein Tag laesst sich verschieben): $line", Regex("""uses:\s*[\w.-]+/[\w./-]+@[0-9a-f]{40}(\s|$)""").containsMatchIn(line))
            assertTrue("Der Versionskommentar fehlt (Dependabot und der Leser brauchen ihn): $line", Regex("""#\s*v\d+\.\d+\.\d+\s*$""").containsMatchIn(line))
        }
    }

    @Test
    fun `the workflow is read-only and never runs untrusted code with secrets`() {
        val text = read(workflowPath)
        val top = text.substringBefore("\njobs:")
        assertTrue("Der Workflow braucht oben 'permissions: contents: read'", Regex("""(?m)^permissions:\s*\n\s+contents:\s*read\s*$""").containsMatchIn(top))
        assertFalse("Ein Schreibrecht im Workflow", Regex(""":\s*write\b""").containsMatchIn(text))
        assertFalse("pull_request_target fuehrt Code aus Forks mit Rechten des Repositorys aus", text.contains("pull_request_target"))
        assertFalse("Der Workflow braucht keine Geheimnisse", text.contains("secrets."))
        assertTrue("Der Workflow laeuft nicht fuer Pull Requests", Regex("""(?m)^\s+pull_request:""").containsMatchIn(text))
        assertTrue("checkout soll die Zugangsdaten nicht im Arbeitsordner lassen", text.contains("persist-credentials: false"))
    }

    @Test
    fun `the workflow runs the JVM tests on the JDK the daemon criteria ask for`() {
        val text = read(workflowPath)
        assertTrue("Der Workflow fuehrt die JVM-Tests nicht aus", text.contains("./gradlew :app:testDebugUnitTest"))
        val criteria = read("gradle/gradle-daemon-jvm.properties").lines().filter { it.contains('=') && !it.startsWith("#") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val vendor = criteria["toolchainVendor"]
        val version = criteria["toolchainVersion"]
        assertEquals("JETBRAINS", vendor)
        // Gradle laedt kein JDK nach: setup-java muss genau das bereitstellen, was die Daemon-JVM-Kriterien verlangen.
        assertTrue("Der Workflow nutzt nicht die Distribution $vendor", text.contains("distribution: ${vendor!!.lowercase()}"))
        assertTrue("Der Workflow nutzt nicht Java $version", text.contains("java-version: \"$version\""))
    }

    @Test
    fun `Dependabot watches only the actions and never the gradle dependencies`() {
        val text = read(".github/dependabot.yml")
        assertTrue(Regex("""package-ecosystem:\s*github-actions""").containsMatchIn(text))
        // Jede Gradle-Abhaengigkeit steht mit SHA-256 in gradle/verification-metadata.xml; ein automatischer Vorschlag bricht den Build.
        assertFalse("Dependabot fuer gradle brache jeden Vorschlag zum Scheitern (Pruefsummen)", Regex("""package-ecosystem:\s*"?gradle""").containsMatchIn(text))
        assertTrue(file("gradle/verification-metadata.xml").isFile)
    }
}
