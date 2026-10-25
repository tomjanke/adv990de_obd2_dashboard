package de.adv990de.obd2.docs

import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.parseLogbookCsv
import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Der einmalige Konverter fuer den Altbestand (`scripts/logbook_convert.py`) liegt im Repository und laeuft nur auf dem Rechner des Entwicklers — ein Skript,
 * das nie laeuft, verrottet unbemerkt. Dieser Test fuehrt seine Python-Tests aus (erfundene Daten unter `app/src/test/resources/logbook/`) und liest die
 * erwartete Ausgabe mit dem **Leser der App**: so laufen Konverter und Import nicht auseinander.
 */
class LogbookConvertScriptTest {

    private val script = File(projectRoot, "scripts/logbook_convert.py")
    private val tests = File(projectRoot, "scripts/test_logbook_convert.py")
    private val fixtures = File(projectRoot, "app/src/test/resources/logbook")

    @Test
    fun `the converter and its tests are in the repository`() {
        assertTrue("scripts/logbook_convert.py fehlt", script.isFile)
        assertTrue("scripts/test_logbook_convert.py fehlt", tests.isFile)
        for (name in listOf("sample_fuel.csv", "sample_fluids.txt", "sample_expected.csv")) {
            assertTrue("$name fehlt", File(fixtures, name).isFile)
        }
    }

    @Test
    fun `the converter passes its own Python tests`() {
        val python = try {
            ProcessBuilder("python3", "-I", tests.absolutePath).redirectErrorStream(true).start()
        } catch (_: java.io.IOException) {
            null
        }
        assumeTrue("python3 nicht verfuegbar", python != null)
        val output = python!!.inputStream.bufferedReader().readText()
        assertTrue("test_logbook_convert.py lief nicht in 60 s", python.waitFor(60, TimeUnit.SECONDS))
        assertEquals("Python-Tests des Konverters scheitern:\n$output", 0, python.exitValue())
    }

    @Test
    fun `the app reads the converted sample without a single error`() {
        val parsed = parseLogbookCsv(File(fixtures, "sample_expected.csv").readText(), LocalDate.of(2026, 10, 20))
        assertEquals(emptyList<Any>(), parsed.errors)
        assertEquals(9, parsed.entries.size)
        val byType = parsed.entries.groupBy { it.entry.type }.mapValues { it.value.size }
        assertEquals(mapOf(LogType.FUEL to 4, LogType.OIL to 3, LogType.COOLANT to 2), byType)
        // die korrigierte Tankung (27500,00 -> 27,50 EUR) und die ohne Kosten
        val costs = parsed.entries.filter { it.entry.type == LogType.FUEL }.map { it.entry.costCents }
        assertEquals(listOf(2_000, 3_000, 2_750, null), costs)
        assertTrue("alle Tankungen sind voll", parsed.entries.filter { it.entry.type == LogType.FUEL }.all { it.entry.fullTank == true })
    }
}
