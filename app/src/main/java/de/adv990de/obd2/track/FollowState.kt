package de.adv990de.obd2.track

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** In welcher Richtung ein Track nachgefahren wird: wie aufgezeichnet oder zurueck. */
enum class FollowDirection { FORWARD, REVERSE }

/**
 * Die Linie, der man folgt: die Punkte **in Fahrtrichtung** ([FollowDirection.REVERSE] kehrt sie um) und die Laenge vom Start bis zu jedem Punkt ([cumulativeM], Meter).
 * Gerechnet wird einmal beim Anlegen, nicht bei jedem Fix.
 *
 * **Positionen sind sensibel:** nichts hiervon geht in ein Log.
 */
class FollowLine private constructor(val points: List<TrackPoint>) {
    val cumulativeM: DoubleArray = DoubleArray(points.size).also { out ->
        for (i in 1 until points.size) out[i] = out[i - 1] + distanceM(points[i - 1], points[i])
    }

    val totalM: Double get() = if (points.isEmpty()) 0.0 else cumulativeM.last()

    /** Zwei verschiedene Punkte braucht es mindestens: eine Linie ohne Laenge hat kein Vorn. */
    val canFollow: Boolean get() = points.size >= 2 && totalM > 0.0

    val segmentCount: Int get() = points.size - 1

    /** Das Teilstueck, in dem [alongM] liegt (ab 0), auf die vorhandenen Teilstuecke begrenzt. */
    fun segmentAt(alongM: Double): Int {
        if (segmentCount <= 0) return 0
        val i = java.util.Arrays.binarySearch(cumulativeM, alongM)
        val segment = if (i >= 0) i else -i - 2
        return segment.coerceIn(0, segmentCount - 1)
    }

    /** Die Strecke vom Start bis zum Fusspunkt auf Teilstueck [segment] an der Stelle [fraction] (0 bis 1). */
    fun alongOf(segment: Int, fraction: Double): Double =
        cumulativeM[segment] + fraction * (cumulativeM[segment + 1] - cumulativeM[segment])

    /** Der Punkt auf Teilstueck [segment] an der Stelle [fraction] (0 bis 1): zwischen den zwei Endpunkten, der kurze Weg ueber die Datumsgrenze. */
    fun footPoint(segment: Int, fraction: Double): TrackPoint {
        val a = points[segment]
        val b = points[segment + 1]
        return TrackPoint(null, a.lat + fraction * (b.lat - a.lat), a.lon + fraction * wrapLonDegrees(b.lon - a.lon), null, null, null)
    }

    /** Der Punkt der Linie [alongM] Meter vom Anfang (auf die Linie begrenzt). */
    fun pointAt(alongM: Double): TrackPoint {
        val clamped = alongM.coerceIn(0.0, totalM)
        val segment = segmentAt(clamped)
        val len = cumulativeM[segment + 1] - cumulativeM[segment]
        return footPoint(segment, if (len > 0.0) ((clamped - cumulativeM[segment]) / len).coerceIn(0.0, 1.0) else 0.0)
    }

    /** Die Richtung von Teilstueck [segment] in Grad (0 = Nord, im Uhrzeigersinn); ein Teilstueck ohne Laenge nimmt das naechste mit Laenge, sonst das vorige. */
    fun bearingAt(segment: Int): Double {
        fun hasLength(i: Int) = cumulativeM[i + 1] - cumulativeM[i] > 0.0
        val i = (segment until segmentCount).firstOrNull(::hasLength) ?: (segment downTo 0).firstOrNull(::hasLength) ?: return 0.0
        return bearingDeg(points[i], points[i + 1])
    }

    companion object {
        fun of(points: List<TrackPoint>, direction: FollowDirection): FollowLine =
            FollowLine(if (direction == FollowDirection.REVERSE) points.asReversed() else points)
    }
}

/** Die Anfangsrichtung des Grosskreises von [a] nach [b] in Grad, 0 bis unter 360 (0 = Nord, 90 = Ost). */
fun bearingDeg(a: TrackPoint, b: TrackPoint): Double {
    val phi1 = Math.toRadians(a.lat)
    val phi2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(wrapLonDegrees(b.lon - a.lon))
    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    val deg = Math.toDegrees(atan2(y, x))
    return (deg + 360.0) % 360.0
}

/**
 * Wo man auf der Linie steht. [distanceToTrackM] ist der kuerzeste Abstand zur Linie, [alongM] die Strecke vom Start bis zum Fusspunkt, [remainingM] der Rest bis zum Ziel,
 * [segmentIndex] das Teilstueck des Fusspunkts und [bearingDeg] dessen Richtung (0 = Nord, im Uhrzeigersinn).
 */
data class FollowResult(
    val distanceToTrackM: Double,
    val alongM: Double,
    val remainingM: Double,
    val totalM: Double,
    val segmentIndex: Int,
    val bearingDeg: Double,
    /** Der Fusspunkt auf der Linie (der naechste Punkt des Tracks): das Ende der Abstandslinie in der Karte; `null` nur in Tests und Vorschauen. */
    val foot: TrackPoint? = null,
) {
    /** Der Fortschritt von 0 bis 1. */
    val progress: Double get() = if (totalM > 0.0) (alongM / totalM).coerceIn(0.0, 1.0) else 0.0
}

/** So weit voraus sucht der Verfolger um den letzten Treffer (Meter auf der Linie); weiter weg findet die Suche ueber die ganze Linie. */
internal const val FOLLOW_FORWARD_WINDOW_M = 2_000.0

/** So weit zurueck reicht der Rueckwaertsschritt, den der Verfolger ueberhaupt in Betracht zieht. */
internal const val FOLLOW_BACK_WINDOW_M = 200.0

/** Ein Schritt zurueck, der nicht weiter geht als das, gilt als Rauschen des GPS: der Fortschritt sinkt nicht. */
internal const val FOLLOW_BACK_HYSTERESIS_M = 50.0

/** Ein Treffer hinter dem Fortschritt gilt nur, wenn er um so viel naeher an der Position liegt als der beste vor ihm (auf dem Rueckweg derselben Strasse liegen beide gleich nah). */
internal const val FOLLOW_BACK_PREFERENCE_M = 30.0

/** Liegt ein Punkt der ganzen Linie um mehr als das naeher als der beste im Fenster, gilt er (man hat abgekuerzt oder ist weit gesprungen). */
internal const val FOLLOW_REACQUIRE_MARGIN_M = 100.0

/**
 * Der Verfolger einer [FollowLine]: macht aus jeder Position Abstand, Strecke, Rest und Richtung — **ohne Routenfuehrung**, er zeigt nur, wo man steht. Er merkt sich den Fortschritt,
 * damit der Treffer auf einer Schleife oder einem Rueckweg ueber dieselbe Strasse nicht auf die andere Haelfte springt:
 *
 * - gesucht wird zuerst in einem **Fenster** um den bisherigen Fortschritt (bis [FOLLOW_FORWARD_WINDOW_M] voraus, bis [FOLLOW_BACK_HYSTERESIS_M] zurueck);
 * - ein Treffer weiter **hinter** dem Fortschritt (bis [FOLLOW_BACK_WINDOW_M]) gilt nur, wenn er klar naeher liegt ([FOLLOW_BACK_PREFERENCE_M]);
 * - ein Schritt zurueck innerhalb der **Hysterese** senkt den Fortschritt nicht;
 * - findet die Suche ueber die **ganze** Linie einen Punkt, der um [FOLLOW_REACQUIRE_MARGIN_M] naeher liegt, gilt er (Wiedereinstieg).
 *
 * Der erste Fix sucht ueber die ganze Linie. Ein Verfolger gehoert zu einer Linie und einer Richtung; wer die Richtung wechselt, legt einen neuen an.
 */
class FollowTracker(private val line: FollowLine) {
    private var along = 0.0
    private var started = false

    /** Die Antwort zu [position]; `null`, wenn die Linie nicht verfolgt werden kann. */
    fun update(position: TrackPoint): FollowResult? {
        if (!line.canFollow) return null
        val last = line.segmentCount - 1
        var chosen: PolylineHit
        var forward = false
        if (!started) {
            chosen = hit(position, 0, last)
        } else {
            val base = line.segmentAt(along - FOLLOW_BACK_HYSTERESIS_M)
            val ahead = hit(position, base, line.segmentAt(along + FOLLOW_FORWARD_WINDOW_M))
            val backFrom = line.segmentAt(along - FOLLOW_BACK_WINDOW_M)
            val behind = if (backFrom < base) hit(position, backFrom, base) else null
            forward = true
            chosen = ahead
            if (behind != null && behind.distanceM + FOLLOW_BACK_PREFERENCE_M < ahead.distanceM) {
                chosen = behind
                forward = false
            }
            // Die Suche ueber die ganze Linie kann nur gewinnen, wenn der Treffer weiter weg liegt als die Marge (der Abstand ist hoechstens so gross wie der des Treffers):
            // sonst spart sie sich die Arbeit ueber alle Teilstuecke bei jedem Fix.
            if (chosen.distanceM > FOLLOW_REACQUIRE_MARGIN_M) {
                val global = hit(position, 0, last)
                if (chosen.distanceM - global.distanceM > FOLLOW_REACQUIRE_MARGIN_M) {
                    chosen = global
                    forward = false
                }
            }
        }
        var newAlong = line.alongOf(chosen.segmentIndex, chosen.fraction)
        // Rauschen: ein kleiner Schritt zurueck senkt den Fortschritt nicht.
        if (started && forward && newAlong < along && along - newAlong <= FOLLOW_BACK_HYSTERESIS_M) newAlong = along
        along = newAlong
        started = true
        return FollowResult(
            distanceToTrackM = chosen.distanceM,
            alongM = newAlong,
            remainingM = (line.totalM - newAlong).coerceAtLeast(0.0),
            totalM = line.totalM,
            segmentIndex = chosen.segmentIndex,
            bearingDeg = line.bearingAt(chosen.segmentIndex),
            foot = line.footPoint(chosen.segmentIndex, chosen.fraction),
        )
    }

    /** Der naechste Punkt der Teilstuecke [from] bis [to] (einschliesslich); der Index im Treffer ist der der ganzen Linie. */
    private fun hit(position: TrackPoint, from: Int, to: Int): PolylineHit {
        val hit = distanceToPolylineM(position, line.points.subList(from, to + 2))!!
        return hit.copy(segmentIndex = hit.segmentIndex + from)
    }
}
