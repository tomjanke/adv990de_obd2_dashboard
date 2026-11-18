package de.adv990de.obd2.track

import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Duennt einen Linienzug auf hoechstens [maxPoints] Punkte aus (Douglas-Peucker, aber nach **Zahl** statt nach Toleranz): Anfang und Ende bleiben, dann kommt der Punkt dazu, der am
 * weitesten von der Verbindung seiner Nachbarn liegt, und so weiter, bis [maxPoints] erreicht sind oder kein Punkt mehr abweicht. Die Reihenfolge bleibt, es entstehen keine neuen Punkte.
 *
 * Nur fuer die **Zeichnung**: Kennzahlen und Export rechnen immer mit allen Punkten. Ein Track mit zehntausenden Punkten zeichnete sonst jede Neuzeichnung zehntausendfach.
 * Gerechnet wird in einer lokalen ebenen Projektion (Laenge mal `cos(Breite)`); fuer eine Zeichnung ist der Fehler ohne Belang.
 */
fun simplifyTrack(points: List<TrackPoint>, maxPoints: Int): List<TrackPoint> {
    require(maxPoints >= 2) { "maxPoints must be at least 2" }
    if (points.size <= maxPoints) return points

    val cosLat = cos(Math.toRadians((points.minOf { it.lat } + points.maxOf { it.lat }) / 2))
    val xs = DoubleArray(points.size) { points[it].lon * cosLat }
    val ys = DoubleArray(points.size) { points[it].lat }

    /** Das Teilstueck zwischen [lo] und [hi] und der Punkt darin mit der groessten Abweichung. */
    class Span(val lo: Int, val hi: Int, val index: Int, val distance: Double)

    fun deviation(lo: Int, hi: Int): Span? {
        if (hi - lo < 2) return null
        val dx = xs[hi] - xs[lo]
        val dy = ys[hi] - ys[lo]
        val lengthSquared = dx * dx + dy * dy
        var bestIndex = -1
        var best = -1.0
        for (i in lo + 1 until hi) {
            val d = if (lengthSquared == 0.0) {
                hypot(xs[i] - xs[lo], ys[i] - ys[lo])
            } else {
                val t = max(0.0, min(1.0, ((xs[i] - xs[lo]) * dx + (ys[i] - ys[lo]) * dy) / lengthSquared))
                hypot(xs[i] - (xs[lo] + t * dx), ys[i] - (ys[lo] + t * dy))
            }
            if (d > best) {
                best = d
                bestIndex = i
            }
        }
        return Span(lo, hi, bestIndex, best)
    }

    val kept = sortedSetOf(0, points.lastIndex)
    // das Teilstueck mit der groessten Abweichung zuerst; bei Gleichstand das fruehere (stabil)
    val queue = PriorityQueue<Span>(compareByDescending<Span> { it.distance }.thenBy { it.lo })
    deviation(0, points.lastIndex)?.let(queue::add)
    while (kept.size < maxPoints) {
        val span = queue.poll() ?: break
        if (span.distance == 0.0) break
        kept.add(span.index)
        deviation(span.lo, span.index)?.let(queue::add)
        deviation(span.index, span.hi)?.let(queue::add)
    }
    return kept.map { points[it] }
}
