package de.adv990de.obd2.map

import de.adv990de.obd2.ui.tracks.ScreenPoint
import de.adv990de.obd2.ui.tracks.TrackView
import de.adv990de.obd2.ui.tracks.projectLat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/*
 * Die Mathematik zwischen Web-Mercator-Kacheln und der Ebene der Track-Zeichnung (`TrackCanvasLayout.kt`). Beides ist dieselbe Mercator-Ebene: x = Laengenunterschied zu einer Bezugslaenge
 * im Bogenmass, y = der Mercator-y-Wert der Breite. Eine Kachel der Stufe z ist 2π / 2^z Einheiten breit und hoch; ihre obere linke Ecke liegt bei der Laenge `x / 2^z · 360 − 180` und dem
 * y-Wert `π · (1 − 2 y / 2^z)`. Deshalb ist die Umrechnung ein einziges affines Abbild je Kachel — die Drehung („Fahrtrichtung oben“), der Zoom und die Lage der Ansicht stecken darin.
 */

/** So gross soll eine Kachel auf dem Bildschirm etwa sein (Bildpunkte); die Stufe ist die, bei der sie zwischen diesem und dem doppelten Wert liegt. */
const val TILE_TARGET_PX = 384.0

/** So viele Kacheln liefert [visibleTiles] hoechstens (die naechsten zur Mitte). */
const val MAX_VISIBLE_TILES = 64

/** Wie viele Kandidaten [visibleTiles] hoechstens prueft; mehr deutet auf unsinnige Eingaben. */
private const val MAX_CANDIDATES = 10_000

/** Die Seitenlaenge einer Kachel der Stufe [z] in Einheiten der Ebene. */
fun tileSizeUnits(z: Int): Double = 2 * PI / 2.0.pow(z)

/** Die Laenge (Grad) der westlichen Kante der Spalte [x] auf Stufe [z]. */
fun tileWestDeg(x: Int, z: Int): Double = x / 2.0.pow(z) * 360.0 - 180.0

/** Der y-Wert der Ebene der noerdlichen Kante der Zeile [y] auf Stufe [z]. */
fun tileNorthY(y: Int, z: Int): Double = PI * (1 - 2 * y / 2.0.pow(z))

/** Die Kachel, in der ein Ort liegt (die Kachelraender gehoeren zur oestlichen bzw. suedlichen Kachel); Werte ausserhalb des Rasters werden an den Rand geklemmt. */
fun tileContaining(latDeg: Double, lonDeg: Double, z: Int): TileKey {
    val n = 2.0.pow(z)
    val tx = (lonDeg + 180.0) / 360.0 * n
    val ty = (1 - projectLat(latDeg) / PI) / 2 * n
    return TileKey(z, clampTile(tx, z), clampTile(ty, z))
}

private fun clampTile(fraction: Double, z: Int): Int {
    if (fraction.isNaN()) return 0
    val last = (1L shl z) - 1
    return floor(fraction).coerceIn(0.0, last.toDouble()).toInt()
}

/**
 * Die Stufe, deren Kacheln bei diesem Massstab ([pxPerUnit], Bildpunkte je Einheit der Ebene) etwa [TILE_TARGET_PX] gross sind, geklemmt auf [minZ]..[maxZ] der Quelle. Ueber der hoechsten
 * Stufe der Quelle werden deren Kacheln vergroessert („Ueberzoom“), darunter bleibt es bei der kleinsten; das Abbild ([tileToScreen]) rechnet jeden Massstab, die Stufe waehlt nur die Genauigkeit.
 */
fun zoomFor(pxPerUnit: Double, minZ: Int, maxZ: Int): Int {
    if (maxZ < minZ) return minZ
    if (!(pxPerUnit > 0) || pxPerUnit.isInfinite()) return minZ
    val z = floor(ln(pxPerUnit * 2 * PI / TILE_TARGET_PX) / ln(2.0))
    return z.coerceIn(minZ.toDouble(), maxZ.toDouble()).toInt()
}

/** Ein affines Abbild von Kachelkoordinaten (u, v) auf Bildpunkte: `x = a·u + c·v + e`, `y = b·u + d·v + f` (Reihenfolge wie `android.graphics.Matrix.setValues`-Spalten). */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    fun map(u: Double, v: Double) = ScreenPoint(a * u + c * v + e, b * u + d * v + f)
}

/**
 * Das Abbild der Kachelkoordinaten der Kachel [key] (0 bis [extent], y nach unten) auf die Zeichenflaeche [widthPx] mal [heightPx] unter der Ansicht [view]; [refLonDeg] ist die Bezugslaenge der
 * Ebene (bei Tracks die westliche Kante). Dieselbe Rechnung wie `toScreen` (mit Drehung und `anchorY`), nur im Voraus zu einem Abbild zusammengezogen.
 */
fun tileToScreen(view: TrackView, widthPx: Double, heightPx: Double, refLonDeg: Double, key: TileKey, extent: Int): Affine {
    val k = tileSizeUnits(key.z) / extent
    val x0 = Math.toRadians(tileWestDeg(key.x, key.z) - refLonDeg)
    val yTop = tileNorthY(key.y, key.z)
    val r = Math.toRadians(view.rotationDeg)
    val c = cos(r)
    val s = sin(r)
    val ppu = view.pxPerUnit
    val ox = ppu * (x0 - view.cx)
    val oy = ppu * (yTop - view.cy)
    return Affine(
        a = ppu * k * c,
        b = -ppu * k * s,
        c = ppu * k * s,
        d = ppu * k * c,
        e = widthPx / 2 + ox * c - oy * s,
        f = heightPx * view.anchorY - ox * s - oy * c,
    )
}

/** Ein Bildpunkt zurueck in die Ebene (x, y) — die Umkehrung von `toScreen`. */
internal fun toPlane(view: TrackView, widthPx: Double, heightPx: Double, sx: Double, sy: Double): Pair<Double, Double> {
    val x = sx - widthPx / 2
    val y = sy - heightPx * view.anchorY
    val r = Math.toRadians(view.rotationDeg)
    val c = cos(r)
    val s = sin(r)
    val dx = x * c - y * s
    val dy = x * s + y * c
    return (view.cx + dx / view.pxPerUnit) to (view.cy - dy / view.pxPerUnit)
}

/**
 * Die Kacheln der Stufe [z], die die Zeichenflaeche (um [marginPx] Bildpunkte rundum vergroessert) beruehren, die naechsten zur Mitte zuerst, hoechstens [maxTiles]. Auch bei Drehung: eine Kachel
 * zaehlt nur, wenn ihr gedrehtes Viereck das Rechteck der Flaeche wirklich schneidet (nicht schon, wenn es im umschliessenden Rechteck liegt). Leer bei unsinnigen Eingaben.
 */
fun visibleTiles(
    view: TrackView,
    widthPx: Double,
    heightPx: Double,
    refLonDeg: Double,
    z: Int,
    marginPx: Double = 0.0,
    maxTiles: Int = MAX_VISIBLE_TILES,
): List<TileKey> {
    if (z !in 0..MAX_TILE_ZOOM || maxTiles <= 0) return emptyList()
    if (!(widthPx > 0 && heightPx > 0 && view.pxPerUnit > 0) || listOf(view.cx, view.cy, view.pxPerUnit, view.rotationDeg, view.anchorY, refLonDeg, marginPx).any { !it.isFinite() }) return emptyList()
    val x0 = -marginPx
    val y0 = -marginPx
    val x1 = widthPx + marginPx
    val y1 = heightPx + marginPx
    // Umschliessendes Rechteck der Flaeche in der Ebene, daraus der Bereich der Kachelnummern.
    val corners = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1).map { toPlane(view, widthPx, heightPx, it.first, it.second) }
    val n = 2.0.pow(z)
    fun tileX(planeX: Double) = (Math.toDegrees(planeX) + refLonDeg + 180.0) / 360.0 * n
    fun tileY(planeY: Double) = (1 - planeY / PI) / 2 * n
    val minTx = corners.minOf { tileX(it.first) }
    val maxTx = corners.maxOf { tileX(it.first) }
    val minTy = corners.minOf { tileY(it.second) }
    val maxTy = corners.maxOf { tileY(it.second) }
    if (listOf(minTx, maxTx, minTy, maxTy).any { !it.isFinite() }) return emptyList()
    val last = n - 1
    val fromX = floor(minTx).coerceIn(0.0, last).toInt()
    val toX = floor(maxTx).coerceIn(0.0, last).toInt()
    val fromY = floor(minTy).coerceIn(0.0, last).toInt()
    val toY = floor(maxTy).coerceIn(0.0, last).toInt()
    if ((toX - fromX + 1).toLong() * (toY - fromY + 1) > MAX_CANDIDATES) return emptyList()
    val found = ArrayList<Pair<TileKey, Double>>()
    for (tx in fromX..toX) for (ty in fromY..toY) {
        val key = TileKey(z, tx, ty)
        val m = tileToScreen(view, widthPx, heightPx, refLonDeg, key, 1)
        val poly = listOf(m.map(0.0, 0.0), m.map(1.0, 0.0), m.map(1.0, 1.0), m.map(0.0, 1.0))
        if (!intersectsRect(poly, x0, y0, x1, y1)) continue
        val centre = m.map(0.5, 0.5)
        val dx = centre.x - widthPx / 2
        val dy = centre.y - heightPx / 2
        found.add(key to dx * dx + dy * dy)
    }
    return found.sortedBy { it.second }.take(maxTiles).map { it.first }
}

/** Schneiden sich das konvexe Viereck [poly] und das Rechteck? (Trennachsen: die zwei Achsen des Rechtecks und die zwei Kantenrichtungen des Vierecks.) */
internal fun intersectsRect(poly: List<ScreenPoint>, x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
    if (poly.maxOf { it.x } < x0 || poly.minOf { it.x } > x1) return false
    if (poly.maxOf { it.y } < y0 || poly.minOf { it.y } > y1) return false
    val rect = listOf(ScreenPoint(x0, y0), ScreenPoint(x1, y0), ScreenPoint(x1, y1), ScreenPoint(x0, y1))
    for (i in 0 until 2) {
        val ex = poly[i + 1].x - poly[i].x
        val ey = poly[i + 1].y - poly[i].y
        val nx = -ey
        val ny = ex
        val pMin = poly.minOf { it.x * nx + it.y * ny }
        val pMax = poly.maxOf { it.x * nx + it.y * ny }
        val rMin = rect.minOf { it.x * nx + it.y * ny }
        val rMax = rect.maxOf { it.x * nx + it.y * ny }
        if (pMax < rMin || rMax < pMin) return false
    }
    return true
}

// ── Korridor um einen Track ────────────────────────────────────────────────────────────────────────────────────────

/** Ein Ort in Grad. */
data class LatLon(val lat: Double, val lon: Double)

/**
 * Die Kacheln, die ein Track braucht: [tiles] (Stufe aufsteigend, je Stufe in der Reihenfolge entlang des Tracks), [count] ihre Zahl und [tooLarge], wenn mehr als `maxTiles` noetig waeren — dann
 * ist [tiles] leer und [count] die Zahl, bei der abgebrochen wurde (mindestens `maxTiles + 1`).
 */
class CorridorPlan(val tiles: List<TileKey>, val count: Int, val tooLarge: Boolean) {
    val byZoom: Map<Int, Int> get() = tiles.groupingBy { it.z }.eachCount()
}

/**
 * Der Korridor um [points]: je Stufe von [minZ] bis [maxZ] alle Kacheln, die der Track beruehrt, plus [bufferTiles] Kacheln Rand rundum. Der Weg zwischen zwei Punkten wird als gerade Linie der
 * Ebene genommen (wie die Zeichnung). Hoechstens [maxTiles] Kacheln, sonst `tooLarge`. Ein leerer Track hat keine Kacheln.
 */
fun corridorTiles(points: List<LatLon>, minZ: Int, maxZ: Int, bufferTiles: Int, maxTiles: Int): CorridorPlan {
    val valid = points.filter { it.lat.isFinite() && it.lon.isFinite() }
    if (valid.isEmpty() || minZ > maxZ || maxTiles <= 0) return CorridorPlan(emptyList(), 0, false)
    val buffer = bufferTiles.coerceAtLeast(0)
    val lo = minZ.coerceIn(0, MAX_TILE_ZOOM)
    val hi = maxZ.coerceIn(0, MAX_TILE_ZOOM)
    val all = ArrayList<TileKey>()
    for (z in lo..hi) {
        val n = 2.0.pow(z)
        val last = (1L shl z) - 1
        val touched = LinkedHashSet<TileKey>()
        // Kachelkoordinaten (Bruchteile) der Punkte; der Weg dazwischen wird in Schritten von hoechstens einer viertel Kachel abgetastet.
        val coords = valid.map { ((it.lon + 180.0) / 360.0 * n) to ((1 - projectLat(it.lat) / PI) / 2 * n) }
        fun add(tx: Double, ty: Double) {
            val cx = floor(tx).coerceIn(0.0, last.toDouble()).toLong()
            val cy = floor(ty).coerceIn(0.0, last.toDouble()).toLong()
            for (dx in -buffer..buffer) for (dy in -buffer..buffer) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0..last && y in 0..last) touched.add(TileKey(z, x.toInt(), y.toInt()))
            }
        }
        add(coords[0].first, coords[0].second)
        for (i in 1 until coords.size) {
            val (ax, ay) = coords[i - 1]
            val (bx, by) = coords[i]
            val lengthTiles = max(kotlin.math.abs(bx - ax), kotlin.math.abs(by - ay))
            // Ein Weg von L Kacheln beruehrt mindestens L Kacheln: ist L schon ueber der Grenze, ist der Korridor zu gross (und die Abtastung wuerde endlos dauern).
            if (lengthTiles > maxTiles) return CorridorPlan(emptyList(), maxTiles + 1, true)
            val steps = max(1, kotlin.math.ceil(lengthTiles * 4).toInt())
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                add(ax + (bx - ax) * t, ay + (by - ay) * t)
            }
            if (all.size + touched.size > maxTiles) return CorridorPlan(emptyList(), all.size + touched.size, true)
        }
        all.addAll(touched)
        if (all.size > maxTiles) return CorridorPlan(emptyList(), all.size, true)
    }
    return CorridorPlan(all, all.size, false)
}
