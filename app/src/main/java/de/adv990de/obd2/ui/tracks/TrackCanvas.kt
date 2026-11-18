package de.adv990de.obd2.ui.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.ui.components.AppIcons
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.asLiveValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp

internal const val TRACK_CANVAS_TEST_TAG = "track_canvas"
internal const val TRACK_ZOOM_IN_TEST_TAG = "track_zoom_in"
internal const val TRACK_ZOOM_OUT_TEST_TAG = "track_zoom_out"
internal const val TRACK_FIT_TEST_TAG = "track_fit"
internal const val TRACK_PAN_UP_TEST_TAG = "track_pan_up"
internal const val TRACK_PAN_DOWN_TEST_TAG = "track_pan_down"
internal const val TRACK_PAN_LEFT_TEST_TAG = "track_pan_left"
internal const val TRACK_PAN_RIGHT_TEST_TAG = "track_pan_right"

/** Rand um den angepassten Track, damit Start- und Zielmarke nicht an der Kante kleben. */
private val CANVAS_PADDING = 32.dp

/** Die Knoepfe sind 56 dp gross (Handschuh-Regel); ein Zoomschritt ist ein Faktor 2, ein Verschiebeschritt ein Viertel der kleineren Seite der Flaeche. */
private val CONTROL_SIZE = 56.dp
private const val ZOOM_STEP = 2.0
private const val PAN_STEP_SHARE = 0.25

private val ViewStateSaver = listSaver<TrackViewState, Double>(
    save = { listOf(it.zoom, it.dx, it.dy) },
    restore = { TrackViewState(it[0], it[1], it[2]) },
)

/**
 * Die Ansicht eines Tracks: der Linienzug an die Flaeche angepasst (Mercator, Seitenverhaeltnis erhalten), Start- und Zielmarke, Massstabsleiste, Nordpfeil — **ohne Kartenhintergrund**
 * (K0). Zoomen und Verschieben mit **Knoepfen** in der Ecke (Handschuh-Regel), zusaetzlich Ziehen und Doppeltipp = anpassen; ein Wischen ist nie der einzige Weg.
 *
 * [points] sind die Punkte der Zeichnung (schon ausgeduennt), [bounds] das Rechteck **aller** Punkte. Der Zustand ueberlebt eine Drehung ([TrackViewState] ist relativ zur angepassten Ansicht).
 * **Positionen sind sensibel:** nichts hiervon geht in ein Log.
 */
@Composable
internal fun TrackMapView(points: List<TrackPoint>, bounds: TrackBounds, modifier: Modifier = Modifier) {
    var state by rememberSaveable(stateSaver = ViewStateSaver) { mutableStateOf(TrackViewState()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val paddingPx = with(density) { CANVAS_PADDING.toPx() }.toDouble()
    val fit = remember(bounds, size) { fitView(bounds, size.width.toDouble(), size.height.toDouble(), paddingPx) }
    val currentState by rememberUpdatedState(state)
    val currentFit by rememberUpdatedState(fit)

    Box(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .onSizeChanged { size = it },
    ) {
        TrackCanvas(
            points = points,
            bounds = bounds,
            view = state.resolve(fit),
            size = size,
            modifier = Modifier
                .fillMaxSize()
                .testTag(TRACK_CANVAS_TEST_TAG)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { state = TrackViewState() }) }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        state = currentState.pannedBy(drag.x.toDouble(), drag.y.toDouble(), currentFit)
                    }
                },
        )
        val panStep = minOf(size.width, size.height) * PAN_STEP_SHARE
        TrackControls(
            onZoomIn = { state = state.zoomedBy(ZOOM_STEP, fit) },
            onZoomOut = { state = state.zoomedBy(1 / ZOOM_STEP, fit) },
            onFit = { state = state.fitted() },
            // Eine Taste verschiebt den Ausschnitt in ihre Richtung: der Inhalt wandert dagegen.
            onUp = { state = state.pannedBy(0.0, panStep.toDouble(), fit) },
            onDown = { state = state.pannedBy(0.0, -panStep.toDouble(), fit) },
            onLeft = { state = state.pannedBy(panStep.toDouble(), 0.0, fit) },
            onRight = { state = state.pannedBy(-panStep.toDouble(), 0.0, fit) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )
    }
}

@Composable
private fun TrackCanvas(points: List<TrackPoint>, bounds: TrackBounds, view: TrackView, size: IntSize, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.tertiary
    val endColor = MaterialTheme.colorScheme.onSurface
    val halo = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val textStyle = MaterialTheme.typography.labelMedium.asLiveValue().copy(color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    val northLabel = stringResource(R.string.track_north).take(1)
    // Die Punkte werden einmal in die Kartenebene gerechnet, nicht bei jedem Zeichnen.
    val projected = remember(points, bounds) {
        DoubleArray(points.size * 2).also { out ->
            points.forEachIndexed { i, p ->
                out[2 * i] = projectLon(p.lon, bounds.minLon)
                out[2 * i + 1] = projectLat(p.lat)
            }
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val barMax = with(density) { 120.dp.toPx() }.toDouble()
    val bar = scaleBar(view, unprojectLat(view.cy), barMax)
    val (barNumber, isKm) = scaleBarUnit(bar.metres)
    val barLabel = stringResource(if (isKm) R.string.track_scale_km else R.string.track_scale_m, barNumber)
    val description = stringResource(R.string.track_canvas_desc)

    Canvas(modifier.semantics { contentDescription = description }) {
        if (size.width == 0 || size.height == 0 || points.isEmpty()) return@Canvas
        val w = size.width.toDouble()
        val h = size.height.toDouble()
        fun at(i: Int): Offset {
            val p = toScreen(view, w, h, projected[2 * i], projected[2 * i + 1])
            return Offset(p.x.toFloat(), p.y.toFloat())
        }
        if (points.size > 1) {
            val path = Path()
            path.moveTo(at(0).x, at(0).y)
            for (i in 1 until points.size) at(i).let { path.lineTo(it.x, it.y) }
            drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val r = 7.dp.toPx()
        // Ziel zuerst, damit der Start bei einer Runde (Start = Ziel) oben liegt.
        if (points.size > 1) {
            drawCircle(halo, r + 2.dp.toPx(), at(points.lastIndex))
            drawCircle(endColor, r, at(points.lastIndex))
        }
        drawCircle(halo, r + 2.dp.toPx(), at(0))
        drawCircle(startColor, r, at(0))

        // Massstabsleiste unten links
        val margin = 12.dp.toPx()
        val y = this.size.height - margin - 28.dp.toPx() - 0f
        val x0 = margin
        val x1 = margin + bar.px.toFloat()
        val tick = 6.dp.toPx()
        val stroke = 2.dp.toPx()
        drawLine(ink, Offset(x0, y), Offset(x1, y), stroke)
        drawLine(ink, Offset(x0, y - tick), Offset(x0, y + tick), stroke)
        drawLine(ink, Offset(x1, y - tick), Offset(x1, y + tick), stroke)
        val label = measurer.measure(barLabel, textStyle)
        drawText(label, topLeft = Offset(x0, y - tick - label.size.height - 2.dp.toPx()))

        // Nordpfeil oben rechts: Norden ist immer oben
        val ax = this.size.width - margin - 12.dp.toPx()
        val ay = margin + 28.dp.toPx()
        val arrow = Path().apply {
            moveTo(ax, ay - 20.dp.toPx())
            lineTo(ax + 8.dp.toPx(), ay + 4.dp.toPx())
            lineTo(ax, ay)
            lineTo(ax - 8.dp.toPx(), ay + 4.dp.toPx())
            close()
        }
        drawPath(arrow, ink)
        val n = measurer.measure(northLabel, textStyle)
        drawText(n, topLeft = Offset(ax - n.size.width / 2f, ay - 20.dp.toPx() - n.size.height - 2.dp.toPx()))
    }
}

@Composable
private fun TrackControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Eine 3 x 3 Kreuzbelegung in der Ecke: Zoom + oben Zoom -, links Anpassen rechts, unten. Halbdurchsichtig, damit der Track darunter lesbar bleibt.
    Surface(modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = androidx.compose.ui.graphics.RectangleShape) {
        Column {
            Row {
                ControlIconButton(onZoomIn, Icons.Default.Add, R.string.track_zoom_in, TRACK_ZOOM_IN_TEST_TAG)
                ControlIconButton(onUp, Icons.Default.KeyboardArrowUp, R.string.track_pan_up, TRACK_PAN_UP_TEST_TAG)
                ZoomOutButton(onZoomOut)
            }
            Row {
                ControlIconButton(onLeft, Icons.Default.KeyboardArrowLeft, R.string.track_pan_left, TRACK_PAN_LEFT_TEST_TAG)
                FitButton(onFit)
                ControlIconButton(onRight, Icons.Default.KeyboardArrowRight, R.string.track_pan_right, TRACK_PAN_RIGHT_TEST_TAG)
            }
            Row {
                Box(Modifier.size(CONTROL_SIZE))
                ControlIconButton(onDown, Icons.Default.KeyboardArrowDown, R.string.track_pan_down, TRACK_PAN_DOWN_TEST_TAG)
            }
        }
    }
}

@Composable
private fun ControlIconButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, descriptionRes: Int, tag: String) {
    IconButton(onClick = onClick, modifier = Modifier.size(CONTROL_SIZE).testTag(tag)) {
        Icon(icon, contentDescription = stringResource(descriptionRes))
    }
}

/** Herauszoomen hat kein Minus-Symbol im Core-Paket der Icons: ein Text-Knopf mit dem Zeichen, mit derselben Beschreibung fuer TalkBack. */
@Composable
private fun ZoomOutButton(onClick: () -> Unit) {
    val description = stringResource(R.string.track_zoom_out)
    TextButton(onClick = onClick, modifier = Modifier.size(CONTROL_SIZE).testTag(TRACK_ZOOM_OUT_TEST_TAG).semantics { contentDescription = description }) {
        Text("−", style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun FitButton(onClick: () -> Unit) {
    val description = stringResource(R.string.track_fit_cd)
    TextButton(onClick = onClick, modifier = Modifier.size(CONTROL_SIZE).testTag(TRACK_FIT_TEST_TAG).semantics { contentDescription = description }) {
        Text(stringResource(R.string.track_fit), style = MaterialTheme.typography.labelMedium)
    }
}

// region — Previews

/** Eine erfundene Runde im Hochformat-Rechteck bei 50 N, 10 E — nie ein echter Weg. */
private fun previewPoints(): List<TrackPoint> = List(120) { i ->
    val a = i / 120.0 * 2 * Math.PI
    TrackPoint(null, 50.0 + 0.004 * Math.sin(a) + 0.0006 * i / 120, 10.0 + 0.007 * Math.cos(a), null, null, null)
}

@Composable
private fun TrackMapPreviewContent() {
    val points = previewPoints()
    val bounds = TrackBounds(points.minOf { it.lat }, points.maxOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lon })
    TrackMapView(points, bounds, Modifier.size(width = 420.dp, height = 300.dp))
}

@Preview(name = "TrackMapView · light", showBackground = true)
@Composable
private fun PreviewTrackMapViewLight() = PreviewTheme(dark = false) { TrackMapPreviewContent() }

@Preview(name = "TrackMapView · dark", showBackground = true)
@Composable
private fun PreviewTrackMapViewDark() = PreviewTheme(dark = true) { TrackMapPreviewContent() }

// endregion
