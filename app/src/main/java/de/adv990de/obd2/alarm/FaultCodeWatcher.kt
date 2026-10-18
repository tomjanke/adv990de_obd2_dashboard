package de.adv990de.obd2.alarm

import de.adv990de.obd2.obd.model.DtcEntry

internal data class FaultFinding(val code: String, val isPending: Boolean)

/**
 * Erkennt neue Fehlercodes unterwegs. Die **Basis** (die Codes beim Verbinden, oder — scheiterte das Lesen — das erste erfolgreiche spätere Auslesen) gilt als bekannt. Ein Code ist
 * erst dann ein Fund, wenn er in **zwei aufeinanderfolgenden** erfolgreichen Auslesungen als neu auftaucht (Schlüssel: Code und Zustand): A9 Teil 2 (Mehrframe-Antworten) ist offen, eine
 * falsch gelesene Antwort darf keinen Alarm auslösen. Neu ist ein unbekannter Code oder ein als ausstehend bekannter, der jetzt bestätigt ist. Ein bekannter Code wird erst vergessen,
 * wenn er in **zwei aufeinanderfolgenden** erfolgreichen Auslesungen fehlt — ein einzelner Aussetzer macht einen alten Fehler nicht neu. Kehrt er danach zurück, ist er wieder neu.
 * Ein gescheitertes Auslesen (`null`) ist neutral; nur das Auslesen beim Verbinden setzt die Basis zurück, wenn es scheitert. Nur das Format `[PCBU][0-9A-F]{4}` zählt.
 */
internal class FaultCodeWatcher {
    private var hasBaseline = false
    private var known: Map<String, Boolean> = emptyMap()                    // Code -> ausstehend, als er bekannt wurde
    private var candidates: Set<Pair<String, Boolean>> = emptySet()         // beim letzten Auslesen neu
    private var missingOnce: Set<String> = emptySet()                       // bekannte Codes, die im letzten erfolgreichen Auslesen fehlten

    fun onRead(codes: List<DtcEntry>?, isBaseline: Boolean): List<FaultFinding> {
        if (codes == null) {
            // Ein gescheitertes Auslesen beim Verbinden: was die vorige Verbindung wusste, gilt nicht mehr. Unterwegs ist es neutral.
            if (isBaseline) reset()
            return emptyList()
        }
        val current = codes.filter { CODE_FORMAT.matches(it.code) }.distinctBy { it.code }.associate { it.code to it.isPending }
        if (isBaseline || !hasBaseline) {
            known = current
            candidates = emptySet()
            missingOnce = emptySet()
            hasBaseline = true
            return emptyList()
        }
        val isNew = current.filter { (code, pending) -> known[code]?.let { wasPending -> wasPending && !pending } ?: true }
            .map { (code, pending) -> code to pending }
            .toSet()
        val confirmed = isNew.intersect(candidates)
        candidates = isNew - confirmed
        // Ein bekannter Code, der fehlt, wird erst beim zweiten Fehlen in Folge vergessen. Ein Code, der wieder da ist, verliert seinen Zähler (er fehlt nicht mehr).
        val absent = known.keys - current.keys
        val forgotten = absent.intersect(missingOnce)
        missingOnce = absent - missingOnce
        known = known.filterKeys { it !in forgotten } + confirmed.associate { it.first to it.second }
        return confirmed.sortedBy { it.first }.map { FaultFinding(it.first, it.second) }
    }

    fun reset() {
        hasBaseline = false
        known = emptyMap()
        candidates = emptySet()
        missingOnce = emptySet()
    }

    private companion object {
        val CODE_FORMAT = Regex("[PCBU][0-9A-F]{4}")
    }
}
