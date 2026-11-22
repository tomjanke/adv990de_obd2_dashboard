package de.adv990de.obd2.ui.tracks

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import de.adv990de.obd2.map.DrawOp
import de.adv990de.obd2.map.MapPalette
import de.adv990de.obd2.map.MapProfile
import de.adv990de.obd2.map.MapStyle
import de.adv990de.obd2.map.MapTiles
import de.adv990de.obd2.map.TileKey
import de.adv990de.obd2.map.TileScene
import de.adv990de.obd2.map.TileSceneCache
import de.adv990de.obd2.map.buildTileScene
import de.adv990de.obd2.map.decodeVectorTile
import de.adv990de.obd2.map.tileToScreen
import de.adv990de.obd2.map.visibleTiles
import de.adv990de.obd2.map.zoomFor
import de.adv990de.obd2.ui.theme.MapColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/*
 * Die Kartenebene der Trackansicht: Kacheln der eingespielten Datei hinter der Linie des Tracks. Sie holt die sichtbaren Kacheln im Hintergrund (Lesen und Dekodieren ausserhalb des
 * Hauptthreads), haelt fertige Pfade je Kachel und zeichnet sie mit einem Abbild je Kachel (`tileToScreen`) — Drehung, Zoom und Lage der Ansicht stecken darin. Ohne Karte ([MapTiles] `null`)
 * wird nichts davon angefasst: die Zeichnung ist dann Bildpunkt fuer Bildpunkt die ohne Kartenebene.
 */

/** Eine Kachel mit fertigen Pfaden in Kachelkoordinaten, nach [DrawOp.order] aufsteigend. */
internal class ScenePaths(val extent: Int, val groups: List<PathGroup>)

internal class PathGroup(val op: DrawOp, val color: Color, val path: Path)

/** Macht aus einer Szene Pfade (Flaechen mit der Gerade-Ungerade-Regel: innere Ringe sind Loecher). */
internal fun TileScene.toPaths(): ScenePaths = ScenePaths(
    extent,
    groups.map { g ->
        val path = Path()
        if (g.op is DrawOp.Fill) path.fillType = PathFillType.EvenOdd
        for (part in g.parts) {
            path.moveTo(part[0], part[1])
            var i = 2
            while (i + 1 < part.size) {
                path.lineTo(part[i], part[i + 1])
                i += 2
            }
            if (g.op is DrawOp.Fill) path.close()
        }
        PathGroup(g.op, Color(when (val o = g.op) { is DrawOp.Fill -> o.argb; is DrawOp.Line -> o.argb }), path)
    },
)

/** Der Zustand der Kartenebene einer Ansicht: die Kacheln samt Ablage; [revision] steigt mit jeder geladenen Kachel (das Zeichnen liest sie, damit es neu zeichnet). */
internal class MapTileLayerState(val tiles: MapTiles, val cache: TileSceneCache<ScenePaths>, val revision: State<Int>)

/** Die Karte hinter der Ansicht, oder `null` ohne Karte. Neu gebaut, wenn sich die Karte oder die Farben aendern (der Stil steckt in den Pfaden). */
@Composable
internal fun rememberMapTileLayer(tiles: MapTiles?): MapTileLayerState? {
    if (tiles == null) return null
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme.surface, scheme.onSurface, scheme.primary) {
        MapPalette.derive(scheme.surface.toArgbInt(), scheme.onSurface.toArgbInt(), MapColors.tints(), MapProfile.TRACK_VIEW, scheme.primary.toArgbInt())
    }
    val style = remember(palette) { MapStyle(palette, MapProfile.TRACK_VIEW) }
    val revision = remember(tiles, style) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    return remember(tiles, style) {
        val cache = TileSceneCache<ScenePaths>(capacity = TILE_CACHE_SIZE, scope = scope, onLoaded = { revision.intValue++ }) { key ->
            val bytes = tiles.archive.tile(key) ?: return@TileSceneCache null
            withContext(Dispatchers.Default) {
                val tile = decodeVectorTile(bytes) ?: return@withContext null
                buildTileScene(tile, style, key.z.toDouble()).toPaths()
            }
        }
        MapTileLayerState(tiles, cache, revision)
    }
}

private fun Color.toArgbInt(): Int = toArgb()

/** So viele Kacheln haelt die Ablage (rund zwei Bildschirme voll plus die Stufe darueber). */
private const val TILE_CACHE_SIZE = 160

/** So weit ausserhalb des Bildes werden Kacheln schon geholt (Bildpunkte). */
private const val PRELOAD_MARGIN_PX = 96.0

/** Die Kacheln, die [view] zeigt, und die der Stufe darunter (sie decken die Luecken, solange die feinere Stufe laedt). */
internal class VisibleTiles(val keys: List<TileKey>, val parents: List<TileKey>)

internal fun MapTileLayerState.visibleFor(view: TrackView, widthPx: Double, heightPx: Double, refLonDeg: Double): VisibleTiles {
    val header = tiles.archive.header
    val z = zoomFor(view.pxPerUnit, header.minZoom, header.maxZoom)
    val keys = visibleTiles(view, widthPx, heightPx, refLonDeg, z, PRELOAD_MARGIN_PX)
    val parents = if (z - 1 >= header.minZoom) keys.map { TileKey(z - 1, it.x / 2, it.y / 2) }.distinct() else emptyList()
    return VisibleTiles(keys, parents)
}

/** Bestellt die Kacheln in der Reihenfolge: erst die der Stufe darunter (sie sind sofort nuetzlich), dann die feinen. */
@Composable
internal fun MapTileLayerState.RequestTiles(visible: VisibleTiles) {
    LaunchedEffect(this, visible.keys) { cache.request(visible.parents + visible.keys) }
}

/**
 * Zeichnet die Karte unter dem Track. Die Anweisungen kommen **nach Ordnung ueber alle Kacheln einer Stufe** (erst alle Flaechen, dann alle Wege), damit an einer Kachelkante keine Flaeche der
 * einen Kachel einen Weg der anderen abschneidet. Sind nicht alle feinen Kacheln da, kommt die Stufe darunter zuerst (grob, aber ohne Loch).
 */
internal fun DrawScope.drawMapTiles(layer: MapTileLayerState, visible: VisibleTiles, view: TrackView, refLonDeg: Double) {
    layer.revision.value // gelesen, damit eine geladene Kachel das Zeichnen neu anstoesst
    val cache = layer.cache
    val pending = visible.keys.any { !cache.isSettled(it) }
    if (pending) drawLevel(visible.parents, layer, view, refLonDeg)
    drawLevel(visible.keys, layer, view, refLonDeg)
}

private class PlacedTile(val matrix: Matrix, val scale: Float, val scene: ScenePaths)

private fun DrawScope.drawLevel(keys: List<TileKey>, layer: MapTileLayerState, view: TrackView, refLonDeg: Double) {
    val w = size.width.toDouble()
    val h = size.height.toDouble()
    val placed = ArrayList<PlacedTile>()
    for (key in keys) {
        val scene = layer.cache.peek(key) ?: continue
        if (scene.groups.isEmpty()) continue
        val m = tileToScreen(view, w, h, refLonDeg, key, scene.extent)
        val matrix = Matrix().apply {
            values[Matrix.ScaleX] = m.a.toFloat()
            values[Matrix.SkewY] = m.b.toFloat()
            values[Matrix.SkewX] = m.c.toFloat()
            values[Matrix.ScaleY] = m.d.toFloat()
            values[Matrix.TranslateX] = m.e.toFloat()
            values[Matrix.TranslateY] = m.f.toFloat()
        }
        placed.add(PlacedTile(matrix, hypot(m.a, m.b).toFloat(), scene))
    }
    val orders = placed.flatMap { t -> t.scene.groups.map { it.op.order } }.distinct().sorted()
    for (order in orders) for (t in placed) for (g in t.scene.groups) if (g.op.order == order) drawGroup(g, t.matrix, t.scale)
}

private fun DrawScope.drawGroup(g: PathGroup, matrix: Matrix, scale: Float) {
    withTransform({ transform(matrix) }) {
        when (val op = g.op) {
            is DrawOp.Fill -> drawPath(g.path, g.color, style = Fill)
            is DrawOp.Line -> drawPath(
                g.path, g.color,
                style = Stroke(width = (op.widthDp * density / scale).coerceAtLeast(0.01f), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
