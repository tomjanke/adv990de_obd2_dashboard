package de.adv990de.obd2.ui.tracks

import de.adv990de.obd2.track.EARTH_RADIUS_M
import de.adv990de.obd2.track.Maneuver
import de.adv990de.obd2.track.ManeuverKind
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.wrapLonDegrees
import de.adv990de.obd2.map.toPlane
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

/** Der kleinste Ausschnitt voraus (Meter): auch eine Richtungsaenderung dicht vor der Position wird nicht ins Riesige gezogen. */
internal const val ROADBOOK_MAP_MIN_AHEAD_M = 50.0

/** So viel Weg bleibt hinter der Position im Bild (Meter): der Blick gehoert der Strecke, die noch vor einem liegt. */
internal const val ROADBOOK_MAP_BEHIND_M = 20.0

/** Die kleinste Breite des Ausschnitts (Meter), damit eine gerade Linie nicht auf die ganze Breite gezogen wird. */
internal const val ROADBOOK_MAP_MIN_WIDTH_M = 60.0

/** Der Rand um den Inhalt als Anteil der Hoehe der Flaeche: Platz fuer den Pfeil der eigenen Position, damit er nie am Rand abgeschnitten wird. */
internal const val ROADBOOK_MAP_PADDING_FRACTION = 0.12f

/**
 * Legt den Ausschnitt in eine Flaeche von [widthPx] mal [heightPx]: **der Kurs steht oben** ([bearingDeg] ist die Richtung des Tracks an der Position). Der Massstab ist so, dass die
 * ganze Strecke voraus ([path] und [apex]) und [ROADBOOK_MAP_BEHIND_M] hinter der Position hineinpassen — mindestens [ROADBOOK_MAP_MIN_AHEAD_M] voraus und [ROADBOOK_MAP_MIN_WIDTH_M] breit.
 * **Die eigene Position liegt immer im Bild, mit Rand** ([ROADBOOK_MAP_PADDING_FRACTION]): der Inhalt wird in der Flaeche zentriert, die Position steht also dort, wo die Strecke
 * Platz laesst — vor einer Rechtskurve links unten, vor einer Linkskurve rechts unten, auf der Geraden in der Mitte. [apex] ist der Scheitelpunkt der Richtungsaenderung
 * (`null`: ohne Punkt in der Karte). Die Rechnung ist lokal (ebene Naeherung um die Position): fuer einen Ausschnitt von wenigen hundert Metern genau genug.
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
    val apexLocal = apex?.let(::local)
    val all = if (apexLocal != null) local + apexLocal else local
    // Der Inhalt: die Position (0, 0) und alles voraus; was hinter der Position liegt, zaehlt nur bis ROADBOOK_MAP_BEHIND_M (die Zeichnung schneidet den Rest ab).
    val maxY = max(0.0, all.maxOfOrNull { it.second } ?: 0.0)
    val minX = min(0.0, all.minOfOrNull { it.first } ?: 0.0)
    val maxX = max(0.0, all.maxOfOrNull { it.first } ?: 0.0)
    val pad = ROADBOOK_MAP_PADDING_FRACTION * heightPx
    val availW = (widthPx - 2f * pad).coerceAtLeast(1f)
    val availH = (heightPx - 2f * pad).coerceAtLeast(1f)
    // Der Massstab folgt den Mindestmassen, damit eine nahe Kurve nicht ins Riesige waechst; die Platzierung folgt den tatsaechlichen Massen.
    val scaleAhead = max(ROADBOOK_MAP_MIN_AHEAD_M, maxY) + ROADBOOK_MAP_BEHIND_M
    val scaleWidth = max(ROADBOOK_MAP_MIN_WIDTH_M, maxX - minX)
    val perMetre = min(availH / scaleAhead, availW / scaleWidth).let { if (it <= 0.0 || !it.isFinite()) 1.0 else it }
    val contentH = (maxY + ROADBOOK_MAP_BEHIND_M) * perMetre
    val posY = pad + (availH - contentH) / 2.0 + maxY * perMetre
    val xCentre = (minX + maxX) / 2.0
    fun px(l: Pair<Double, Double>) = MapPx((widthPx / 2.0 + (l.first - xCentre) * perMetre).toFloat(), (posY - l.second * perMetre).toFloat())
    return RoadbookMap(
        line = local.map(::px),
        position = px(0.0 to 0.0),
        apex = apexLocal?.let(::px),
        metresPerPx = 1.0 / perMetre,
    )
}

/**
 * Die Ansicht der Kartenkacheln, die zur kleinen Karte [this] passt: dieselbe Lage der Position im Bild ([RoadbookMap.position]), derselbe Massstab ([RoadbookMap.metresPerPx]) und der Kurs
 * oben ([bearingDeg]). Die Ebene misst ihre Laenge von [position]`.lon` aus (Bezugslaenge fuer `tileToScreen`). Mercator ist winkeltreu: eine Einheit der Ebene sind an dieser Breite
 * `EARTH_RADIUS_M · cos(lat)` Meter in beide Richtungen, so liegen Kacheln und Linie der kleinen Karte (ebene Naeherung) auf dem Bild deckungsgleich.
 */
internal fun RoadbookMap.trackView(position: TrackPoint, bearingDeg: Double, widthPx: Float, heightPx: Float): TrackView {
    val pxPerUnit = EARTH_RADIUS_M * cos(Math.toRadians(position.lat)) / metresPerPx
    val provisional = TrackView(cx = 0.0, cy = projectLat(position.lat), pxPerUnit = pxPerUnit, rotationDeg = bearingDeg, anchorY = 0.5)
    // Mit der Position in der Bildmitte steht der gewuenschte Mittelpunkt dort, wo die Position hin soll, gespiegelt an der Mitte.
    val (cx, cy) = toPlane(provisional, widthPx.toDouble(), heightPx.toDouble(), widthPx - this.position.x.toDouble(), heightPx - this.position.y.toDouble())
    return provisional.copy(cx = cx, cy = cy)
}

/** Die eigene Lage in der kleinen Karte: Position und Kurs (Richtung des Tracks an der Position). */
internal data class MapPose(val position: TrackPoint, val bearingDeg: Double)

/**
 * Die Lage zwischen zwei Fixen: [t] 0 ist [from], 1 ist [to]; der Kurs dreht den kuerzeren Weg (nie ueber Norden herum). Die Karte gleitet damit von einem Fix zum naechsten,
 * statt im Sekundentakt zu springen. Fuer die wenigen Meter zwischen zwei Fixen genuegt die lineare Mischung von Breite und Laenge.
 */
internal fun interpolatePose(from: MapPose, to: MapPose, t: Float): MapPose {
    val k = t.coerceIn(0f, 1f).toDouble()
    val lat = from.position.lat + (to.position.lat - from.position.lat) * k
    val lon = from.position.lon + wrapLonDegrees(to.position.lon - from.position.lon) * k
    val bearing = (from.bearingDeg + shortestTurnDeg(from.bearingDeg, to.bearingDeg) * k + 360.0) % 360.0
    return MapPose(TrackPoint(null, lat, lon, null, null, null), bearing)
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
