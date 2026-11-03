package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Die Batterie-Kennwerte je Fahrt (Idee F12) werden **nicht** in der App berechnet, sondern aus der exportierten CSV: der Nutzer exportiert ab und zu,
 * Claude rechnet dieselbe Auswertung auf den neuen Export und vergleicht sie mit der Basis vom 2026-10-19 (Entscheidungsprotokoll Punkt 58). Ein Trend ist
 * nur dann einer, wenn jede Auswertung dieselben Definitionen nutzt — deshalb steht die Rechnung als `scripts/battery_baseline.py` im Repository, mit Tests
 * (`scripts/test_battery_baseline.py`). Dieser Test hält fest, dass beides da ist, übersetzbar bleibt, im Gate mitläuft und die Basis dazu auffindbar ist
 * (ein Skript, das nie läuft, verrottet sonst unbemerkt).
 */
class BatteryBaselineScriptTest {

    private val script = File(projectRoot, "scripts/battery_baseline.py")
    private val tests = File(projectRoot, "scripts/test_battery_baseline.py")
    private val baseline = File(projectRoot, "docs/qa/2026-10-19-battery-baseline.md")

    private fun python(vararg args: String): Process? = try {
        ProcessBuilder("python3", *args).redirectErrorStream(true).start()
    } catch (_: java.io.IOException) {
        null
    }

    @Test
    fun `the script, its tests and the baseline are in the repository`() {
        assertTrue("scripts/battery_baseline.py fehlt", script.isFile)
        assertTrue("scripts/test_battery_baseline.py fehlt", tests.isFile)
        assertTrue("docs/qa/2026-10-19-battery-baseline.md fehlt", baseline.isFile)
    }

    @Test
    fun `the script names every definition and the baseline names how to run it`() {
        val text = script.readText()
        for (expected in listOf(
            "CYCLE_GAP_MS", "RUNNING_MIN_RPM", "REST_EXCLUDE_BEFORE_START_MS", "DIP_WINDOW_MS", "DIP_MIN_DEPTH_V", "RUN_SETTLE_MS",
            "STOP_LATE_AT_MS", "STOP_LATE_TOLERANCE_MS", "Multimeter", "persoenliche Daten",
        )) {
            assertTrue("battery_baseline.py nennt '$expected' nicht", expected in text)
        }
        val doc = baseline.readText()
        assertTrue("die Basis nennt den Aufruf nicht", "python3 -I scripts/battery_baseline.py" in doc)
        assertTrue("die Basis nennt die Fahrten nicht", "Ids 45 bis 57" in doc)
    }

    @Test
    fun `the baseline carries no time of day of a trip`() {
        // Die Fahrtzeiten sind persoenliche Daten (wie bei den anderen Auswertungen unter docs/qa): die Basis nennt nur Daten, nie Uhrzeiten
        val clock = Regex("""\b([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?\b""")
        val hits = baseline.readLines().filter { clock.containsMatchIn(it) }
        assertTrue("die Basis enthaelt Uhrzeiten: $hits", hits.isEmpty())
    }

    @Test
    fun `the script is valid Python`() {
        val python = python("-c", "import ast,sys; ast.parse(open(sys.argv[1]).read())", script.absolutePath)
        assumeTrue("python3 nicht verfuegbar", python != null)
        val output = python!!.inputStream.bufferedReader().readText()
        assertTrue(python.waitFor(30, TimeUnit.SECONDS))
        assertEquals("Syntaxfehler in battery_baseline.py: $output", 0, python.exitValue())
    }

    @Test
    fun `the definitions of the script pass their own Python tests`() {
        val python = python("-I", tests.absolutePath)
        assumeTrue("python3 nicht verfuegbar", python != null)
        val output = python!!.inputStream.bufferedReader().readText()
        assertTrue("test_battery_baseline.py lief nicht in 60 s", python.waitFor(60, TimeUnit.SECONDS))
        assertEquals("Python-Tests der Batterie-Auswertung scheitern:\n$output", 0, python.exitValue())
    }

    @Test
    fun `the readme and the idea list point to the script`() {
        for (path in ReadmeFiles.detailed) {
            assertTrue("$path nennt scripts/battery_baseline.py nicht", "scripts/battery_baseline.py" in ReadmeFiles.read(path))
        }
        val ideas = File(projectRoot, "docs/ideas/2026-10-17-adventure-enduro-funktionsideen.md").readText()
        assertTrue("die Ideenliste nennt scripts/battery_baseline.py nicht", "scripts/battery_baseline.py" in ideas)
    }
}
