package de.adv990de.obd2.fuel

import kotlin.math.floor

/** Erlaubte Eingabe fuer die Reserve der Tankanzeige, in ganzen Kilometern Restreichweite. 0 steht im Speicher fuer "aus". */
object FuelReserveLimits {
    const val MIN_KM = 5
    const val MAX_KM = 500
}

/** Die Zahl in der Tankzeile wird auf so viele Kilometer abgerundet: die Restreichweite ist eine Schaetzung, kein Messwert. Bis 1.44.1 waren es 5 km, seit 1.44.2 (Nutzerwunsch 2026-10-26) ist es 1 km. */
private const val DISPLAY_STEP_KM = 1f

/**
 * Die Restreichweite, wie die Tankzeile sie als Zahl zeigt: **auf ganze Kilometer abgerundet** ("187,9" wird "187") — die Reichweite ist eine Schaetzung (aus dem Logbuch auf etwa +-20 %),
 * und abwaerts rundet zur sicheren Seite; die Schrittweite ist [DISPLAY_STEP_KM]. Unter einem Kilometer, negativ, NaN und unendlich ergeben 0: nie eine negative oder erfundene Zahl.
 * Reine Funktion ohne Android-Bezug, JVM-getestet (Entwurf `2026-10-25-range-design.md`, D2).
 */
fun displayRangeKm(remainingKm: Float): Int {
    if (!remainingKm.isFinite() || remainingKm < DISPLAY_STEP_KM) return 0
    return (floor(remainingKm / DISPLAY_STEP_KM) * DISPLAY_STEP_KM).toInt()
}

/**
 * Wo die Reserve-Marke im Balken steht, als Anteil der vollen Reichweite von links (0 bis 1): `reserveKm / rangeFullKm`. `null` = keine Marke: Reserve 0 (aus) oder negativ, und eine
 * Reserve, die so gross ist wie die Reichweite oder groesser (die Marke stuende ganz rechts am Ende und sagte nichts).
 */
fun reserveFraction(rangeFullKm: Int, reserveKm: Int): Float? {
    if (rangeFullKm <= 0 || reserveKm <= 0 || reserveKm >= rangeFullKm) return null
    return reserveKm.toFloat() / rangeFullKm
}

/** `true`, wenn der Rest die Reserve erreicht hat oder darunter liegt (die Grenze selbst zaehlt als Reserve). Ohne Reserve nie — auch nicht bei leerem Tank (das ist die Alarmstufe der Segmente). */
fun inReserve(remainingKm: Float, reserveKm: Int): Boolean = reserveKm > 0 && remainingKm <= reserveKm
