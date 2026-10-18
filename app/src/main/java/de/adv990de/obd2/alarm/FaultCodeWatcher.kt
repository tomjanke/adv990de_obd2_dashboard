package de.adv990de.obd2.alarm

import de.adv990de.obd2.obd.model.DtcEntry

internal data class FaultFinding(val code: String, val isPending: Boolean)

/**
 * Erkennt neue Fehlercodes unterwegs. Die **Basis** (die Codes beim Verbinden, oder — scheiterte das Lesen — das erste erfolgreiche spätere Auslesen) gilt als bekannt. Ein Code ist
 * erst dann ein Fund, wenn er in **zwei aufeinanderfolgenden** erfolgreichen Auslesungen als neu auftaucht (Schlüssel: Code und Zustand): A9 Teil 2 (Mehrframe-Antworten) ist offen, eine
 * falsch gelesene Antwort darf keinen Alarm auslösen. Neu ist ein unbekannter Code oder ein als ausstehend bekannter, der jetzt bestätigt ist; ein bekannter Code, der aus einem Auslesen
 * verschwindet und wiederkehrt, ist wieder neu. Ein gescheitertes Auslesen (`null`) ändert nichts. Nur das Format `[PCBU][0-9A-F]{4}` zählt.
 */
internal class FaultCodeWatcher {
    private var hasBaseline = false
    private var known: Map<String, Boolean> = emptyMap()                    // Code -> ausstehend, als er bekannt wurde
    private var candidates: Set<Pair<String, Boolean>> = emptySet()         // beim letzten Auslesen neu

    fun onRead(codes: List<DtcEntry>?, isBaseline: Boolean): List<FaultFinding> {
        if (codes == null) return emptyList()
        val current = codes.filter { CODE_FORMAT.matches(it.code) }.distinctBy { it.code }.associate { it.code to it.isPending }
        if (isBaseline || !hasBaseline) {
            known = current
            candidates = emptySet()
            hasBaseline = true
            return emptyList()
        }
        val isNew = current.filter { (code, pending) -> known[code]?.let { wasPending -> wasPending && !pending } ?: true }
            .map { (code, pending) -> code to pending }
            .toSet()
        val confirmed = isNew.intersect(candidates)
        candidates = isNew - confirmed
        known = known.filterKeys { it in current } + confirmed.associate { it.first to it.second }
        return confirmed.sortedBy { it.first }.map { FaultFinding(it.first, it.second) }
    }

    fun reset() {
        hasBaseline = false
        known = emptyMap()
        candidates = emptySet()
    }

    private companion object {
        val CODE_FORMAT = Regex("[PCBU][0-9A-F]{4}")
    }
}
