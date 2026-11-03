package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Der Smoke-Test des Release-Ablaufs lag bis 1.24.11 nur im Scratchpad einer Sitzung und ging verloren. Jetzt steht er als
 * `scripts/smoke.py` im Repository. Dieser Test haelt fest, dass er da ist, uebersetzbar bleibt und im Release-Ablauf genannt wird —
 * ein Skript, das nie laeuft, verrottet sonst unbemerkt.
 */
class SmokeScriptTest {

    private val script = File(projectRoot, "scripts/smoke.py")

    @Test
    fun `the smoke script is in the repository and names what it checks`() {
        assertTrue("scripts/smoke.py fehlt", script.isFile)
        val text = script.readText()
        for (expected in listOf("ALL PASS", "App-Info", "Beenden", "isKeyguardShowing", "FATAL EXCEPTION")) {
            assertTrue("smoke.py nennt '$expected' nicht", expected in text)
        }
    }

    @Test
    fun `the smoke script is valid Python`() {
        val python = try {
            ProcessBuilder("python3", "-c", "import ast,sys; ast.parse(open(sys.argv[1]).read())", script.absolutePath)
                .redirectErrorStream(true).start()
        } catch (_: java.io.IOException) {
            null
        }
        assumeTrue("python3 nicht verfuegbar", python != null)
        val output = python!!.inputStream.bufferedReader().readText()
        assertTrue(python.waitFor(30, TimeUnit.SECONDS))
        assertEquals("Syntaxfehler in smoke.py: $output", 0, python.exitValue())
    }

    @Test
    fun `the decision logic of the smoke script passes its own Python tests`() {
        // Das Tippen und Warten laeuft nur am Geraet; was entscheidet (Verbindung, Werte, Zeile, Argumente), steht als reine Funktion
        // in smoke.py und ist hier ohne Geraet geprueft (scripts/test_smoke.py).
        val tests = File(projectRoot, "scripts/test_smoke.py")
        assertTrue("scripts/test_smoke.py fehlt", tests.isFile)
        val python = try {
            ProcessBuilder("python3", "-I", tests.absolutePath).redirectErrorStream(true).start()
        } catch (_: java.io.IOException) {
            null
        }
        assumeTrue("python3 nicht verfuegbar", python != null)
        val output = python!!.inputStream.bufferedReader().readText()
        assertTrue("test_smoke.py lief nicht in 60 s", python.waitFor(60, TimeUnit.SECONDS))
        assertEquals("Python-Tests der Smoke-Logik scheitern:\n$output", 0, python.exitValue())
    }

    @Test
    fun `the simulator mode is documented where people look for it`() {
        for (file in listOf("scripts/smoke.py") + ReadmeFiles.detailed) {
            assertTrue("$file nennt --simulator nicht", "--simulator" in File(projectRoot, file).readText())
        }
    }

    @Test
    fun `the release flow points to the script`() {
        for (path in ReadmeFiles.detailed) assertTrue("$path nennt scripts/smoke.py nicht", "scripts/smoke.py" in ReadmeFiles.read(path))
        assertTrue("release.sh nennt scripts/smoke.py nicht", "scripts/smoke.py" in File(projectRoot, "scripts/release.sh").readText())
    }
}
