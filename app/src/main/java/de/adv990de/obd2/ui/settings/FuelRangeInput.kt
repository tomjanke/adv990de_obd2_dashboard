package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.fuel.FuelRangeLimits

/** Das Ergebnis der Eingabe "Reichweite bei vollem Tank" (ganze Kilometer, [FuelRangeLimits]). */
internal sealed interface FuelRangeInput {
    /** Leeres Feld: die Reichweite wird geloescht, die Tankanzeige bleibt ausgeblendet. */
    data object Cleared : FuelRangeInput

    data class Valid(val km: Int) : FuelRangeInput

    /** Keine ganze Zahl oder ausserhalb von [FuelRangeLimits.MIN_KM] bis [FuelRangeLimits.MAX_KM]. */
    data object Invalid : FuelRangeInput
}

/**
 * Liest die Eingabe. Nur ASCII-Ziffern, hoechstens vier Stellen: keine Dezimalzahl ("320,5"), kein Vorzeichen, keine Einheit — die Reichweite ist
 * eine ganze Zahl in km. Reine Funktion, JVM-getestet.
 */
internal fun parseFuelRange(raw: String): FuelRangeInput {
    val text = raw.trim()
    if (text.isEmpty()) return FuelRangeInput.Cleared
    if (text.length > 4 || !text.all { it in '0'..'9' }) return FuelRangeInput.Invalid
    val km = text.toInt()
    return if (km in FuelRangeLimits.MIN_KM..FuelRangeLimits.MAX_KM) FuelRangeInput.Valid(km) else FuelRangeInput.Invalid
}
