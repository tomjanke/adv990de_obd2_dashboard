package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Was ein Rechner oder eine IDE ueber sich selbst schreibt, gehoert nicht ins Repository (QS SEC-11): 13 `.idea`-Dateien
 * waren getrackt, darunter `deploymentTargetSelector.xml` mit der Seriennummer eines Testgeraets. Die `.gitignore` nannte
 * einen Teil davon schon — ignorieren wirkt aber nur auf Dateien, die noch nicht im Index stehen.
 */
class RepositoryHygieneTest {

    private fun git(vararg args: String): List<String>? = try {
        val process = ProcessBuilder(listOf("git", *args)).directory(projectRoot).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readLines()
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) null else output
    } catch (_: java.io.IOException) {
        null
    }

    @Test
    fun `the gitignore ignores the whole IDE folder`() {
        val lines = File(projectRoot, ".gitignore").readLines().map { it.trim() }
        assertTrue(".idea/ fehlt in der .gitignore", ".idea/" in lines)
    }

    @Test
    fun `no IDE file is tracked by git`() {
        val tracked = git("ls-files", "--", ".idea")
        assumeTrue("kein Git-Repository oder kein git verfuegbar", tracked != null)
        assertEquals("getrackte IDE-Dateien: $tracked", emptyList<String>(), tracked)
    }

    @Test
    fun `no tracked file names a physical device serial`() {
        val hits = git("grep", "-n", "-I", "-E", "serial=[0-9A-Z]{8,}", "--", ".")
        assumeTrue("kein Git-Repository oder kein git verfuegbar", hits != null || git("rev-parse", "--git-dir") != null)
        assertEquals("Seriennummer in getrackten Dateien: $hits", emptyList<String>(), hits.orEmpty())
    }

    @Test
    fun `no tracked file names an absolute home directory of the build machine`() {
        // Der Pfad zum Home-Verzeichnis verraet den Benutzernamen des Rechners (macOS: /Users/<name>/, und als Ordnername
        // des Claude-Projektspeichers -Users-<name>-). Pläne und Doku nennen stattdessen <repo-root> und <memory>.
        // Die eigene Datei steht in der Ausnahme: sie enthaelt das Suchmuster.
        val hits = git(
            "grep", "-n", "-I", "-E", "/Users/[A-Za-z0-9._-]+/|-Users-[A-Za-z0-9]+-",
            "--", ".", ":(exclude)app/src/test/java/de/adv990de/obd2/docs/RepositoryHygieneTest.kt",
        )
        assumeTrue("kein Git-Repository oder kein git verfuegbar", hits != null || git("rev-parse", "--git-dir") != null)
        assertEquals("Absoluter Home-Pfad in getrackten Dateien: $hits", emptyList<String>(), hits.orEmpty())
    }

    // ── Interne Unterlagen (ausgelagert am 2026-11-03, CLAUDE.md „Interne Unterlagen“) ─────────────────────────────

    @Test
    fun `the internal plans and specifications are neither tracked nor committable by accident`() {
        val lines = File(projectRoot, ".gitignore").readLines().map { it.trim() }
        for (folder in listOf("docs/plans/", "docs/superpowers/")) {
            assertTrue("$folder fehlt in der .gitignore — neue Plaene (auch die der Superpowers-Skills) wuerden im oeffentlichen Repository landen", folder in lines)
        }
        val tracked = git("ls-files", "--", "docs/plans", "docs/superpowers")
        assumeTrue("kein Git-Repository oder kein git verfuegbar", tracked != null)
        assertEquals("getrackte interne Unterlagen: $tracked", emptyList<String>(), tracked)
    }

    @Test
    fun `docs qa holds only the two files that tests and a public script read`() {
        val tracked = git("ls-files", "--", "docs/qa")
        assumeTrue("kein Git-Repository oder kein git verfuegbar", tracked != null)
        assertEquals(
            "docs/qa darf nur coverage.md und die Batterie-Basis halten — Auswertungen von Fahrten und Entscheidungsprotokolle gehoeren in den internen Ordner (CLAUDE.md, \"Interne Unterlagen\")",
            listOf("docs/qa/2026-10-19-battery-baseline.md", "docs/qa/coverage.md"),
            tracked!!.sorted(),
        )
    }

    @Test
    fun `no tracked file points into the folders that moved out of the repository`() {
        // Verweise auf ausgelagerte Dokumente heissen <intern>/… . Die eigene Datei und die .gitignore stehen in der Ausnahme: sie nennen die alten Pfade.
        val moved = "docs/(plans|superpowers)/|docs/qa/(2026-10-04-qa-report|2026-10-12-decisions|2026-10-12-dtc-texte-gegenlesen|2026-10-13-gear-tolerances|2026-10-17-voltage-analysis|gear-analysis|voltage-analysis)"
        val hits = git(
            "grep", "-n", "-I", "-E", moved,
            "--", ".", ":(exclude)app/src/test/java/de/adv990de/obd2/docs/RepositoryHygieneTest.kt", ":(exclude).gitignore",
        )
        assumeTrue("kein Git-Repository oder kein git verfuegbar", hits != null || git("rev-parse", "--git-dir") != null)
        assertEquals("Verweise auf ausgelagerte Dokumente ohne <intern>: $hits", emptyList<String>(), hits.orEmpty())
    }
}
