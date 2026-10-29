package de.adv990de.obd2.fuel

import de.adv990de.obd2.logbook.FuelReport
import kotlin.math.roundToInt

/** Erlaubte Eingabe fuer den Tankinhalt, in Zehntelliter (30 = 3,0 l). 0 steht im Speicher fuer "nicht festgelegt". */
object FuelCapacityLimits {
    const val MIN_DL = 30
    const val MAX_DL = 600
}

/**
 * So viele der juengsten Voll-zu-Voll-Intervalle gehen in den gelernten Verbrauch ein. Seit 2026-10-29 **3** (Entscheidung des Nutzers; bis 1.44.7 waren es 10).
 * Grundlage war eine Nachrechnung an den Logbuch-Daten des Nutzers (73 Intervalle, je Fenster 63 Vorhersagen des naechsten Intervalls): die **mittlere** Abweichung ist bei
 * allen Fenstern gleich (rund 18 bis 20 %), die **typische** (Median) ist mit kurzem Fenster kleiner (Fenster 3: 12 %, Fenster 10: 17 %), dafuer springt die Reichweite nach jeder
 * Tankung staerker (Fenster 3: im Schnitt rund 10 %, Fenster 10: rund 3 %). Ein Motorrad, eine Datei — kein Beweis; die Schlechtfaelle (75. Perzentil 23 bis 28 %) sind bei allen Fenstern gleich.
 * Gleich [RANGE_LEARNING_MIN_INTERVALS]: mit genau drei Intervallen geht jedes ein.
 */
const val RANGE_LEARNING_WINDOW = 3

/** Weniger Intervalle sind keine Grundlage: die Reichweite bleibt die manuelle. */
const val RANGE_LEARNING_MIN_INTERVALS = 3

/**
 * Der Verbrauch der juengsten Intervalle des Logbuchs in l/100 km: **Summe der Liter durch Summe der Kilometer** der letzten [window] Intervalle (nicht der Mittelwert der Einzelwerte:
 * der ist verzerrt, ein kurzes Intervall mit hohem Verbrauch zaehlte so viel wie ein langes). Basis ist die Voll-zu-Voll-Rechnung ([de.adv990de.obd2.logbook.fuelReport]); eine Teiltankung zaehlt
 * in das Intervall, das sie schliesst. `null` bei weniger als [minIntervals] Intervallen oder 0 Kilometern. Reine Funktion ohne Android-Bezug, JVM-getestet.
 *
 * [FuelReport.intervals] stehen aelteste zuerst: die juengsten sind die letzten.
 */
fun recentLitersPer100Km(
    report: FuelReport,
    window: Int = RANGE_LEARNING_WINDOW,
    minIntervals: Int = RANGE_LEARNING_MIN_INTERVALS,
): Double? {
    if (report.intervals.size < minIntervals) return null
    val recent = report.intervals.takeLast(window)
    val km = recent.sumOf { it.km }
    if (km <= 0) return null
    return recent.sumOf { it.amountMl } / (10.0 * km)
}

/**
 * Die Reichweite bei vollem Tank aus dem Tankinhalt [capacityDl] (Zehntelliter) und dem Verbrauch [litersPer100Km]: `Tankinhalt / Verbrauch * 100`, auf ganze km gerundet. `null` ohne
 * Tankinhalt, ohne (oder mit unbrauchbarem) Verbrauch und wenn das Ergebnis ausserhalb von [FuelRangeLimits] liegt: ein unplausibles Ergebnis wird nicht angezeigt, es gilt die manuelle Zahl.
 * Beispiel (erfundene Zahlen): 20,0 l bei 7,7 l/100 km sind 260 km.
 */
fun learnedRangeKm(capacityDl: Int, litersPer100Km: Double?): Int? {
    if (capacityDl <= 0 || litersPer100Km == null || !litersPer100Km.isFinite() || litersPer100Km <= 0.0) return null
    val km = (capacityDl / 10.0 / litersPer100Km * 100.0).roundToInt()
    return km.takeIf { it in FuelRangeLimits.MIN_KM..FuelRangeLimits.MAX_KM }
}

/** Die Reichweite, mit der die Tankanzeige rechnet: die gelernte, sonst die manuelle; 0 = keine (die Tankzeile bleibt dann ausgeblendet). */
fun effectiveRangeKm(manualKm: Int, learnedKm: Int?): Int = learnedKm ?: manualKm

/**
 * Wie viele Kilometer eine Teilbetankung der Tankanzeige gutschreibt: die getankten [liters] geteilt durch den Verbrauch [litersPer100Km] mal 100. `null` ohne brauchbare Liter oder
 * ohne (oder mit unbrauchbarem) Verbrauch — dann gibt es keine Rueckfrage. Reine Funktion; der Zaehler faellt dadurch nie unter 0 (`FuelRepository.credit`).
 */
fun creditKm(liters: Double, litersPer100Km: Double?): Float? {
    if (!liters.isFinite() || liters <= 0.0) return null
    if (litersPer100Km == null || !litersPer100Km.isFinite() || litersPer100Km <= 0.0) return null
    return (liters / litersPer100Km * 100.0).toFloat()
}
