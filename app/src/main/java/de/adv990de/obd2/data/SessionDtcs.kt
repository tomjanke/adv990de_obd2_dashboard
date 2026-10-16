package de.adv990de.obd2.data

import de.adv990de.obd2.obd.model.DtcEntry

/**
 * Die Fehlercodes, die das Fahrzeug bei Beginn einer Fahrt gemeldet hat, als Text in `sessions.dtcCodes`.
 *
 * **Drei Zustaende, nicht zwei:** `null` = nicht erfasst (Fahrten vor Version 1.33.0, oder das Auslesen ist gescheitert),
 * leere Liste = gelesen, das Fahrzeug meldete nichts, sonst die Codes. Ein "keine Fehler" darf nie fuer ein "nicht erfasst"
 * stehen: das waere eine Entwarnung, die das Fahrzeug nie gegeben hat.
 *
 * **Das Format ist persistierter Zustand — nie aendern:** Codes durch `,` getrennt, ein ausstehender Code (Modus 07) traegt
 * ein angehaengtes `?`, z. B. `P0301,P0420?`. Ein Code, der bestaetigt UND ausstehend vorkommt, zaehlt als bestaetigt (wie in
 * der Fehlerhistorie), ein wiederholter Code steht einmal, die Reihenfolge des Fahrzeugs bleibt. Als Code gilt nur
 * `[PCBU][0-9A-F]{4}` (so erzeugt ihn `DtcParser`): was anders aussieht, wird beim Schreiben verworfen und beim Lesen
 * uebergangen — Aufzeichnen ist Beiwerk der Live-Anzeige, ein Formatfehler darf sie nie verhindern.
 */
private val DTC_CODE = Regex("[PCBU][0-9A-F]{4}")
private const val PENDING_MARK = "?"

/** Der Text fuer `sessions.dtcCodes`; `null` bleibt `null` (nicht erfasst). */
fun encodeSessionDtcs(dtcs: List<DtcEntry>?): String? {
    if (dtcs == null) return null
    // code -> ausstehend? Die erste Stelle bleibt, bestaetigt gewinnt gegen ausstehend.
    val pendingByCode = LinkedHashMap<String, Boolean>()
    for (dtc in dtcs) {
        if (!DTC_CODE.matches(dtc.code)) continue
        pendingByCode[dtc.code] = (pendingByCode[dtc.code] ?: true) && dtc.isPending
    }
    return pendingByCode.entries.joinToString(",") { (code, pending) -> if (pending) code + PENDING_MARK else code }
}

/** Die Codes aus `sessions.dtcCodes`; `null` = nicht erfasst, leere Liste = keine Codes. Uebergeht Ungueltiges, wirft nie. */
fun decodeSessionDtcs(text: String?): List<DtcEntry>? {
    if (text == null) return null
    return text.split(',').mapNotNull { token ->
        val trimmed = token.trim()
        val pending = trimmed.endsWith(PENDING_MARK)
        val code = if (pending) trimmed.dropLast(PENDING_MARK.length) else trimmed
        if (DTC_CODE.matches(code)) DtcEntry(code = code, isPending = pending) else null
    }
}
