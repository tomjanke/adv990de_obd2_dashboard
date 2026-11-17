package de.adv990de.obd2.track

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ein Punkt eines Tracks. Zeit, Hoehe, Genauigkeit und Geschwindigkeit sind optional: ein importierter Track hat sie oft nicht, und "unbekannt" ist nicht 0.
 *
 * **Positionen sind sensibel:** nie in ein Log schreiben, nie in einen Test mit echten Daten.
 */
data class TrackPoint(
    val timeMs: Long?,
    val lat: Double,
    val lon: Double,
    val ele: Float?,
    val accuracyM: Float?,
    val speedKmh: Float?,
)

/** Der Treffer von [distanceToPolylineM]: Abstand zur Linie, das naechste Teilstueck (ab 0) und die Lage des Fusspunkts darauf (0 bis 1). */
data class PolylineHit(val distanceM: Double, val segmentIndex: Int, val fraction: Double)

/** Mittlerer Erdradius in Metern; ein Grad am Aequator sind damit 111 195 m. */
internal const val EARTH_RADIUS_M = 6_371_008.8

private const val DEG_TO_RAD = PI / 180.0

/** Grosskreis-Abstand zweier Punkte in Metern (Haversine). */
fun distanceM(a: TrackPoint, b: TrackPoint): Double {
    val dLat = (b.lat - a.lat) * DEG_TO_RAD
    // der Unterschied der Laenge braucht keinen Umbruch: sin^2(x/2) ist 360-periodisch
    val dLon = (b.lon - a.lon) * DEG_TO_RAD
    val h = sin(dLat / 2).let { it * it } + cos(a.lat * DEG_TO_RAD) * cos(b.lat * DEG_TO_RAD) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/**
 * Der kuerzeste Abstand von [p] zur Linie [line] (ein Linienzug aus Strecken), in einer lokalen ebenen Projektion um [p] — fuer die Abstaende, um die es hier geht
 * (Meter bis wenige Kilometer), ist der Fehler klein gegen die Genauigkeit des GPS. `null` bei einer leeren Linie; bei einem Punkt der Abstand zu ihm. Bei gleichem
 * Abstand gewinnt das erste Teilstueck.
 */
fun distanceToPolylineM(p: TrackPoint, line: List<TrackPoint>): PolylineHit? {
    if (line.isEmpty()) return null
    val cosLat = cos(p.lat * DEG_TO_RAD)
    fun x(q: TrackPoint) = wrapLonDegrees(q.lon - p.lon) * DEG_TO_RAD * cosLat * EARTH_RADIUS_M
    fun y(q: TrackPoint) = (q.lat - p.lat) * DEG_TO_RAD * EARTH_RADIUS_M
    if (line.size == 1) return PolylineHit(sqrt(x(line[0]).let { it * it } + y(line[0]).let { it * it }), 0, 0.0)
    var best = PolylineHit(Double.POSITIVE_INFINITY, 0, 0.0)
    for (i in 0 until line.size - 1) {
        val ax = x(line[i])
        val ay = y(line[i])
        val bx = x(line[i + 1])
        val by = y(line[i + 1])
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        // p liegt im Ursprung: der Fusspunkt ist der Punkt der Strecke, der dem Ursprung am naechsten liegt
        val fraction = if (lengthSquared == 0.0) 0.0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / lengthSquared))
        val fx = ax + fraction * dx
        val fy = ay + fraction * dy
        val distance = sqrt(fx * fx + fy * fy)
        if (distance < best.distanceM) best = PolylineHit(distance, i, fraction)
    }
    return best
}

/** Bringt einen Laengenunterschied in Grad auf -180 bis 180 (der kurze Weg ueber die Datumsgrenze). */
internal fun wrapLonDegrees(d: Double): Double {
    var v = d % 360.0
    if (v > 180.0) v -= 360.0
    if (v < -180.0) v += 360.0
    return v
}
