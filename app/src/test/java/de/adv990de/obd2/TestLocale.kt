package de.adv990de.obd2

import java.util.Locale

/**
 * Fuehrt [block] mit einer festen Standard-Locale aus und stellt die alte danach wieder her.
 *
 * Produktionscode formatiert Zahlen fuer die Anzeige in der Locale des Geraets (`"%.1f".format(x)`:
 * Komma auf einem deutschen Geraet, Punkt auf einem englischen). Ein Test, der das Ergebnis mit einem festen
 * String vergleicht, haengt sonst von der Maschine ab, auf der er laeuft (B35): Er war unter `de_DE` rot, und
 * ein `contains(".")` haette dort nur zufaellig gehalten. Wer eine Anzeigeformatierung prueft, pinnt die
 * Locale deshalb hier — und prueft ruhig beide.
 */
internal inline fun <T> withDefaultLocale(locale: Locale, block: () -> T): T {
    val previous = Locale.getDefault()
    Locale.setDefault(locale)
    try {
        return block()
    } finally {
        Locale.setDefault(previous)
    }
}
