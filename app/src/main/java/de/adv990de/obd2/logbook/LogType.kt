package de.adv990de.obd2.logbook

/**
 * Die neun Eintragsarten des Logbuchs. **Die Namen sind persistierter Zustand** (Datenbankspalte `vehicle_log.type` und CSV-Spalte `type`) —
 * nie umbenennen, nie umsortieren ohne Not, keine Art mit anderer Bedeutung wiederverwenden (`LogTypeTest` haelt die Namen fest).
 *
 * [hasAmount]: Tanken, Oel und Kuehlfluessigkeit tragen eine Menge (in Millilitern gespeichert), die uebrigen Arten nicht.
 * [maxAmountMl]: die groesste zulaessige Menge einer Eintragung (100 l beim Tanken, 5 l bei Oel und Kuehlfluessigkeit); 0 ohne Menge.
 * [hasBrand]: nur der Reifenwechsel traegt ein Fabrikat (Pflicht), alle anderen Arten keines.
 *
 * Reifenwechsel und Kettenkit-Wechsel sind eigene Arten und **kein** Bezugspunkt fuer die Verbrauchsrechnung. Ob ein Reifenwechsel vorne, hinten oder
 * beide betrifft, steht im Fabrikat oder in der Notiz; ein eigenes Feld dafuer gibt es nicht.
 */
enum class LogType(val hasAmount: Boolean, val maxAmountMl: Int, val hasBrand: Boolean = false) {
    FUEL(hasAmount = true, maxAmountMl = 100_000),
    OIL(hasAmount = true, maxAmountMl = 5_000),
    COOLANT(hasAmount = true, maxAmountMl = 5_000),
    CHAIN_SMALL(hasAmount = false, maxAmountMl = 0),
    CHAIN_LARGE(hasAmount = false, maxAmountMl = 0),
    SERVICE_SMALL(hasAmount = false, maxAmountMl = 0),
    SERVICE_LARGE(hasAmount = false, maxAmountMl = 0),
    TIRE_CHANGE(hasAmount = false, maxAmountMl = 0, hasBrand = true),
    CHAIN_KIT_CHANGE(hasAmount = false, maxAmountMl = 0),
}
