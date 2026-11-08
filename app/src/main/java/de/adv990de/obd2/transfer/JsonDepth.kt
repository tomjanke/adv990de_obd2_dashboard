package de.adv990de.obd2.transfer

/** Hoechste erlaubte Klammertiefe in `settings.json` und `counters.json` (die Dateien haben zwei Ebenen). */
internal const val MAX_JSON_DEPTH = 16

/**
 * `false`, sobald die Verschachtelung von `{` und `[` ausserhalb von Zeichenketten [max] uebersteigt. `org.json` parst rekursiv ohne Grenze und wirft bei
 * tiefer Verschachtelung einen `StackOverflowError` (ein `Error`, den kein `catch (e: Exception)` faengt): ein Paket mit 100 000 `[` passt in die Groessengrenze
 * des Eintrags und beendete sonst die App. Zaehlt in einem Durchgang ohne Rekursion; bricht beim Ueberschreiten sofort ab.
 */
internal fun jsonDepthWithin(text: String, max: Int = MAX_JSON_DEPTH): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
        } else {
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > max) return false
                '}', ']' -> if (depth > 0) depth--
            }
        }
    }
    return true
}
