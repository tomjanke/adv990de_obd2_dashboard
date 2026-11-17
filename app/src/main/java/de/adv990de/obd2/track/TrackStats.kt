package de.adv990de.obd2.track

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Das umschliessende Rechteck eines Tracks in Grad. */
data class TrackBounds(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

/**
 * Die Kennzahlen eines Tracks. [durationMs] ist `null` ohne zwei Zeitangaben, [ascentM] und [descentM] sind `null` ohne zwei Hoehenangaben — **"unbekannt" ist nicht 0**
 * (ein importierter Track ohne Hoehe hat keinen Anstieg von null Metern), [bounds] ist `null` ohne Punkte.
 */
data class TrackStats(
    val distanceM: Double,
    val durationMs: Long?,
    val ascentM: Double?,
    val descentM: Double?,
    val bounds: TrackBounds?,
)

internal object TrackStatsLimits {
    /** Fenster des gleitenden Mittels der Hoehe: so viele Punkte auf jeder Seite des Punkts (insgesamt 5). */
    const val SMOOTHING_HALF_WIDTH = 2

    /** Eine Hoehenaenderung unter diesem Betrag zaehlt nicht (Rauschen der GPS- und Druckhoehe), sie wird erst mitgezaehlt, wenn sie den Betrag erreicht. Ungemessen. */
    const val MIN_ELEVATION_STEP_M = 3.0
}

/** Rechnet die Kennzahlen aus den Punkten in der Reihenfolge der Aufzeichnung. */
fun trackStats(points: List<TrackPoint>): TrackStats {
    if (points.isEmpty()) return TrackStats(0.0, null, null, null, null)
    var distance = 0.0
    var minLat = points[0].lat
    var maxLat = minLat
    var minLon = points[0].lon
    var maxLon = minLon
    var firstTime: Long? = null
    var lastTime: Long? = null
    var timed = 0
    for (i in points.indices) {
        val p = points[i]
        if (i > 0) distance += distanceM(points[i - 1], p)
        minLat = min(minLat, p.lat)
        maxLat = max(maxLat, p.lat)
        minLon = min(minLon, p.lon)
        maxLon = max(maxLon, p.lon)
        p.timeMs?.let { t ->
            firstTime = firstTime?.let { min(it, t) } ?: t
            lastTime = lastTime?.let { max(it, t) } ?: t
            timed++
        }
    }
    val duration = if (timed >= 2) lastTime!! - firstTime!! else null
    val elevations = points.mapNotNull { it.ele?.toDouble() }
    val (ascent, descent) = ascentDescent(elevations)
    return TrackStats(distance, duration, ascent, descent, TrackBounds(minLat, maxLat, minLon, maxLon))
}

/** Anstieg und Abstieg aus der Hoehenfolge: gleitendes Mittel ueber 5 Punkte (an den Enden schmaler, die Endpunkte bleiben unveraendert), dann nur Schritte ab 3 m. */
private fun ascentDescent(elevations: List<Double>): Pair<Double?, Double?> {
    if (elevations.size < 2) return null to null
    val n = elevations.size
    val smoothed = DoubleArray(n) { i ->
        val half = minOf(TrackStatsLimits.SMOOTHING_HALF_WIDTH, i, n - 1 - i)
        var sum = 0.0
        for (j in i - half..i + half) sum += elevations[j]
        sum / (2 * half + 1)
    }
    var reference = smoothed[0]
    var ascent = 0.0
    var descent = 0.0
    for (i in 1 until n) {
        val diff = smoothed[i] - reference
        if (abs(diff) >= TrackStatsLimits.MIN_ELEVATION_STEP_M) {
            if (diff > 0) ascent += diff else descent -= diff
            reference = smoothed[i]
        }
    }
    return ascent to descent
}
