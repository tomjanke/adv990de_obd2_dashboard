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
 *
 * Nachtrag (2026-11-03, Auftrag des Nutzers "Issue-Vorlagen, Pull-Requestvorlage und Verhaltenskodex vorbereiten"): zwei Issue-Formulare, die Auswahl
 * (`config.yml`), die Pull-Request-Vorlage und `CODE_OF_CONDUCT.md`. Kein YAML-Parser in den JVM-Tests: die Formulare werden nach ihren Zeilen aufgeteilt;
 * ob GitHub sie fehlerfrei zeigt, sieht man erst auf `/issues/new/choose` nach dem Push (die Syntax ist lokal mit Ruby geprueft).
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
    private val bugFormPath = ".github/ISSUE_TEMPLATE/bug_report.yml"
    private val ideaFormPath = ".github/ISSUE_TEMPLATE/idea.yml"
    private val advisoryUrl = "https://github.com/tomjanke/adv990de_obd2_dashboard/security/advisories/new"

    /** "persönliche Daten" und "persönlichen Daten" (je nach Satzbau): beide Beugungen zaehlen. */
    private val germanPersonalData = Regex("""persönliche[n]? Daten""")

    private data class FormItem(val type: String, val id: String?, val required: Boolean)

    /** Die Elemente unter `body:` eines Issue-Formulars, nach den Zeilen `  - type: …` aufgeteilt. `required` gilt, wenn irgendwo im Element `required: true` steht. */
    private fun formItems(path: String): List<FormItem> {
        val body = read(path).substringAfter("\nbody:\n")
        return Regex("""(?m)^ {2}- type:""").split(body).drop(1).map { chunk ->
            FormItem(
                type = chunk.lineSequence().first().trim(),
                id = Regex("""(?m)^ {4}id:\s*(\S+)""").find(chunk)?.groupValues?.get(1),
                required = Regex("""(?m)^\s+required:\s*true\s*$""").containsMatchIn(chunk),
            )
        }
    }

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
    fun `the READMEs point to both files and the detailed ones to the workflow`() {
        // Die Kurzfassung liegt im Hauptverzeichnis, die ausfuehrlichen Fassungen in docs/ — ihre Links fuehren mit `../` hinaus.
        val short = read("README.md")
        assertTrue("README.md verweist nicht auf CONTRIBUTING.md", short.contains("](CONTRIBUTING.md)"))
        assertTrue("README.md verweist nicht auf SECURITY.md", short.contains("](SECURITY.md)"))
        for (path in ReadmeFiles.detailed) {
            val readme = read(path)
            assertTrue("$path verweist nicht auf CONTRIBUTING.md", readme.contains("[`CONTRIBUTING.md`](../CONTRIBUTING.md)"))
            assertTrue("$path verweist nicht auf SECURITY.md", readme.contains("[`SECURITY.md`](../SECURITY.md)"))
            assertTrue("$path nennt den Workflow nicht", readme.contains(workflowPath))
        }
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

    @Test
    fun `the issue forms have the shape GitHub needs and ask for what a report needs`() {
        for (path in listOf(bugFormPath, ideaFormPath)) {
            val text = read(path)
            for (key in listOf("name", "description", "labels")) {
                assertTrue("$path hat den Schluessel '$key:' oben nicht", Regex("""(?m)^$key:""").containsMatchIn(text))
            }
            assertTrue("$path hat kein 'body:'", text.contains("\nbody:\n"))
            val fields = formItems(path).filter { it.type != "markdown" }
            assertTrue("$path hat keine Eingabefelder", fields.isNotEmpty())
            assertTrue("In $path hat ein Feld keine id (GitHub braucht sie fuer die Antwort)", fields.all { it.id != null })
            assertEquals("In $path kommt eine id doppelt vor", fields.map { it.id }.distinct(), fields.map { it.id })
        }
        val bugRequired = formItems(bugFormPath).filter { it.required }.mapNotNull { it.id }.toSet()
        for (id in listOf("app_version", "android_device", "steps", "expected", "actual", "confirmations")) {
            assertTrue("Das Fehlerformular verlangt '$id' nicht (Pflichtfeld)", id in bugRequired)
        }
        // Wer keinen Adapternamen und kein Log nennen will, muss trotzdem melden koennen (Datenschutz: nichts erzwingen).
        for (id in listOf("adapter", "log")) assertFalse("'$id' darf kein Pflichtfeld sein (persoenliche Daten)", id in bugRequired)
        val ideaRequired = formItems(ideaFormPath).filter { it.required }.mapNotNull { it.id }.toSet()
        for (id in listOf("what", "why")) assertTrue("Das Ideenformular verlangt '$id' nicht", id in ideaRequired)
        assertTrue("Das Fehlerformular nutzt nicht das Label bug", read(bugFormPath).contains("labels: [\"bug\"]"))
        assertTrue("Das Ideenformular nutzt nicht das Label enhancement", read(ideaFormPath).contains("labels: [\"enhancement\"]"))
    }

    @Test
    fun `both forms warn about personal data in both languages and the bug form points to the private reporting`() {
        for (path in listOf(bugFormPath, ideaFormPath)) {
            val text = read(path)
            assertTrue("$path warnt nicht vor persoenlichen Daten (Englisch)", text.contains("personal data"))
            assertTrue("$path warnt nicht vor persoenlichen Daten (Deutsch)", germanPersonalData.containsMatchIn(text))
            assertTrue("$path nennt die FIN nicht (Englisch VIN, Deutsch FIN)", text.contains("VIN") && text.contains("FIN"))
            assertFalse("$path nennt eine E-Mail-Adresse", emailAddress.containsMatchIn(text))
        }
        val bug = read(bugFormPath)
        assertTrue("Das Fehlerformular verweist nicht auf die private Meldung", bug.contains(advisoryUrl))
        assertTrue("Das Fehlerformular verweist nicht auf SECURITY.md", bug.contains("SECURITY.md"))
    }

    @Test
    fun `the issue chooser leads security problems to the private reporting and still allows a question`() {
        val config = read(".github/ISSUE_TEMPLATE/config.yml")
        // Diskussionen sind aus: ohne ein leeres Issue haette eine Frage keinen Platz.
        assertTrue("Leere Issues muessen erlaubt bleiben (Fragen)", Regex("""(?m)^blank_issues_enabled:\s*true\s*$""").containsMatchIn(config))
        assertTrue("Die Auswahl fuehrt nicht zur privaten Meldung", config.contains("url: $advisoryUrl"))
        assertFalse("config.yml nennt eine E-Mail-Adresse", emailAddress.containsMatchIn(config))
    }

    @Test
    fun `the pull request template repeats the rules of CONTRIBUTING md as a bilingual checklist`() {
        val text = read(".github/pull_request_template.md")
        val items = text.lines().filter { it.startsWith("- [ ]") }
        assertTrue("Die Checkliste hat zu wenige Punkte: ${items.size}", items.size >= 6)
        assertTrue("Ein Punkt der Checkliste ist nicht zweisprachig (' / ' fehlt): ${items.filterNot { " / " in it }}", items.all { " / " in it })
        for (needle in listOf("agreed", "abgesprochen", "./gradlew :app:testDebugUnitTest", "values-es", "gradle/verification-metadata.xml", "personal data")) {
            assertTrue("Die Vorlage nennt '$needle' nicht", text.contains(needle))
        }
        assertTrue("Die Vorlage nennt persoenliche Daten nicht (Deutsch)", germanPersonalData.containsMatchIn(text))
        assertFalse("Die Vorlage nennt eine E-Mail-Adresse", emailAddress.containsMatchIn(text))
    }

    @Test
    fun `the code of conduct is in both languages, names no mail address and sends private reports to the tools of GitHub`() {
        val text = read("CODE_OF_CONDUCT.md")
        assertTrue("CODE_OF_CONDUCT.md hat den englischen Teil nicht", text.contains("\n## English\n"))
        assertTrue("CODE_OF_CONDUCT.md hat den deutschen Teil nicht", text.contains("\n## Deutsch\n"))
        assertFalse("CODE_OF_CONDUCT.md nennt eine E-Mail-Adresse — im Repository steht bewusst keine", emailAddress.containsMatchIn(text))
        assertTrue("Das Formular fuer Missbrauch ist nicht in beiden Sprachen genannt", text.split("https://github.com/contact/report-abuse").size - 1 >= 2)
        assertTrue("Der Text sagt nicht, dass eine Meldung bei GitHub nicht unbedingt beim Betreuer ankommt (Englisch)", text.contains("reach GitHub, not necessarily me"))
        assertTrue("Der Text sagt nicht, dass eine Meldung bei GitHub nicht unbedingt beim Betreuer ankommt (Deutsch)", text.contains("gehen an GitHub, nicht unbedingt an mich"))
        for (target in listOf("SECURITY.md", "CONTRIBUTING.md")) {
            assertTrue("CODE_OF_CONDUCT.md verweist nicht auf $target", text.contains("]($target)"))
            assertTrue("$target fehlt, CODE_OF_CONDUCT.md verweist darauf", file(target).isFile)
        }
    }

    @Test
    fun `the READMEs and CONTRIBUTING md point to the code of conduct, the forms and the template`() {
        assertTrue("README.md verweist nicht in beiden Sprachen auf CODE_OF_CONDUCT.md", read("README.md").split("](CODE_OF_CONDUCT.md)").size - 1 >= 2)
        for (path in ReadmeFiles.detailed) {
            val readme = read(path)
            assertTrue("$path verweist nicht auf CODE_OF_CONDUCT.md", readme.contains("[`CODE_OF_CONDUCT.md`](../CODE_OF_CONDUCT.md)"))
            assertTrue("$path nennt die Formulare nicht", readme.contains("ISSUE_TEMPLATE/"))
            assertTrue("$path nennt die Pull-Request-Vorlage nicht", readme.contains("pull_request_template.md"))
        }
        val contributing = read("CONTRIBUTING.md")
        assertTrue("CONTRIBUTING.md verweist nicht in beiden Sprachen auf den Verhaltenskodex", contributing.split("](CODE_OF_CONDUCT.md)").size - 1 >= 2)
        assertTrue("CODE_OF_CONDUCT.md fehlt", file("CODE_OF_CONDUCT.md").isFile)
    }
}
