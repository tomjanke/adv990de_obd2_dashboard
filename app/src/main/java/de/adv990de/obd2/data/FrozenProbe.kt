package de.adv990de.obd2.data

import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.compactForLog
import de.adv990de.obd2.obd.model.ObdException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Die PIDs, die die Probe beim Erkennen eingefrorener Werte liest: Motorlast (`0104`), Drosselklappe (`0111`) und Saugrohrdruck (`010B`). Sie gehoeren zu keiner Kachel
 * und werden vom Zyklus nie abgefragt; das Steuergeraet am Motorrad des Nutzers meldet sie laut Discovery (Diagnose vom 2026-10-26), und sie veraendern sich im Fahren von selbst.
 */
internal val FROZEN_PROBE_PIDS = listOf("0104", "0111", "010B")

/** Der Abstand zwischen der ersten und der zweiten Lesung: lang genug, dass sich ein lebendiger Wert bewegen kann. */
internal const val FROZEN_PROBE_PAUSE_MS = 1_000L

/** Hoechstens so lange haelt die Probe das Neuverbinden auf (zwei Lesungen je PID dauern sonst rund 2 s; haengt der Adapter, sind es Zeitlimits von je 5 s). */
internal const val FROZEN_PROBE_BUDGET_MS = 4_000L

/**
 * Die Probe von Diagnose-Runde 3 (Nutzerauftrag 2026-11-02): liest jede der [pids] zweimal, [pauseMs] auseinander, und liefert EINE Zeile fuers App-Log —
 * "0104=41 04 2A -> 41 04 2B (moved); 0111=41 11 33 -> 41 11 33 (same)".
 *
 * **Warum zweimal:** eine einzelne Lesung sagt nichts ueber das Leben dahinter, erst der Vergleich. Bewegt sich ein Wert zwischen den Lesungen (`moved`), lebt das
 * Steuergeraet hinter dieser PID, waehrend die abgefragten stehen — dann haengt etwas an den PIDs des Zyklus (Zwischenspeicher im Adapter, oder das Steuergeraet
 * friert nur diese ein). Bleibt alles gleich (`same`), ist das Steuergeraet insgesamt eingefroren — oder die Sensoren stehen wirklich still (eine Sekunde im
 * Reiseflug: Drosselklappe und Last koennen auf dem Zahlenwert stehen); `same` ist deshalb nur ein schwacher Hinweis, `moved` ein starker. Ein Urteil trifft die
 * Zeile nicht, sie nennt, was sie gesehen hat.
 *
 * `unclear` heisst: keine der beiden Lesungen ist eine positive Antwort fuer diese PID (NO DATA, Zeitlimit, Echo eines anderen Befehls) oder die Probe kam nicht
 * ganz zum Ende. Ein zweimaliges "NO DATA" darf nicht als "gleich" gelten.
 *
 * Nur Modus-01-Lesungen, wie jeder Zyklus sie macht — nichts schreibt ins Fahrzeug. **Die Grenze [budgetMs] gilt auch in `NonCancellable`** (dort ruft die Pipeline sie),
 * was `withTimeoutOrNull` mit einem eigenen Job erreicht; was bis dahin gelesen war, steht in der Zeile, der Rest als "-".
 */
internal suspend fun probeUnusedPids(
    controller: ElmController,
    pids: List<String>,
    pauseMs: Long = FROZEN_PROBE_PAUSE_MS,
    budgetMs: Long = FROZEN_PROBE_BUDGET_MS,
): String {
    if (pids.isEmpty()) return "none"
    val first = mutableMapOf<String, String>()
    val second = mutableMapOf<String, String>()
    withTimeoutOrNull(budgetMs) {
        for (pid in pids) first[pid] = readForProbe(controller, pid)
        delay(pauseMs)
        for (pid in pids) second[pid] = readForProbe(controller, pid)
    }
    return pids.joinToString("; ") { pid -> probeLine(pid, first[pid], second[pid]) }
}

/** Eine Lesung fuer die Probe: die Antwort (auf eine Zeile und 40 Zeichen gekuerzt) oder "n/a (Name der Ausnahme)" — ein Zeitlimit oder Verlust bricht die Probe nicht ab. */
private suspend fun readForProbe(controller: ElmController, pid: String): String =
    try {
        controller.sendCommand(pid).compactForLog()
    } catch (e: ObdException) {
        "n/a (${e::class.simpleName})"
    }

private fun probeLine(pid: String, first: String?, second: String?): String {
    val verdict = when {
        first == null || second == null -> "unclear"
        !first.isPositiveAnswerFor(pid) || !second.isPositiveAnswerFor(pid) -> "unclear"
        first == second -> "same"
        else -> "moved"
    }
    return "$pid=${first ?: "-"} -> ${second ?: "-"} ($verdict)"
}

/** Eine positive Antwort auf "01xx" beginnt mit "41 xx" (nicht "NO DATA", nicht das verspaetete Echo eines anderen Befehls). */
private fun String.isPositiveAnswerFor(pid: String): Boolean = contains("41 ${pid.drop(2)}", ignoreCase = true)
