package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Das Abnahme-Skript fuer die laute Anzeige bei eingefrorenen Werten (`scripts/freeze_check.py`) liegt im Repository und laeuft nur am Geraet des Entwicklers — ein Skript, das nie laeuft,
 * verrottet unbemerkt. Dieser Test haelt es vorhanden und fuehrt die Python-Tests seiner reinen Teile aus (Zustand aus den Texten, Auswertung).
 */
class FreezeCheckScriptTest {

    private val script = File(projectRoot, "scripts/freeze_check.py")
    private val tests = File(projectRoot, "scripts/test_freeze_check.py")

    @Test
    fun `the check script and its tests are in the repository`() {
        assertTrue("scripts/freeze_check.py fehlt", script.isFile)
        assertTrue("scripts/test_freeze_check.py fehlt", tests.isFile)
    }

    @Test
    fun `the check script passes its own Python tests`() {
        val python = try {
            ProcessBuilder("python3", "-I", tests.absolutePath).redirectErrorStream(true).start()
        } catch (_: java.io.IOException) {
            null
        }
        assumeTrue("python3 nicht verfuegbar", python != null)
        val output = python!!.inputStream.bufferedReader().readText()
        assertTrue("test_freeze_check.py lief nicht in 60 s", python.waitFor(60, TimeUnit.SECONDS))
        assertEquals("Python-Tests des Abnahme-Skripts scheitern:\n$output", 0, python.exitValue())
    }

    @Test
    fun `the script names the build property that switches the freeze on`() {
        assertTrue(script.readText().contains("-PsimFreezeAfterSeconds="))
    }
}
