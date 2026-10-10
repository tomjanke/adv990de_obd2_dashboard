package de.adv990de.obd2.docs

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

    private val projectRoot: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("settings.gradle.kts nicht gefunden")

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
}
