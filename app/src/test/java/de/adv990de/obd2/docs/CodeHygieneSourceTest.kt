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

    @Test
    fun `the UI collects state with the lifecycle, not with collectAsState (SCR)`() {
        // `collectAsState` sammelt, solange die Komposition existiert — auch im Hintergrund, wo nichts mehr zu zeichnen ist. Die Oberflaeche
        // nimmt Zustand mit `collectAsStateWithLifecycle` (nur zwischen STARTED und STOPPED). Das ist nur zulaessig, weil KEIN ViewModel dieser
        // App fuer seine Arbeit auf einen Sammler der Oberflaeche angewiesen ist: Polling und Aufzeichnung laufen im viewModelScope bzw. im
        // @ApplicationScope, die Oberflaeche liest nur mit (s. CLAUDE.md "Zustand sammeln"). Wer Arbeit an `WhileSubscribed` haengt,
        // verlaesst sich auf einen Sammler — dort gehoert ein `Eagerly` bzw. ein eigener Scope hin, nicht ein Rueckfall auf `collectAsState`.
        val offenders = mainSources.filter { Regex("""\bcollectAsState\b""").containsMatchIn(it.readText().lines().filterNot { line -> line.trimStart().startsWith("*") || line.trimStart().startsWith("//") }.joinToString("\n")) }.map(::relative)
        assertTrue("collectAsState in $offenders - stattdessen collectAsStateWithLifecycle (androidx.lifecycle.compose)", offenders.isEmpty())
    }
}
