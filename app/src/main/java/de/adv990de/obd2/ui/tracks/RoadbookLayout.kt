package de.adv990de.obd2.ui.tracks

import de.adv990de.obd2.track.EARTH_RADIUS_M
import de.adv990de.obd2.track.Maneuver
import de.adv990de.obd2.track.ManeuverKind
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.wrapLonDegrees
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Die Geometrie und die Texte des Roadbook-HUD — rein, ohne Compose, damit jede Regel JVM-getestet ist.
 */

/** Die Entfernung im HUD: ab 1 km mit einer Nachkommastelle („1,2 km“), ab 100 m auf 10 m abgerundet, darunter auf 5 m abgerundet, nie negativ. */
internal fun formatRoadbookDistance(metres: Double, locale: Locale = Locale.getDefault()): String {
    val m = metres.coerceAtLeast(0.0)
    val rounded = when {
        m >= 1_000.0 -> return formatTrackDistance(Math.round(m / 100.0) * 100L, locale)
        m >= 100.0 -> (m / 10.0).toLong() * 10L
        else -> (m / 5.0).toLong() * 5L
    }
    return formatTrackDistance(rounded, locale)
}

/** Ein Punkt in den Bildpunkten der kleinen Karte (Ursprung links oben). */
internal data class MapPx(val x: Float, val y: Float)

/** Die kleine Karte des HUD: der Linienzug, die Position, der Scheitelpunkt der Richtungsaenderung und die Zahl der Meter je Bildpunkt. */
internal data class RoadbookMap(val line: List<MapPx>, val position: MapPx, val apex: MapPx?, val metresPerPx: Double)

/** Auf dieser Hoehe (Anteil von oben) steht die eigene Position in der kleinen Karte: unten, damit der Weg voraus Platz hat. */
internal const val ROADBOOK_MAP_ANCHOR_Y = 0.85f

/** Der kleinste Ausschnitt voraus (Meter): auch eine Richtungsaenderung dicht vor der Position zeigt noch etwas Weg dahinter. */
internal const val ROADBOOK_MAP_MIN_AHEAD_M = 120.0

/** Die kleinste halbe Breite (Meter), damit eine gerade Linie nicht auf die ganze Breite gezogen wird. */
internal const val ROADBOOK_MAP_MIN_HALF_WIDTH_M = 60.0

/**
 * Legt den Ausschnitt in eine Flaeche von [widthPx] mal [heightPx]: **der Kurs steht oben** ([bearingDeg] ist die Richtung des Tracks an der Position), die Position bei
 * [ROADBOOK_MAP_ANCHOR_Y] mittig; der Massstab ist so, dass alles bis zum weitesten Punkt voraus ([ROADBOOK_MAP_MIN_AHEAD_M] mindestens) und seitlich ([ROADBOOK_MAP_MIN_HALF_WIDTH_M] mindestens)
 * hineinpasst. [apex] ist der Scheitelpunkt der Richtungsaenderung (`null`: ohne Punkt in der Karte).
 * Die Rechnung ist lokal (ebene Naeherung um die Position): fuer einen Ausschnitt von wenigen hundert Metern genau genug.
 */
internal fun roadbookMap(
    path: List<TrackPoint>,
    position: TrackPoint,
    bearingDeg: Double,
    apex: TrackPoint?,
    widthPx: Float,
    heightPx: Float,
): RoadbookMap {
    val cosLat = cos(Math.toRadians(position.lat))
    val r = Math.toRadians(bearingDeg)
    val c = cos(r)
    val s = sin(r)
    // Metern ostwaerts/nordwaerts von der Position, dann so gedreht, dass der Kurs nach oben zeigt: vorwaerts = (east * s + north * c), seitwaerts = (east * c - north * s).
    fun local(p: TrackPoint): Pair<Double, Double> {
        val east = Math.toRadians(wrapLonDegrees(p.lon - position.lon)) * cosLat * EARTH_RADIUS_M
        val north = Math.toRadians(p.lat - position.lat) * EARTH_RADIUS_M
        return (east * c - north * s) to (east * s + north * c)
    }
    val local = path.map(::local)
    val ahead = max(ROADBOOK_MAP_MIN_AHEAD_M, local.maxOfOrNull { it.second } ?: 0.0)
    val half = max(ROADBOOK_MAP_MIN_HALF_WIDTH_M, local.maxOfOrNull { abs(it.first) } ?: 0.0)
    val up = ROADBOOK_MAP_ANCHOR_Y * heightPx
    val perMetre = min(up / ahead, (widthPx / 2f) / half).toDouble().let { if (it <= 0.0) 1.0 else it }
    // Nach hinten darf der Weg bis zum Rand laufen; was darueber hinausgeht, schneidet die Zeichnung ab.
    fun px(l: Pair<Double, Double>) = MapPx((widthPx / 2f + l.first * perMetre).toFloat(), (up - l.second * perMetre).toFloat())
    return RoadbookMap(
        line = local.map(::px),
        position = MapPx(widthPx / 2f, up),
        apex = apex?.let { px(local(it)) },
        metresPerPx = if (perMetre > 0) 1.0 / perMetre else 1.0,
    )
}

/**
 * Wie der Pfeil des HUD aussieht: [turnDeg] ist der Winkel (positiv rechts). Der Pfeil kommt von unten, geht nach oben bis zur Ecke und knickt dort um den Winkel ab; eine Kehre ab
 * [HAIRPIN_ARROW_MIN_DEG] ist ein U. Die Rueckgabe ist die Richtung des zweiten Schenkels als Vektor (x nach rechts, y nach unten) und ob der Pfeil ein U ist.
 */
internal data class ArrowShape(val legDx: Float, val legDy: Float, val uTurn: Boolean)

internal const val HAIRPIN_ARROW_MIN_DEG = 135.0

internal fun arrowShape(turnDeg: Double): ArrowShape {
    if (abs(turnDeg) >= HAIRPIN_ARROW_MIN_DEG) return ArrowShape(0f, 1f, uTurn = true)
    val r = Math.toRadians(turnDeg)
    // Der erste Schenkel zeigt nach oben (0, -1); gedreht um turnDeg im Uhrzeigersinn: (sin, -cos).
    return ArrowShape(sin(r).toFloat(), (-cos(r)).toFloat(), uTurn = false)
}

/** Der Schluessel des Textes fuer die Art und Richtung einer Richtungsaenderung (`roadbook_kind_*`). */
internal fun maneuverKindKey(maneuver: Maneuver): String {
    val side = if (maneuver.isRight) "right" else "left"
    return when (maneuver.kind) {
        ManeuverKind.SLIGHT -> "slight_$side"
        ManeuverKind.NORMAL -> "normal_$side"
        ManeuverKind.SHARP -> "sharp_$side"
        ManeuverKind.HAIRPIN -> "hairpin_$side"
        ManeuverKind.DESTINATION -> "destination"
    }
}
