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

/** Wie weit man herauszoomen darf, als Vielfaches der angepassten Ansicht: **1** = nie kleiner als angepasst, der Track verlaesst den Rahmen nicht (Nutzerwunsch 2026-11-22). */
const val MIN_ZOOM_OUT = 1.0

/** Ein Punkt auf der Zeichenflaeche in Bildpunkten, Ursprung links oben. */
data class ScreenPoint(val x: Double, val y: Double)

/**
 * Die Ansicht: [cx], [cy] ist der Punkt der Kartenebene in der Mitte der Flaeche, [pxPerUnit] der Massstab (Bildpunkte je Einheit der Ebene). Die Ansicht kennt die Groesse der Flaeche
 * nicht — die kommt bei jeder Umrechnung mit, damit eine Drehung (andere Flaeche) die Ansicht nicht verschiebt.
 *
 * [rotationDeg] ist der Kurs (0 = Nord, im Uhrzeigersinn), der im Bild **oben** steht: 0 = Norden oben, 90 = Osten oben. [anchorY] ist die Hoehe (Anteil der Flaeche von oben), auf der der
 * Punkt ([cx], [cy]) gezeichnet wird und um die gedreht wird: 0,5 = Mitte.
 */
data class TrackView(val cx: Double, val cy: Double, val pxPerUnit: Double, val rotationDeg: Double = 0.0, val anchorY: Double = 0.5)

/** Der y-Wert der Mercator-Ebene zu einer Breite in Grad. */
fun projectLat(latDeg: Double): Double {
    val phi = Math.toRadians(latDeg.coerceIn(-MAX_LAT_DEG, MAX_LAT_DEG))
    return ln(tan(PI / 4 + phi / 2))
}

/** Die Breite in Grad zu einem y-Wert der Mercator-Ebene (die Umkehrung von [projectLat]). */
fun unprojectLat(y: Double): Double = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(y)))

/** Der x-Wert der Ebene zu einer Laenge in Grad, gemessen von [refLonDeg] (der kurze Weg ueber die Datumsgrenze). */
fun projectLon(lonDeg: Double, refLonDeg: Double): Double = Math.toRadians(wrapLonDegrees(lonDeg - refLonDeg))

/** Der Bildpunkt zu einem Punkt der Ebene; ohne Drehung ist Norden oben, sonst steht der Kurs [TrackView.rotationDeg] oben. */
fun toScreen(view: TrackView, widthPx: Double, heightPx: Double, x: Double, y: Double): ScreenPoint {
    val dx = (x - view.cx) * view.pxPerUnit
    val dy = -(y - view.cy) * view.pxPerUnit
    if (view.rotationDeg == 0.0) return ScreenPoint(widthPx / 2 + dx, heightPx * view.anchorY + dy)
    val r = Math.toRadians(view.rotationDeg)
    val c = cos(r)
    val s = kotlin.math.sin(r)
    // Die Karte dreht sich gegen den Uhrzeigersinn um den Kurs, damit er nach oben zeigt.
    return ScreenPoint(widthPx / 2 + dx * c + dy * s, heightPx * view.anchorY - dx * s + dy * c)
}

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
 * Der Rahmen einer Zeichnung: die Flaeche ([widthPx] mal [heightPx]), der Rand [paddingPx] und das Rechteck des Tracks in der Kartenebene. Die Ansicht muss darin bleiben: ihr sichtbarer
 * Ausschnitt liegt immer **im Rechteck des Tracks samt Rand** — der Track verlaesst den Rahmen nie. Ist der Ausschnitt in einer Richtung groesser als das Rechteck (angepasst und kaum
 * hineingezoomt), steht die Mitte fest auf der Mitte des Tracks.
 */
data class TrackFrame(
    val widthPx: Double,
    val heightPx: Double,
    val paddingPx: Double,
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val fit: TrackView,
) {
    /** Schiebt die Mitte von [view] so weit, dass der Ausschnitt im Rechteck des Tracks samt Rand liegt. */
    fun clamp(view: TrackView): TrackView {
        val ppu = view.pxPerUnit
        val pad = paddingPx / ppu
        val halfW = widthPx / 2 / ppu
        val halfH = heightPx / 2 / ppu
        return view.copy(cx = clampAxis(view.cx, minX - pad + halfW, maxX + pad - halfW, (minX + maxX) / 2), cy = clampAxis(view.cy, minY - pad + halfH, maxY + pad - halfH, (minY + maxY) / 2))
    }

    private fun clampAxis(value: Double, lo: Double, hi: Double, middle: Double) = if (lo > hi) middle else value.coerceIn(lo, hi)

    /** Zoomt um [factor] so, dass der Punkt unter dem Finger ([focusX], [focusY] in Bildpunkten) stehen bleibt; Massstab und Rahmen wie [zoomBy] und [clamp]. */
    fun zoomAround(view: TrackView, factor: Double, focusX: Double, focusY: Double): TrackView {
        val zoomed = zoomBy(view, factor, fit)
        val mapX = view.cx + (focusX - widthPx / 2) / view.pxPerUnit
        val mapY = view.cy - (focusY - heightPx / 2) / view.pxPerUnit
        val moved = zoomed.copy(cx = mapX - (focusX - widthPx / 2) / zoomed.pxPerUnit, cy = mapY + (focusY - heightPx / 2) / zoomed.pxPerUnit)
        return clamp(moved)
    }
}

/** Der Massstab (Bildpunkte je Einheit der Ebene), bei dem die kuerzere Seite einer Flaeche von [widthPx] mal [heightPx] genau [spanM] Meter auf der Breite [latDeg] zeigt. */
fun ppuForSpan(widthPx: Double, heightPx: Double, spanM: Double, latDeg: Double): Double =
    min(widthPx, heightPx) / (spanM / (EARTH_RADIUS_M * cos(Math.toRadians(latDeg))))

/**
 * Die Ansicht, die der Position folgt (Norden oben, Nachfahren): Mitte auf der Position, Massstab fuer [spanM] Meter auf der kurzen Seite — nie kleiner als die angepasste Ansicht —, und
 * im Rahmen: der Ausschnitt bleibt im Rechteck des Tracks samt Rand, der Track verlaesst den Rahmen auch hier nie (nahe dem Rand des Tracks steht die Position dann nicht in der Mitte).
 */
fun TrackFrame.followView(positionX: Double, positionY: Double, spanM: Double): TrackView {
    val ppu = max(fit.pxPerUnit, ppuForSpan(widthPx, heightPx, spanM, unprojectLat(positionY)))
    return clamp(TrackView(cx = positionX, cy = positionY, pxPerUnit = ppu))
}

/** Der Rahmen eines Tracks mit dem Rechteck [bounds] auf einer Flaeche; seine angepasste Ansicht ist [fitView]. */
fun trackFrame(bounds: TrackBounds, widthPx: Double, heightPx: Double, paddingPx: Double): TrackFrame = TrackFrame(
    widthPx = widthPx,
    heightPx = heightPx,
    paddingPx = paddingPx,
    minX = 0.0,
    maxX = projectLon(bounds.maxLon, bounds.minLon),
    minY = projectLat(bounds.minLat),
    maxY = projectLat(bounds.maxLat),
    fit = fitView(bounds, widthPx, heightPx, paddingPx),
)

/**
 * Wo ein Punkt gezeichnet wird, der ausserhalb der Flaeche liegt: auf dem Rand ([marginPx] innen), auf dem Strahl von der Mitte zum Punkt. Liegt er im Bild, bleibt er, wo er ist.
 * So bleibt die eigene Position auch dann sichtbar, wenn sie weit vom Track entfernt ist.
 */
fun clampToFrame(x: Double, y: Double, widthPx: Double, heightPx: Double, marginPx: Double): ScreenPoint {
    val dx = x - widthPx / 2
    val dy = y - heightPx / 2
    val halfW = max(0.0, widthPx / 2 - marginPx)
    val halfH = max(0.0, heightPx / 2 - marginPx)
    val tx = if (dx == 0.0) Double.POSITIVE_INFINITY else halfW / kotlin.math.abs(dx)
    val ty = if (dy == 0.0) Double.POSITIVE_INFINITY else halfH / kotlin.math.abs(dy)
    val t = min(tx, ty)
    if (t >= 1.0) return ScreenPoint(x, y)
    return ScreenPoint(widthPx / 2 + dx * t, heightPx / 2 + dy * t)
}

/**
 * Was der Nutzer an der Ansicht verstellt hat, **relativ zur angepassten Ansicht**: [zoom] ist ein Vielfaches ihres Massstabs, [dx] und [dy] sind die Verschiebung ihrer Mitte in Einheiten der
 * Kartenebene. So ueberlebt der Zustand eine Drehung (die Flaeche wird eine andere, die angepasste Ansicht mit ihr): derselbe Ausschnitt der Gegend bleibt im Bild, statt dass dieselben
 * Bildpunkte gelten. Der Ausgangszustand [TrackViewState] ist die angepasste Ansicht. Jede Aenderung bleibt im [TrackFrame]: der Track verlaesst den Rahmen nie.
 */
data class TrackViewState(val zoom: Double = 1.0, val dx: Double = 0.0, val dy: Double = 0.0) {

    /** Die Ansicht dieses Zustands auf einer Flaeche, deren angepasste Ansicht [fit] ist (ohne den Rahmen, s. [resolved]). */
    fun resolve(fit: TrackView) = TrackView(cx = fit.cx + dx, cy = fit.cy + dy, pxPerUnit = fit.pxPerUnit * zoom)

    /** Die Ansicht im Rahmen: nach einer Drehung kann der gespeicherte Zustand auf der neuen Flaeche ausserhalb liegen und wird zurueckgeholt. */
    fun resolved(frame: TrackFrame): TrackView = frame.clamp(resolve(frame.fit))

    /** Zoomt um [factor] um die Mitte, begrenzt wie [zoomBy] und im Rahmen. */
    fun zoomedBy(factor: Double, frame: TrackFrame): TrackViewState = zoomedAround(factor, frame.widthPx / 2, frame.heightPx / 2, frame)

    /** Zoomt um [factor] um den Punkt ([focusX], [focusY]) in Bildpunkten, der unter dem Finger stehen bleibt. */
    fun zoomedAround(factor: Double, focusX: Double, focusY: Double, frame: TrackFrame): TrackViewState =
        from(frame.zoomAround(resolved(frame), factor, focusX, focusY), frame.fit)

    /** Verschiebt den Inhalt um [dxPx], [dyPx] Bildpunkte, wie [panBy], aber nie aus dem Rahmen. */
    fun pannedBy(dxPx: Double, dyPx: Double, frame: TrackFrame): TrackViewState = from(frame.clamp(panBy(resolved(frame), dxPx, dyPx)), frame.fit)

    /** Eine Geste mit zwei Fingern (oder einem): erst zoomen um den Mittelpunkt der Finger, dann verschieben; beides im Rahmen. */
    fun transformedBy(centroidX: Double, centroidY: Double, panXPx: Double, panYPx: Double, zoomFactor: Double, frame: TrackFrame): TrackViewState =
        zoomedAround(zoomFactor, centroidX, centroidY, frame).pannedBy(panXPx, panYPx, frame)

    /** Zurueck auf die angepasste Ansicht. */
    fun fitted() = TrackViewState()

    private fun from(view: TrackView, fit: TrackView) = TrackViewState(view.pxPerUnit / fit.pxPerUnit, view.cx - fit.cx, view.cy - fit.cy)

    companion object {
        /** Der Zustand, der genau die Ansicht [view] ergibt (relativ zur angepassten [fit]): so uebernimmt eine Handbewegung die Ansicht, die gerade der Position folgte. */
        fun of(view: TrackView, fit: TrackView) = TrackViewState(view.pxPerUnit / fit.pxPerUnit, view.cx - fit.cx, view.cy - fit.cy)
    }
}


/**
 * Wo der Abstandstext steht: **neben** der Linie vom Fusspunkt ([footX], [footY]) zur Position ([posX], [posY]), nicht auf ihr — sonst verdeckt er bei kurzem Abstand die ganze Linie. Das ist die
 * obere linke Ecke des Textfelds ([textW] mal [textH] Bildpunkte). Das Feld sitzt senkrecht zur Linie auf der Mitte, so weit weg, dass es die Linie nicht beruehrt ([gapPx] Luft), und bevorzugt
 * **oberhalb**; liegt es dort ausserhalb der Flaeche ([widthPx] mal [heightPx], Rand [padPx]), nimmt es die andere Seite, wenn dort weniger fehlt. Liegt auch das ausserhalb, wird es in die
 * Flaeche geschoben (dann kann es die Linie beruehren).
 */
fun distanceLabelTopLeft(
    footX: Double, footY: Double, posX: Double, posY: Double,
    textW: Double, textH: Double, gapPx: Double, widthPx: Double, heightPx: Double, padPx: Double,
): ScreenPoint {
    val midX = (footX + posX) / 2
    val midY = (footY + posY) / 2
    val dx = posX - footX
    val dy = posY - footY
    val len = kotlin.math.hypot(dx, dy)
    // Ohne Linie (Laenge 0) bleibt nur der Mittelpunkt; gerufen wird das nur fuer eine sichtbare Linie.
    val nx = if (len == 0.0) 0.0 else -dy / len
    val ny = if (len == 0.0) -1.0 else dx / len
    val reach = kotlin.math.abs(nx) * textW / 2 + kotlin.math.abs(ny) * textH / 2 + gapPx
    fun overflow(cx: Double, cy: Double): Double {
        val l = cx - textW / 2
        val t = cy - textH / 2
        return max(0.0, padPx - l) + max(0.0, l + textW - (widthPx - padPx)) + max(0.0, padPx - t) + max(0.0, t + textH - (heightPx - padPx))
    }
    // Die Seite nach oben (ny < 0) zuerst, bei einer senkrechten Linie die nach rechts.
    val upSign = if (ny < 0 || (ny == 0.0 && nx > 0)) 1.0 else -1.0
    val preferredX = midX + nx * reach * upSign
    val preferredY = midY + ny * reach * upSign
    val otherX = midX - nx * reach * upSign
    val otherY = midY - ny * reach * upSign
    val (cx, cy) = if (overflow(otherX, otherY) < overflow(preferredX, preferredY)) otherX to otherY else preferredX to preferredY
    val left = (cx - textW / 2).coerceIn(padPx, max(padPx, widthPx - textW - padPx))
    val top = (cy - textH / 2).coerceIn(padPx, max(padPx, heightPx - textH - padPx))
    return ScreenPoint(left, top)
}

/** So viele Meter zeigt die kuerzere Seite der Flaeche in der Ansicht „Fahrtrichtung oben“ bei Zoom 1 (rund 400 m: die naechsten Kurven vor einem). */
const val HEADING_DEFAULT_SPAN_M = 400.0

/** Auf dieser Hoehe (Anteil von oben) steht die eigene Position in der Ansicht „Fahrtrichtung oben“: unten im Bild, damit mehr vom Weg voraus zu sehen ist. */
const val HEADING_ANCHOR_Y = 0.65

/** Wie weit man in der Ansicht „Fahrtrichtung oben“ hinein- und herauszoomen darf, als Vielfaches von Zoom 1 (rund 50 m bis 3,2 km). */
const val HEADING_MIN_ZOOM = 0.125
const val HEADING_MAX_ZOOM = 8.0

/**
 * Die Ansicht „Fahrtrichtung oben“: die Karte folgt der eigenen Position und ist so gedreht, dass der Kurs oben steht. [zoom] ist ein Vielfaches des Massstabs, der [HEADING_DEFAULT_SPAN_M]
 * auf die kuerzere Seite der Flaeche bringt; ([offsetX], [offsetY]) ist die Verschiebung der Position aus ihrem Ruheplatz ([HEADING_ANCHOR_Y], Mitte) in Bildpunkten: der Nutzer kann mit einem
 * Finger vorausschauen. Alles hier ist Bildschirmgeometrie um die Position — die Weltkoordinaten kommen erst in [HeadingFrame.viewFor] dazu. Die Position bleibt immer im Bild
 * ([HeadingFrame.marginPx] innen), sie ist nie weggeschoben.
 */
data class HeadingViewState(val zoom: Double = 1.0, val offsetX: Double = 0.0, val offsetY: Double = 0.0)

/** Die Flaeche der Ansicht „Fahrtrichtung oben“ ([widthPx] mal [heightPx]) und der Rand [marginPx], den die Position zum Bildrand haelt. */
data class HeadingFrame(val widthPx: Double, val heightPx: Double, val marginPx: Double) {
    private val anchorX get() = widthPx / 2
    private val anchorY get() = heightPx * HEADING_ANCHOR_Y

    /** Wo die Position bei [state] im Bild steht. */
    fun markerOf(state: HeadingViewState) = ScreenPoint(anchorX + state.offsetX, anchorY + state.offsetY)

    private fun clamped(x: Double, y: Double, zoom: Double): HeadingViewState {
        val mx = x.coerceIn(min(marginPx, widthPx / 2), max(widthPx - marginPx, widthPx / 2))
        val my = y.coerceIn(min(marginPx, heightPx / 2), max(heightPx - marginPx, heightPx / 2))
        return HeadingViewState(zoom = zoom, offsetX = mx - anchorX, offsetY = my - anchorY)
    }

    /** Verschiebt den Inhalt um [dxPx], [dyPx] Bildpunkte (wie ein Finger auf Papier); die Position bleibt im Bild. */
    fun pannedBy(state: HeadingViewState, dxPx: Double, dyPx: Double): HeadingViewState {
        val m = markerOf(state)
        return clamped(m.x + dxPx, m.y + dyPx, state.zoom)
    }

    /** Zoomt um [factor] um den Punkt ([focusX], [focusY]): der Punkt der Karte unter dem Finger bleibt stehen, die Position wandert mit; begrenzt auf [HEADING_MIN_ZOOM] bis [HEADING_MAX_ZOOM]. */
    fun zoomedAround(state: HeadingViewState, factor: Double, focusX: Double, focusY: Double): HeadingViewState {
        val zoom = (state.zoom * factor).coerceIn(HEADING_MIN_ZOOM, HEADING_MAX_ZOOM)
        val k = zoom / state.zoom
        val m = markerOf(state)
        return clamped(focusX + (m.x - focusX) * k, focusY + (m.y - focusY) * k, zoom)
    }

    /** Eine Geste mit einem oder zwei Fingern: erst zoomen um den Mittelpunkt der Finger, dann verschieben. */
    fun transformedBy(state: HeadingViewState, centroidX: Double, centroidY: Double, panXPx: Double, panYPx: Double, zoomFactor: Double): HeadingViewState =
        pannedBy(zoomedAround(state, zoomFactor, centroidX, centroidY), panXPx, panYPx)

    /**
     * Die Ansicht der Karte: [positionX], [positionY] ist die Position in der Kartenebene, [rotationDeg] der Kurs, der oben steht. [spanM] sind die Meter, die die kuerzere Seite der Flaeche
     * bei Zoom 1 zeigt: [HEADING_DEFAULT_SPAN_M], beim Nachfahren die Spanne nach Geschwindigkeit ([de.adv990de.obd2.track.AutoZoom]); der Zoom des Nutzers ist ein Faktor darauf.
     */
    fun viewFor(state: HeadingViewState, positionX: Double, positionY: Double, rotationDeg: Double, spanM: Double = HEADING_DEFAULT_SPAN_M): TrackView {
        val latDeg = unprojectLat(positionY)
        val ppu = ppuForSpan(widthPx, heightPx, spanM, latDeg) * state.zoom
        // Der Mittelpunkt der Ansicht liegt auf dem Ruheplatz; die Position steht um (offsetX, offsetY) daneben. Rueckwaerts aus dem Bild in die Ebene rechnen (die Drehung umkehren).
        val r = Math.toRadians(rotationDeg)
        val c = cos(r)
        val s = kotlin.math.sin(r)
        val dx = state.offsetX * c - state.offsetY * s
        val dy = state.offsetX * s + state.offsetY * c
        return TrackView(cx = positionX - dx / ppu, cy = positionY + dy / ppu, pxPerUnit = ppu, rotationDeg = rotationDeg, anchorY = HEADING_ANCHOR_Y)
    }
}

/** Der kuerzeste Winkel von [from] nach [to] in Grad, im Bereich (−180, 180]: so dreht die Karte nie den langen Weg. */
fun shortestTurnDeg(from: Double, to: Double): Double {
    val d = ((to - from) % 360.0 + 360.0) % 360.0
    return if (d > 180.0) d - 360.0 else d
}
