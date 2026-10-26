package de.adv990de.obd2.fuel

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Stufen der virtuellen Tankanzeige, seit 2026-10-15 (Nutzerwunsch) **an die Zahl leuchtender Segmente gekoppelt** statt an Prozentschwellen:
 * 5-10 Segmente gruen, 3-4 gelb, 2 rot, 1 (und leer) rot blinkend. Die Farbe wechselt damit genau in dem Moment, in dem ein Segment erlischt;
 * die verbleibenden Segmente nehmen die Farbe der neuen Stufe an (die ganze Anzeige hat eine Farbe, die Farbe haengt nicht an der Segmentposition).
 * Vorher galten >= 40 % gruen, >= 20 % gelb, >= 5 % rot, darunter blinkend — die Farbe wechselte mitten in einem Segment, und die blinkende Stufe
 * begann erst unter 5 %; jetzt blinkt das letzte Segment, also ab hoechstens 10 % Rest.
 */
enum class FuelLevel {
    Green, Yellow, Red, RedBlinking;

    /** Nur die unterste Stufe blinkt. */
    val blinks: Boolean get() = this == RedBlinking
}

/** Ab so vielen leuchtenden Segmenten ist die Anzeige gruen. */
internal const val FUEL_GREEN_FROM_SEGMENTS = 5

/** Ab so vielen leuchtenden Segmenten (und unter [FUEL_GREEN_FROM_SEGMENTS]) ist sie gelb. */
internal const val FUEL_YELLOW_FROM_SEGMENTS = 3

/** Ab so vielen leuchtenden Segmenten (und unter [FUEL_YELLOW_FROM_SEGMENTS]) ist sie rot; darunter blinkt sie rot. */
internal const val FUEL_RED_FROM_SEGMENTS = 2

/**
 * Die Stufe zu einer Zahl leuchtender Segmente. Null Segmente (leer) ist die Alarmstufe — nie Gruen, auch wenn es dann nichts zum Blinken gibt.
 * Reine Funktion ohne Android-Bezug, JVM-getestet.
 */
fun fuelLevelForSegments(litSegments: Int): FuelLevel = when {
    litSegments >= FUEL_GREEN_FROM_SEGMENTS -> FuelLevel.Green
    litSegments >= FUEL_YELLOW_FROM_SEGMENTS -> FuelLevel.Yellow
    litSegments >= FUEL_RED_FROM_SEGMENTS -> FuelLevel.Red
    else -> FuelLevel.RedBlinking
}

/**
 * Die Stufe zu einem Anteil [fraction] (0 = leer, 1 = voll): die Stufe seiner Segmentzahl ([fuelLitSegments]). Ein unbekannter Anteil (NaN) laesst
 * kein Segment leuchten und ist damit die Alarmstufe — nie Gruen.
 */
fun fuelLevel(fraction: Float): FuelLevel = fuelLevelForSegments(fuelLitSegments(fraction))

/** Zahl der Segmente der Tankanzeige (Nutzerwunsch 2026-10-15): jedes steht fuer ein Zehntel der Reichweite bei vollem Tank. */
const val FUEL_SEGMENT_COUNT = 10

/** Toleranz in Segmenten gegen Float-Rauschen an den Grenzen: `0,3f * 10` ist 3,0000001 und liesse sonst ein Segment zu viel leuchten. */
private const val SEGMENT_EPSILON = 1e-4f

/**
 * Wie viele von [count] Segmenten bei einem Anteil [fraction] (0 = leer, 1 = voll) leuchten: `ceil(fraction * count)` — ein Segment bleibt an,
 * solange noch etwas von seinem Zehntel uebrig ist (wie der Drehzahlbalken mit `ceil(rpm / 200)`). Das erste Segment geht also erst aus, wenn ein
 * Zehntel der Reichweite gefahren ist, und das letzte erst bei leerem Tank — das blinkende letzte Segment steht damit bis zum leeren Tank.
 * Ein unbekannter Anteil (NaN) laesst nichts leuchten. Reine Funktion ohne Android-Bezug, JVM-getestet.
 */
fun fuelLitSegments(fraction: Float, count: Int = FUEL_SEGMENT_COUNT): Int {
    if (fraction.isNaN()) return 0
    return ceil(fraction * count - SEGMENT_EPSILON).toInt().coerceIn(0, count)
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
data class FuelGaugeState(val rangeFullKm: Int, val kmSinceFill: Float, val reserveKm: Int = 0) {

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

    /** Wie viele der [FUEL_SEGMENT_COUNT] Segmente leuchten (s. [fuelLitSegments]); die Farbe kommt weiter aus [level], nicht aus der Segmentzahl. */
    val litSegments: Int get() = fuelLitSegments(fraction)

    /** Die Restreichweite als Zahl der Tankzeile: auf 5 km abgerundet ([displayRangeKm]). */
    val displayKm: Int get() = displayRangeKm(remainingKm)

    /** Wo die Reserve-Marke im Balken steht (Anteil von links), `null` = keine Marke ([reserveFraction]). */
    val reserveFraction: Float? get() = reserveFraction(rangeFullKm, reserveKm)

    /** Der Rest hat die Reserve erreicht ([inReserve]): die Zahl der Tankzeile wird dann rot. */
    val inReserve: Boolean get() = inReserve(remainingKm, reserveKm)
}
