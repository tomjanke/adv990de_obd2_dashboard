package de.adv990de.obd2.ui.tracks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
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
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.asLiveValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.vector.ImageVector

internal const val TRACK_CANVAS_TEST_TAG = "track_canvas"
internal const val TRACK_ZOOM_IN_TEST_TAG = "track_zoom_in"
internal const val TRACK_ZOOM_OUT_TEST_TAG = "track_zoom_out"
internal const val TRACK_FIT_TEST_TAG = "track_fit"
internal const val TRACK_PAN_UP_TEST_TAG = "track_pan_up"
internal const val TRACK_PAN_DOWN_TEST_TAG = "track_pan_down"
internal const val TRACK_PAN_LEFT_TEST_TAG = "track_pan_left"
internal const val TRACK_PAN_RIGHT_TEST_TAG = "track_pan_right"
internal const val TRACK_ORIENTATION_TEST_TAG = "track_orientation"

/**
 * Die eigene Position beim Nachfahren: der Punkt [point] und die Richtung des Tracks dort ([bearingDeg], 0 = Nord, im Uhrzeigersinn). Gezeichnet als Pfeil in Fahrtrichtung des Tracks —
 * **nicht** die eigene Fahrtrichtung (ein Fix der Aufzeichnung traegt keinen Kurs). [foot] ist der naechste Punkt des Tracks und [distanceLabel] der Abstand dorthin als Text: zusammen die
 * gestrichelte Abstandslinie in der Karte. Liegt die Position ausserhalb des Bildes, sitzt der Pfeil am Rand. **Positionen sind sensibel:** nichts hiervon geht in ein Log.
 */
internal data class TrackMarker(val point: TrackPoint, val bearingDeg: Double, val foot: TrackPoint? = null, val distanceLabel: String? = null)

/** Rand um den angepassten Track, damit Start- und Zielmarke nicht an der Kante kleben. */
private val CANVAS_PADDING = 32.dp

/** So weit innen sitzt der Pfeil der eigenen Position am Rand, wenn sie ausserhalb des Bildes liegt. */
private val MARKER_EDGE_MARGIN = 22.dp

/** Liegen Pfeil und Fusspunkt naeher als das beieinander, bleibt die Abstandslinie weg (der Pfeil deckt sie ohnehin). */
private val DISTANCE_LINE_MIN = 28.dp

/** Die Knoepfe sind 56 dp gross (Handschuh-Regel); ein Zoomschritt ist ein Faktor 2, ein Verschiebeschritt ein Viertel der kleineren Seite der Flaeche. */
private val CONTROL_SIZE = 56.dp
private const val ZOOM_STEP = 2.0
private const val PAN_STEP_SHARE = 0.25

private val ViewStateSaver = listSaver<TrackViewState, Double>(
    save = { listOf(it.zoom, it.dx, it.dy) },
    restore = { TrackViewState(it[0], it[1], it[2]) },
)

private val HeadingStateSaver = listSaver<HeadingViewState, Double>(
    save = { listOf(it.zoom, it.offsetX, it.offsetY) },
    restore = { HeadingViewState(it[0], it[1], it[2]) },
)

/** So lange dreht sich die Karte zum neuen Kurs. */
private const val ROTATION_MILLIS = 350

/** So lange gleitet die Karte zu einer neuen Spanne (Auto-Zoom). */
private const val SPAN_MILLIS = 600

/**
 * Die Ansicht eines Tracks: der Linienzug an die Flaeche angepasst (Mercator, Seitenverhaeltnis erhalten), Start- und Zielmarke, Massstabsleiste, Nordpfeil — **ohne Kartenhintergrund**
 * (K0). Zoomen und Verschieben mit **Knoepfen** in der Ecke (Handschuh-Regel), zusaetzlich **Ziehen mit einem Finger**, **Zwei-Finger-Zoom** und Doppeltipp = Ausgangsansicht; eine Geste ist
 * nie der einzige Weg. **Norden oben:** der Track verlaesst den Rahmen nie — herausgezoomt wird nur bis „angepasst“, verschoben nur, solange der Ausschnitt im Rechteck des Tracks samt Rand
 * bleibt ([TrackFrame]); ein Track, der das Fenster in einer Richtung nicht fuellt, laesst sich in dieser Richtung nicht verschieben.
 *
 * Mit [orientable] und einer Position ([marker]) gibt es den Knopf „Fahrtrichtung oben“: die Karte folgt dann der Position und dreht sich so, dass die Richtung des Tracks oben steht
 * ([HeadingFrame]); dort verschiebt ein Finger immer (die Position bleibt im Bild), Doppeltipp und der mittlere Knopf holen die Position zurueck in die Mitte. Der Nordpfeil dreht mit.
 *
 * [points] sind die Punkte der Zeichnung (schon ausgeduennt), [bounds] das Rechteck **aller** Punkte. Der Zustand ueberlebt eine Drehung ([TrackViewState] ist relativ zur angepassten Ansicht).
 * **Positionen sind sensibel:** nichts hiervon geht in ein Log.
 */
@Composable
internal fun TrackMapView(
    points: List<TrackPoint>,
    bounds: TrackBounds,
    modifier: Modifier = Modifier,
    marker: TrackMarker? = null,
    orientable: Boolean = false,
    autoSpanM: Double? = null,
) {
    var state by rememberSaveable(stateSaver = ViewStateSaver) { mutableStateOf(TrackViewState()) }
    var headingState by rememberSaveable(stateSaver = HeadingStateSaver) { mutableStateOf(HeadingViewState()) }
    var headingMode by rememberSaveable { mutableStateOf(false) }
    // „Der Position folgen“ in der Ansicht Norden oben (nur beim Nachfahren mit Auto-Zoom): ein Eingriff von Hand beendet es, Doppeltipp und der mittlere Knopf holen es zurueck.
    var follow by rememberSaveable { mutableStateOf(true) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val paddingPx = with(density) { CANVAS_PADDING.toPx() }.toDouble()
    val marginPx = with(density) { MARKER_EDGE_MARGIN.toPx() }.toDouble()
    val frame = remember(bounds, size, paddingPx) { trackFrame(bounds, size.width.toDouble(), size.height.toDouble(), paddingPx) }
    val headingFrame = remember(size, marginPx) { HeadingFrame(size.width.toDouble(), size.height.toDouble(), marginPx) }
    val currentFrame by rememberUpdatedState(frame)
    val currentHeadingFrame by rememberUpdatedState(headingFrame)
    // „Fahrtrichtung oben“ braucht eine Position; ohne sie (Suche, keine Berechtigung) bleibt es bei Norden oben, der Wunsch bleibt gemerkt.
    val headingActive = orientable && headingMode && marker != null && size.width > 0 && size.height > 0
    val headingActiveNow by rememberUpdatedState(headingActive)
    val rotation = rememberMapRotation(headingActive, marker?.bearingDeg ?: 0.0)
    val autoAvailable = orientable && marker != null && autoSpanM != null && size.width > 0 && size.height > 0
    val autoAvailableNow by rememberUpdatedState(autoAvailable)
    // Die Spanne aendert sich im Sekundentakt der Position; die Karte gleitet dorthin, statt zu springen.
    val animatedSpan by animateFloatAsState(
        targetValue = (autoSpanM ?: HEADING_DEFAULT_SPAN_M).toFloat(),
        animationSpec = tween(SPAN_MILLIS, easing = EaseOutCubic),
        label = "autoSpan",
    )
    val spanM = if (autoSpanM != null) animatedSpan.toDouble() else HEADING_DEFAULT_SPAN_M
    val northFollowing = autoAvailable && !headingMode && follow
    val northFollowingNow by rememberUpdatedState(northFollowing)
    val view = when {
        headingActive && marker != null ->
            headingFrame.viewFor(headingState, projectLon(marker.point.lon, bounds.minLon), projectLat(marker.point.lat), rotation, spanM)
        northFollowing && marker != null -> frame.followView(projectLon(marker.point.lon, bounds.minLon), projectLat(marker.point.lat), spanM)
        else -> state.resolved(frame)
    }
    val currentView by rememberUpdatedState(view)
    // Ein Eingriff von Hand (Verschieben, Zoomen) beendet das Folgen und uebernimmt die Ansicht, wie sie gerade ist.
    fun leaveFollow() {
        if (northFollowingNow) {
            state = TrackViewState.of(currentView, currentFrame.fit)
            follow = false
        }
    }

    Box(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .onSizeChanged { size = it }
            .clipToBounds(),
    ) {
        TrackCanvas(
            points = points,
            bounds = bounds,
            view = view,
            size = size,
            marker = marker,
            modifier = Modifier
                .fillMaxSize()
                .testTag(TRACK_CANVAS_TEST_TAG)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (headingActiveNow) {
                                headingState = HeadingViewState()
                            } else {
                                state = TrackViewState()
                                if (autoAvailableNow) follow = true
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    // Ein Finger verschiebt, zwei Finger zoomen um ihren Mittelpunkt (und verschieben); eine Drehung der Finger wird ignoriert: die Ausrichtung der Karte bestimmt der Knopf.
                    // Der Zustand wird hier direkt gelesen, nicht ueber rememberUpdatedState: kommen mehrere Ereignisse vor der naechsten Komposition, rechnet jedes auf dem Ergebnis des vorigen.
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (headingActiveNow) {
                            headingState = currentHeadingFrame.transformedBy(headingState, centroid.x.toDouble(), centroid.y.toDouble(), pan.x.toDouble(), pan.y.toDouble(), zoom.toDouble())
                        } else {
                            leaveFollow()
                            state = state.transformedBy(centroid.x.toDouble(), centroid.y.toDouble(), pan.x.toDouble(), pan.y.toDouble(), zoom.toDouble(), currentFrame)
                        }
                    }
                },
        )
        val panStep = (minOf(size.width, size.height) * PAN_STEP_SHARE).toDouble()
        // Die Knoepfe zoomen und verschieben in beiden Ansichten gleich; in der Ansicht „Fahrtrichtung oben“ zoomen sie um die Position, damit sie stehen bleibt.
        fun zoomButton(factor: Double) {
            if (headingActive) {
                val m = headingFrame.markerOf(headingState)
                headingState = headingFrame.zoomedAround(headingState, factor, m.x, m.y)
            } else {
                leaveFollow()
                state = state.zoomedBy(factor, frame)
            }
        }
        fun panButton(dxPx: Double, dyPx: Double) {
            if (headingActive) {
                headingState = headingFrame.pannedBy(headingState, dxPx, dyPx)
            } else {
                leaveFollow()
                state = state.pannedBy(dxPx, dyPx, frame)
            }
        }
        TrackControls(
            onZoomIn = { zoomButton(ZOOM_STEP) },
            onZoomOut = { zoomButton(1 / ZOOM_STEP) },
            // Die Mitte: in „Fahrtrichtung oben“ die Position zurueck in die Mitte; in Norden oben mit Position wechselt sie zwischen dem ganzen Track und dem Folgen, sonst der ganze Track.
            onFit = {
                when {
                    headingActive -> headingState = HeadingViewState()
                    autoAvailable -> {
                        state = TrackViewState()
                        follow = !follow
                    }
                    else -> state = state.fitted()
                }
            },
            // Eine Taste verschiebt den Ausschnitt in ihre Richtung: der Inhalt wandert dagegen.
            onUp = { panButton(0.0, panStep) },
            onDown = { panButton(0.0, -panStep) },
            onLeft = { panButton(panStep, 0.0) },
            onRight = { panButton(-panStep, 0.0) },
            centred = headingActive || (autoAvailable && !follow),
            orientable = orientable,
            headingUp = headingMode,
            onOrientation = {
                headingMode = !headingMode
                headingState = HeadingViewState()
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )
    }
}

/**
 * Der Winkel, um den die Karte gedreht ist (der Kurs oben): ohne „Fahrtrichtung oben“ 0, sonst der Kurs [targetDeg]. Beim Einschalten steht er sofort richtig (die Karte kreist nicht ein),
 * danach dreht sie sich bei jeder Kursaenderung den kuerzesten Weg in [ROTATION_MILLIS] ms.
 */
@Composable
private fun rememberMapRotation(active: Boolean, targetDeg: Double): Double {
    val angle = remember { Animatable(0f) }
    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(active, targetDeg) {
        if (!active) {
            angle.snapTo(0f)
            primed = false
        } else if (!primed) {
            angle.snapTo(targetDeg.toFloat())
            primed = true
        } else {
            val goal = angle.value + shortestTurnDeg(angle.value.toDouble(), targetDeg).toFloat()
            angle.animateTo(goal, tween(ROTATION_MILLIS, easing = EaseOutCubic))
        }
    }
    return when {
        !active -> 0.0
        !primed -> targetDeg
        else -> angle.value.toDouble()
    }
}

@Composable
private fun TrackCanvas(points: List<TrackPoint>, bounds: TrackBounds, view: TrackView, size: IntSize, marker: TrackMarker?, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.tertiary
    val endColor = MaterialTheme.colorScheme.onSurface
    val halo = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = StatusColors.OwnPosition
    val ringColor = StatusColors.OwnPositionRing
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

        // Die eigene Position: ein Pfeil in Richtung des Tracks, auf einem Ring in der Flaechenfarbe (lesbar auf Linie und Grund). Ausserhalb des Bildes sitzt er am Rand;
        // vom Fusspunkt auf dem Track fuehrt eine gestrichelte Linie zur Position, mit dem Abstand daneben.
        if (marker != null) {
            val m = toScreen(view, w, h, projectLon(marker.point.lon, bounds.minLon), projectLat(marker.point.lat))
            val shown = clampToFrame(m.x, m.y, w, h, MARKER_EDGE_MARGIN.toPx().toDouble())
            val centre = Offset(shown.x.toFloat(), shown.y.toFloat())
            val real = Offset(m.x.toFloat(), m.y.toFloat())
            if (marker.foot != null) {
                val f = toScreen(view, w, h, projectLon(marker.foot.lon, bounds.minLon), projectLat(marker.foot.lat))
                val foot = Offset(f.x.toFloat(), f.y.toFloat())
                if ((real - foot).getDistance() > DISTANCE_LINE_MIN.toPx()) {
                    drawLine(halo, real, foot, strokeWidth = 5.dp.toPx())
                    drawLine(markerColor, real, foot, strokeWidth = 2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())))
                    drawCircle(ringColor, 6.dp.toPx(), foot)
                    drawCircle(markerColor, 4.dp.toPx(), foot)
                    if (marker.distanceLabel != null) {
                        // Der Text steht neben der Linie, nicht auf ihr: auf der Mitte verdeckte er bei kurzem Abstand fast die ganze Linie.
                        val text = measurer.measure(marker.distanceLabel, textStyle)
                        val pad = 4.dp.toPx()
                        val boxW = text.size.width + 2 * pad
                        val boxH = text.size.height + pad
                        val box = distanceLabelTopLeft(
                            foot.x.toDouble(), foot.y.toDouble(), centre.x.toDouble(), centre.y.toDouble(),
                            boxW.toDouble(), boxH.toDouble(), gapPx = 6.dp.toPx().toDouble(), widthPx = w, heightPx = h, padPx = 4.dp.toPx().toDouble(),
                        )
                        drawRect(halo.copy(alpha = 0.9f), Offset(box.x.toFloat(), box.y.toFloat()), androidx.compose.ui.geometry.Size(boxW, boxH))
                        drawText(text, topLeft = Offset(box.x.toFloat() + pad, box.y.toFloat() + pad / 2))
                    }
                }
            }
            // Der Pfeil zeigt in die Richtung des Tracks; steht der Kurs oben (Karte gedreht), zeigt er nach oben.
            val rad = Math.toRadians(marker.bearingDeg - view.rotationDeg)
            val dir = Offset(Math.sin(rad).toFloat(), -Math.cos(rad).toFloat())
            val side = Offset(-dir.y, dir.x)
            fun arrow(scale: Float) = Path().apply {
                val tip = centre + dir * (18.dp.toPx() * scale)
                val left = centre - dir * (10.dp.toPx() * scale) + side * (11.dp.toPx() * scale)
                val right = centre - dir * (10.dp.toPx() * scale) - side * (11.dp.toPx() * scale)
                moveTo(tip.x, tip.y)
                lineTo(left.x, left.y)
                lineTo(centre.x, centre.y)
                lineTo(right.x, right.y)
                close()
            }
            drawPath(arrow(1.35f), ringColor)
            drawPath(arrow(1f), markerColor)
        }

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

        // Nordpfeil oben rechts: er zeigt nach Norden, auch wenn die Karte gedreht ist (Fahrtrichtung oben); ohne Drehung steht er senkrecht.
        val pivot = Offset(this.size.width - 44.dp.toPx(), 50.dp.toPx())
        val turn = Math.toRadians(-view.rotationDeg)
        val cs = Math.cos(turn).toFloat()
        val sn = Math.sin(turn).toFloat()
        fun north(dx: Float, dy: Float) = Offset(pivot.x + dx * cs - dy * sn, pivot.y + dx * sn + dy * cs)
        val arrow = Path().apply {
            val tip = north(0f, -12.dp.toPx())
            val right = north(8.dp.toPx(), 12.dp.toPx())
            val notch = north(0f, 8.dp.toPx())
            val left = north(-8.dp.toPx(), 12.dp.toPx())
            moveTo(tip.x, tip.y)
            lineTo(right.x, right.y)
            lineTo(notch.x, notch.y)
            lineTo(left.x, left.y)
            close()
        }
        drawPath(arrow, ink)
        val n = measurer.measure(northLabel, textStyle)
        val nCentre = north(0f, -12.dp.toPx() - n.size.height / 2f - 2.dp.toPx())
        drawText(n, topLeft = Offset(nCentre.x - n.size.width / 2f, nCentre.y - n.size.height / 2f))
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
    centred: Boolean,
    orientable: Boolean,
    headingUp: Boolean,
    onOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Eine 3 x 3 Kreuzbelegung in der Ecke: Zoom + oben Zoom -, links Anpassen rechts, unten; unten links die Ausrichtung (nur beim Nachfahren). Halbdurchsichtig, damit der Track darunter lesbar bleibt.
    // Alle Knoepfe sind Symbole: ein Wort („Anpassen“) brach in dem 56-dp-Knopf um.
    Surface(modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = androidx.compose.ui.graphics.RectangleShape) {
        Column {
            Row {
                ControlIconButton(onZoomIn, Icons.Default.Add, R.string.track_zoom_in, TRACK_ZOOM_IN_TEST_TAG)
                ControlIconButton(onUp, Icons.Default.KeyboardArrowUp, R.string.track_pan_up, TRACK_PAN_UP_TEST_TAG)
                ZoomOutButton(onZoomOut)
            }
            Row {
                ControlIconButton(onLeft, Icons.Default.KeyboardArrowLeft, R.string.track_pan_left, TRACK_PAN_LEFT_TEST_TAG)
                // In der Ansicht „Fahrtrichtung oben“ holt die Mitte die Position zurueck, sonst den ganzen Track ins Bild.
                ControlIconButton(onFit, if (centred) MapIcons.Recentre else MapIcons.Fit, if (centred) R.string.track_recentre_cd else R.string.track_fit_cd, TRACK_FIT_TEST_TAG)
                ControlIconButton(onRight, Icons.Default.KeyboardArrowRight, R.string.track_pan_right, TRACK_PAN_RIGHT_TEST_TAG)
            }
            Row {
                if (orientable) {
                    ControlIconButton(
                        onOrientation,
                        if (headingUp) MapIcons.HeadingUp else MapIcons.NorthUp,
                        if (headingUp) R.string.track_orientation_to_north else R.string.track_orientation_to_heading,
                        TRACK_ORIENTATION_TEST_TAG,
                    )
                } else {
                    Box(Modifier.size(CONTROL_SIZE))
                }
                ControlIconButton(onDown, Icons.Default.KeyboardArrowDown, R.string.track_pan_down, TRACK_PAN_DOWN_TEST_TAG)
            }
        }
    }
}

@Composable
private fun ControlIconButton(onClick: () -> Unit, icon: ImageVector, descriptionRes: Int, tag: String) {
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
