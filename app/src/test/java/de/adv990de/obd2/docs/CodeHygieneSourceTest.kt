package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regeln, die sich nicht im Typsystem ausdruecken lassen, aber bei jeder Aenderung wieder verletzt werden koennten (QS-Pflege,
 * v1.25.0). Jede Regel nennt in ihrer Meldung, WAS zu tun ist — nicht nur, dass sie verletzt wurde.
 */
class CodeHygieneSourceTest {

    private val mainRoot = File(projectRoot, "app/src/main/java")
    private val mainSources = mainRoot.walkTopDown().filter { it.extension == "kt" }.toList()

    private fun relative(f: File) = f.relativeTo(projectRoot).path

    @Test
    fun `the parsers split on whitespace with one shared regex, not one compiled per response (PRO-15)`() {
        // `"\\s+".toRegex()` kompiliert das Muster bei JEDEM Aufruf neu — die Parser laufen je Messwert, bei der Live-Abfrage
        // mehrfach pro Sekunde. Die Konstante `WHITESPACE` (obd/Whitespace.kt) wird einmal gebaut.
        val perCall = Regex("""["']\\\\s\+["']\s*\.toRegex\(\)|Regex\(\s*["']\\\\s\+["']\s*\)""")
        val offenders = mainSources.filter { it.name != "Whitespace.kt" && perCall.containsMatchIn(it.readText()) }.map(::relative)
        assertTrue("Whitespace-Regex je Aufruf neu gebaut in $offenders - stattdessen `split(WHITESPACE)` aus obd/Whitespace.kt", offenders.isEmpty())
    }
}
