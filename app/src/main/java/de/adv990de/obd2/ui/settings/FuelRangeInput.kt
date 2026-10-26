package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.fuel.FuelCapacityLimits
import de.adv990de.obd2.fuel.FuelRangeLimits
import de.adv990de.obd2.fuel.FuelReserveLimits

/** Das Ergebnis der Eingabe "Reichweite bei vollem Tank" (ganze Kilometer, [FuelRangeLimits]) bzw. "Reserve" ([FuelReserveLimits]). */
internal sealed interface FuelRangeInput {
    /** Leeres Feld: die Reichweite wird geloescht, die Tankanzeige bleibt ausgeblendet. */
    data object Cleared : FuelRangeInput

    data class Valid(val km: Int) : FuelRangeInput

    /** Keine ganze Zahl oder ausserhalb von [FuelRangeLimits.MIN_KM] bis [FuelRangeLimits.MAX_KM]. */
    data object Invalid : FuelRangeInput
}

/**
 * Liest die Eingabe "Reichweite bei vollem Tank". Nur ASCII-Ziffern, hoechstens vier Stellen: keine Dezimalzahl ("320,5"), kein Vorzeichen, keine Einheit — die Reichweite ist
 * eine ganze Zahl in km. Reine Funktion, JVM-getestet.
 */
internal fun parseFuelRange(raw: String): FuelRangeInput =
    parseWholeKm(raw, FuelRangeLimits.MIN_KM, FuelRangeLimits.MAX_KM, maxDigits = 4)

/** Liest die Eingabe "Reserve" der Tankanzeige: ganze km von [FuelReserveLimits.MIN_KM] bis [FuelReserveLimits.MAX_KM], leer = aus (gleiche Form wie die Reichweite). */
internal fun parseFuelReserve(raw: String): FuelRangeInput =
    parseWholeKm(raw, FuelReserveLimits.MIN_KM, FuelReserveLimits.MAX_KM, maxDigits = 3)

private fun parseWholeKm(raw: String, min: Int, max: Int, maxDigits: Int): FuelRangeInput {
    val text = raw.trim()
    if (text.isEmpty()) return FuelRangeInput.Cleared
    if (text.length > maxDigits || !text.all { it in '0'..'9' }) return FuelRangeInput.Invalid
    val km = text.toInt()
    return if (km in min..max) FuelRangeInput.Valid(km) else FuelRangeInput.Invalid
}

/** Das Ergebnis der Eingabe "Tankinhalt" der Tankanzeige: Zehntelliter ([FuelCapacityLimits]). */
internal sealed interface FuelCapacityInput {
    /** Leeres Feld: der Tankinhalt wird geloescht, die Reichweite bleibt die manuelle. */
    data object Cleared : FuelCapacityInput

    data class Valid(val dl: Int) : FuelCapacityInput

    /** Keine Zahl, mehr als eine Nachkommastelle oder ausserhalb von 3,0 bis 60,0 Litern. */
    data object Invalid : FuelCapacityInput
}

/** Ziffern, hoechstens ein Trennzeichen (Punkt oder Komma) und danach hoechstens eine Ziffer: "20", "20,5", "20.5" und "20," (waehrend des Tippens). */
private val LITERS_ONE_DECIMAL = Regex("""\d+([.,]\d?)?""")

/**
 * Liest die Eingabe "Tankinhalt" in Litern (Punkt oder Komma, eine Nachkommastelle) und gibt Zehntelliter zurueck, 3,0 bis 60,0 l. Eine zu grosse Zahl wird abgelehnt statt zu ueberlaufen.
 * Reine Funktion, JVM-getestet.
 */
internal fun parseFuelCapacity(raw: String): FuelCapacityInput {
    val text = raw.trim()
    if (text.isEmpty()) return FuelCapacityInput.Cleared
    if (!LITERS_ONE_DECIMAL.matches(text)) return FuelCapacityInput.Invalid
    val tenths = java.math.BigDecimal(text.replace(',', '.')).movePointRight(1)
    if (tenths < FuelCapacityLimits.MIN_DL.toBigDecimal() || tenths > FuelCapacityLimits.MAX_DL.toBigDecimal()) return FuelCapacityInput.Invalid
    return FuelCapacityInput.Valid(tenths.toInt())
}
