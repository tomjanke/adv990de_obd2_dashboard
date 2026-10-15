package de.adv990de.obd2.fuel

import kotlin.math.roundToInt

/**
 * Stufen der virtuellen Tankanzeige (Nutzerwunsch 2026-10-15), bezogen auf den Anteil der Restreichweite an der Reichweite bei vollem Tank:
 * 100-40 % gruen, 39-20 % gelb, 19-5 % rot, unter 5 % rot blinkend. Die Grenzen sind Untergrenzen (>= 40, >= 20, >= 5): "39 %" heisst
 * "unter 40", sonst klaffte bei 39,5 % eine Luecke.
 */
enum class FuelLevel {
    Green, Yellow, Red, RedBlinking;

    /** Nur die unterste Stufe blinkt. */
    val blinks: Boolean get() = this == RedBlinking
}

internal const val FUEL_GREEN_FROM = 0.40f
internal const val FUEL_YELLOW_FROM = 0.20f
internal const val FUEL_RED_FROM = 0.05f

/**
 * Die Stufe zu einem Anteil [fraction] (0 = leer, 1 = voll). Ein unbekannter Anteil (NaN) ist die Alarmstufe — nie Gruen.
 * Reine Funktion ohne Android-Bezug, JVM-getestet.
 */
fun fuelLevel(fraction: Float): FuelLevel = when {
    fraction >= FUEL_GREEN_FROM -> FuelLevel.Green
    fraction >= FUEL_YELLOW_FROM -> FuelLevel.Yellow
    fraction >= FUEL_RED_FROM -> FuelLevel.Red
    else -> FuelLevel.RedBlinking
}

/** Erlaubte Eingabe fuer "Reichweite bei vollem Tank", in ganzen Kilometern. 0 steht im Speicher fuer "nicht festgelegt". */
object FuelRangeLimits {
    const val MIN_KM = 50
    const val MAX_KM = 1000
}

/**
 * Was die Tankanzeige zeigt: die hinterlegte Reichweite bei vollem Tank und die seit dem letzten Zuruecksetzen gefahrenen Kilometer.
 * Es ist ein Streckenzaehler, keine Kraftstoffmessung — der Verbrauch geht nicht ein.
 *
 * Gespeichert wird der Zaehler, nicht der Rest: aendert der Nutzer die Reichweite, folgt die Anzeige sofort. Ein negativer oder nicht
 * endlicher Zaehler gilt als "nichts gefahren"; ein Zaehler ueber der Reichweite als leer.
 */
data class FuelGaugeState(val rangeFullKm: Int, val kmSinceFill: Float) {

    init {
        require(rangeFullKm > 0) { "rangeFullKm must be positive, was $rangeFullKm" }
    }

    private val drivenKm: Float get() = if (kmSinceFill.isFinite() && kmSinceFill > 0f) kmSinceFill else 0f

    /** Restreichweite in km, zwischen 0 und der vollen Reichweite. */
    val remainingKm: Float get() = (rangeFullKm - drivenKm).coerceIn(0f, rangeFullKm.toFloat())

    /** Anteil der vollen Reichweite, 0 bis 1. */
    val fraction: Float get() = remainingKm / rangeFullKm

    /** Der Anteil in ganzen Prozent (gerundet) — fuer die Beschreibung, nicht fuer die Stufe. */
    val percent: Int get() = (fraction * 100f).roundToInt()

    val level: FuelLevel get() = fuelLevel(fraction)
}
