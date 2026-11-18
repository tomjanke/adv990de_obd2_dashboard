package de.adv990de.obd2.ui.tracks

import de.adv990de.obd2.track.EARTH_RADIUS_M
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.wrapLonDegrees
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/*
 * Die Projektion und die Ansicht der Track-Zeichnung — rein, ohne Compose, damit jede Regel JVM-getestet ist.
 *
 * Mercator, lokal: x = Laengenunterschied zu einer Bezugslaenge (im Bogenmass), y = der Mercator-y-Wert der Breite. In dieser Ebene sind kleine Formen winkeltreu — ein Quadrat von
 * 100 m mal 100 m bleibt ein Quadrat —, und eine Einheit (Bogenmass) ist auf der Breite phi genau `R * cos(phi)` Meter lang (deshalb der Massstab). Die Bezugslaenge ist die westliche Kante
 * des Tracks (`bounds.minLon`); ein Track ueber die Datumsgrenze ist nicht vorgesehen.
 */

/** Bis zu dieser Breite (Grad) wird projiziert; die Mercator-Projektion geht an den Polen gegen unendlich. */
private const val MAX_LAT_DEG = 85.0

/** So klein darf die sichtbare Ausdehnung hoechstens werden (ein einzelner Punkt, ein Track ohne Weg): 200 m, in Bogenmass. */
private const val MIN_SPAN_UNITS = 200.0 / EARTH_RADIUS_M

/** Wie weit man hineinzoomen darf, als Vielfaches der angepassten Ansicht. */
const val MAX_ZOOM_IN = 256.0

/** Wie weit man herauszoomen darf, als Vielfaches der angepassten Ansicht (kleiner 1 = der Track wird kleiner als die Flaeche). */
const val MIN_ZOOM_OUT = 0.5

/** Ein Punkt auf der Zeichenflaeche in Bildpunkten, Ursprung links oben. */
data class ScreenPoint(val x: Double, val y: Double)

/**
 * Die Ansicht: [cx], [cy] ist der Punkt der Kartenebene in der Mitte der Flaeche, [pxPerUnit] der Massstab (Bildpunkte je Einheit der Ebene). Die Ansicht kennt die Groesse der Flaeche
 * nicht — die kommt bei jeder Umrechnung mit, damit eine Drehung (andere Flaeche) die Ansicht nicht verschiebt.
 */
data class TrackView(val cx: Double, val cy: Double, val pxPerUnit: Double)

/** Der y-Wert der Mercator-Ebene zu einer Breite in Grad. */
fun projectLat(latDeg: Double): Double {
    val phi = Math.toRadians(latDeg.coerceIn(-MAX_LAT_DEG, MAX_LAT_DEG))
    return ln(tan(PI / 4 + phi / 2))
}

/** Die Breite in Grad zu einem y-Wert der Mercator-Ebene (die Umkehrung von [projectLat]). */
fun unprojectLat(y: Double): Double = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(y)))

/** Der x-Wert der Ebene zu einer Laenge in Grad, gemessen von [refLonDeg] (der kurze Weg ueber die Datumsgrenze). */
fun projectLon(lonDeg: Double, refLonDeg: Double): Double = Math.toRadians(wrapLonDegrees(lonDeg - refLonDeg))

/** Der Bildpunkt zu einem Punkt der Ebene; Norden ist oben. */
fun toScreen(view: TrackView, widthPx: Double, heightPx: Double, x: Double, y: Double) =
    ScreenPoint(widthPx / 2 + (x - view.cx) * view.pxPerUnit, heightPx / 2 - (y - view.cy) * view.pxPerUnit)

/** Die Ansicht, in der der ganze Track mit [paddingPx] Rand auf die Flaeche passt, mittig und mit erhaltenem Seitenverhaeltnis. */
fun fitView(bounds: TrackBounds, widthPx: Double, heightPx: Double, paddingPx: Double): TrackView {
    val minX = 0.0
    val maxX = projectLon(bounds.maxLon, bounds.minLon)
    val minY = projectLat(bounds.minLat)
    val maxY = projectLat(bounds.maxLat)
    val spanX = max(maxX - minX, MIN_SPAN_UNITS)
    val spanY = max(maxY - minY, MIN_SPAN_UNITS)
    val availableX = max(1.0, widthPx - 2 * paddingPx)
    val availableY = max(1.0, heightPx - 2 * paddingPx)
    return TrackView(cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, pxPerUnit = min(availableX / spanX, availableY / spanY))
}

/** Zoomt um [factor] um die Mitte der Flaeche; der Massstab bleibt zwischen [MIN_ZOOM_OUT] und [MAX_ZOOM_IN] mal dem der angepassten Ansicht [fit]. */
fun zoomBy(view: TrackView, factor: Double, fit: TrackView): TrackView =
    view.copy(pxPerUnit = (view.pxPerUnit * factor).coerceIn(fit.pxPerUnit * MIN_ZOOM_OUT, fit.pxPerUnit * MAX_ZOOM_IN))

/** Verschiebt den Inhalt um [dxPx], [dyPx] Bildpunkte (in Richtung der Bewegung, wie ein Finger auf Papier). */
fun panBy(view: TrackView, dxPx: Double, dyPx: Double): TrackView =
    view.copy(cx = view.cx - dxPx / view.pxPerUnit, cy = view.cy + dyPx / view.pxPerUnit)

/** Wie viele Meter ein Bildpunkt auf der Breite [latDeg] ist. */
fun metresPerPx(view: TrackView, latDeg: Double): Double = EARTH_RADIUS_M * cos(Math.toRadians(latDeg)) / view.pxPerUnit

/** Die Massstabsleiste: eine runde Laenge in [metres] und ihre Breite [px] in der Zeichnung. */
data class ScaleBar(val metres: Int, val px: Double)

private val ROUND_METRES = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000)

/** Die laengste runde Laenge (1, 2 oder 5 mal einer Zehnerpotenz), die in [maxBarPx] Bildpunkte passt; ist selbst die kuerzeste zu lang, die kuerzeste. */
fun scaleBar(view: TrackView, centerLatDeg: Double, maxBarPx: Double): ScaleBar {
    val perPx = metresPerPx(view, centerLatDeg)
    val metres = ROUND_METRES.lastOrNull { it / perPx <= maxBarPx } ?: ROUND_METRES.first()
    return ScaleBar(metres, metres / perPx)
}

/** Zahl und Einheit der Leiste: unter einem Kilometer Meter, sonst Kilometer. `true` = Kilometer. */
fun scaleBarUnit(metres: Int): Pair<Int, Boolean> = if (metres >= 1_000) metres / 1_000 to true else metres to false

/**
 * Was der Nutzer an der Ansicht verstellt hat, **relativ zur angepassten Ansicht**: [zoom] ist ein Vielfaches ihres Massstabs, [dx] und [dy] sind die Verschiebung ihrer Mitte in Einheiten der
 * Kartenebene. So ueberlebt der Zustand eine Drehung (die Flaeche wird eine andere, die angepasste Ansicht mit ihr): derselbe Ausschnitt der Gegend bleibt im Bild, statt dass dieselben
 * Bildpunkte gelten. Der Ausgangszustand [TrackViewState] ist die angepasste Ansicht.
 */
data class TrackViewState(val zoom: Double = 1.0, val dx: Double = 0.0, val dy: Double = 0.0) {

    /** Die Ansicht dieses Zustands auf einer Flaeche, deren angepasste Ansicht [fit] ist. */
    fun resolve(fit: TrackView) = TrackView(cx = fit.cx + dx, cy = fit.cy + dy, pxPerUnit = fit.pxPerUnit * zoom)

    /** Zoomt um [factor] um die Mitte, begrenzt wie [zoomBy]. */
    fun zoomedBy(factor: Double, fit: TrackView): TrackViewState = from(zoomBy(resolve(fit), factor, fit), fit)

    /** Verschiebt den Inhalt um [dxPx], [dyPx] Bildpunkte, wie [panBy]. */
    fun pannedBy(dxPx: Double, dyPx: Double, fit: TrackView): TrackViewState = from(panBy(resolve(fit), dxPx, dyPx), fit)

    /** Zurueck auf die angepasste Ansicht. */
    fun fitted() = TrackViewState()

    private fun from(view: TrackView, fit: TrackView) = TrackViewState(view.pxPerUnit / fit.pxPerUnit, view.cx - fit.cx, view.cy - fit.cy)
}
