package de.adv990de.obd2.ui.tracks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.track.Maneuver
import de.adv990de.obd2.track.ManeuverKind
import de.adv990de.obd2.track.RoadbookHud
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.asLiveValue

internal const val ROADBOOK_HUD_TEST_TAG = "roadbook_hud"
internal const val ROADBOOK_DISTANCE_TEST_TAG = "roadbook_distance"
internal const val ROADBOOK_BAR_TEST_TAG = "roadbook_bar"
internal const val ROADBOOK_BAR_FILL_TEST_TAG = "roadbook_bar_fill"
internal const val ROADBOOK_ARROW_TEST_TAG = "roadbook_arrow"
internal const val ROADBOOK_MAP_TEST_TAG = "roadbook_map"
internal const val ROADBOOK_NEXT_TEST_TAG = "roadbook_next"

/**
 * Wie gross die Teile des HUD sind (dp): voll im Dashboard, kompakt auf der Kartenseite (dort zeigt die Karte den Track schon). Die volle Fassung ist **88 dp hoch** (72 dp Inhalt, 8 dp Rand):
 * im Hochformat sind die Kacheln 170 dp hoch und ihr Wert beginnt rund 97 dp unter der Oberkante — das HUD darf ihn nicht decken.
 */
private val FULL_ARROW = 56.dp
private val COMPACT_ARROW = 40.dp
private val MAP_WIDTH = 112.dp
private val MAP_HEIGHT = 72.dp

/** Der Text zu Art und Richtung einer Richtungsaenderung. */
@Composable
internal fun maneuverKindText(maneuver: Maneuver): String = stringResource(
    when (maneuver.kind) {
        ManeuverKind.SLIGHT -> if (maneuver.isRight) R.string.roadbook_kind_slight_right else R.string.roadbook_kind_slight_left
        ManeuverKind.NORMAL -> if (maneuver.isRight) R.string.roadbook_kind_normal_right else R.string.roadbook_kind_normal_left
        ManeuverKind.SHARP -> if (maneuver.isRight) R.string.roadbook_kind_sharp_right else R.string.roadbook_kind_sharp_left
        ManeuverKind.HAIRPIN -> if (maneuver.isRight) R.string.roadbook_kind_hairpin_right else R.string.roadbook_kind_hairpin_left
        ManeuverKind.DESTINATION -> R.string.roadbook_kind_destination
    },
)

/**
 * Das Roadbook-HUD blendet ein, wenn [hud] da ist, und aus, wenn nicht (200 ms ein, 500 ms aus, ohne Federn): beim Ausblenden bleibt der letzte Stand stehen, bis er weg ist.
 * Es ist kein Knopf und nimmt keine Beruehrung an. [compact] ist die kleine Fassung der Kartenseite.
 */
@Composable
internal fun FadingRoadbookHud(hud: RoadbookHud?, compact: Boolean, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf<RoadbookHud?>(null) }
    if (hud != null && last !== hud) last = hud
    AnimatedVisibility(visible = hud != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(500)), modifier = modifier) {
        last?.let { RoadbookHudView(it, compact) }
    }
}

/**
 * Das HUD selbst: links der Pfeil, daneben die Entfernung in Metern (Plex Mono) mit dem Balken (er fuellt sich beim Naehern) und, wenn gleich danach eine weitere Richtungsaenderung kommt,
 * „danach …“; in der vollen Fassung rechts der Streckenausschnitt. Flaeche in `surface` mit 92 Prozent Deckkraft, 1 dp Rahmen in `primary`, scharfe Kanten; die ganze Karte wird dezent,
 * solange die Richtungsaenderung weit weg ist ([RoadbookHud.alpha]).
 */
@Composable
internal fun RoadbookHudView(hud: RoadbookHud, compact: Boolean, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val distance = formatRoadbookDistance(hud.distanceM, locale)
    val kind = maneuverKindText(hud.maneuver)
    val description = stringResource(R.string.roadbook_desc, distance, kind)
    Surface(
        modifier = modifier
            .graphicsLayer { alpha = hud.alpha }
            .testTag(ROADBOOK_HUD_TEST_TAG)
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = RectangleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ManeuverArrow(hud.maneuver, Modifier.size(if (compact) COMPACT_ARROW else FULL_ARROW).testTag(ROADBOOK_ARROW_TEST_TAG))
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LiveValueText(
                    distance,
                    style = (if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall).asLiveValue(),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(ROADBOOK_DISTANCE_TEST_TAG),
                )
                ProgressBar(hud.progress, Modifier.fillMaxWidth().height(if (compact) 4.dp else 6.dp).testTag(ROADBOOK_BAR_TEST_TAG))
                val next = hud.next
                if (!compact && next != null && hud.nextDistanceM != null) {
                    Text(
                        stringResource(R.string.roadbook_then, maneuverKindText(next), formatRoadbookDistance(hud.nextDistanceM!!, locale)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(ROADBOOK_NEXT_TEST_TAG),
                    )
                }
            }
            if (!compact) RoadbookMiniMap(hud, Modifier.size(width = MAP_WIDTH, height = MAP_HEIGHT).testTag(ROADBOOK_MAP_TEST_TAG))
        }
    }
}

/** Der Balken der Entfernung: leer bei der Einblendschwelle, voll an der Richtungsaenderung; keine Animation (er folgt dem Sekundentakt der Position). */
@Composable
private fun ProgressBar(progress: Float, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary).testTag(ROADBOOK_BAR_FILL_TEST_TAG))
    }
}

/**
 * Der Pfeil der Richtungsaenderung, gezeichnet nach ihrem **Ist-Winkel** ([arrowShape]): von unten nach oben bis zur Ecke, dort um den Winkel abknickend; eine Kehre ein U, das Ziel eine
 * Fahne. Farbe `onSurface`, Strich ein Neuntel der Flaeche.
 */
@Composable
internal fun ManeuverArrow(maneuver: Maneuver, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.11f, cap = StrokeCap.Square, join = StrokeJoin.Round)
        if (maneuver.isDestination) {
            drawFlag(ink, s, stroke)
            return@Canvas
        }
        val shape = arrowShape(maneuver.turnDeg)
        if (shape.uTurn) {
            val left = if (maneuver.isRight) 0.34f else 0.66f
            val right = 1f - left
            val path = Path().apply {
                moveTo(left * s, 0.92f * s)
                lineTo(left * s, 0.42f * s)
                cubicTo(left * s, 0.14f * s, right * s, 0.14f * s, right * s, 0.42f * s)
                lineTo(right * s, 0.7f * s)
            }
            drawPath(path, ink, style = stroke)
            drawHead(ink, Offset(right * s, 0.72f * s), Offset(0f, 1f), s)
        } else {
            val corner = Offset(0.5f * s, 0.52f * s)
            val leg = 0.36f * s
            val end = Offset(corner.x + shape.legDx * leg, corner.y + shape.legDy * leg)
            val path = Path().apply {
                moveTo(0.5f * s, 0.94f * s)
                lineTo(corner.x, corner.y)
                lineTo(end.x, end.y)
            }
            drawPath(path, ink, style = stroke)
            drawHead(ink, end, Offset(shape.legDx, shape.legDy), s)
        }
    }
}

/** Die Pfeilspitze: ein Dreieck mit der Spitze bei [tip] + [dir] mal 0,14 der Flaeche. */
private fun DrawScope.drawHead(color: androidx.compose.ui.graphics.Color, tip: Offset, dir: Offset, s: Float) {
    val apex = tip + dir * (0.14f * s)
    val side = Offset(-dir.y, dir.x)
    val a = tip + side * (0.17f * s)
    val b = tip - side * (0.17f * s)
    drawPath(Path().apply { moveTo(apex.x, apex.y); lineTo(a.x, a.y); lineTo(b.x, b.y); close() }, color)
}

/** Das Ziel: eine Fahne an einer Stange, mit zwei gefuellten Feldern wie ein Zielflaggenmuster. */
private fun DrawScope.drawFlag(color: androidx.compose.ui.graphics.Color, s: Float, stroke: Stroke) {
    drawLine(color, Offset(0.3f * s, 0.94f * s), Offset(0.3f * s, 0.1f * s), strokeWidth = stroke.width, cap = StrokeCap.Square)
    val x0 = 0.3f * s
    val y0 = 0.1f * s
    val cell = 0.18f * s
    for (row in 0..1) for (col in 0..3) {
        if ((row + col) % 2 == 0) drawRect(color, Offset(x0 + col * cell, y0 + row * cell), androidx.compose.ui.geometry.Size(cell, cell))
    }
    drawRect(color, Offset(x0, y0), androidx.compose.ui.geometry.Size(cell * 4, cell * 2), style = Stroke(width = s * 0.03f))
}

/** Der Streckenausschnitt: der Linienzug in Fahrtrichtung oben, das Passierte gedaempft, der Scheitelpunkt als Punkt und die eigene Position als Pfeil unten (wie in der Karte). */
@Composable
private fun RoadbookMiniMap(hud: RoadbookHud, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val behind = MaterialTheme.colorScheme.outline
    val halo = MaterialTheme.colorScheme.surface
    val apexColor = MaterialTheme.colorScheme.onSurface
    val description = stringResource(R.string.roadbook_map_cd)
    Canvas(
        modifier
            .background(MaterialTheme.colorScheme.background)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .clipToBounds()
            .semantics { contentDescription = description },
    ) {
        val map = roadbookMap(hud.path, hud.position, hud.bearingDeg, hud.apex, size.width, size.height)
        val pts = map.line
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            // Was hinter der Position liegt (weiter unten als sie), ist passiert.
            val passed = (a.y + b.y) / 2f > map.position.y
            drawLine(if (passed) behind else line, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        map.apex?.let {
            drawCircle(halo, 6.dp.toPx(), Offset(it.x, it.y))
            drawCircle(apexColor, 4.dp.toPx(), Offset(it.x, it.y))
        }
        val c = Offset(map.position.x, map.position.y)
        fun arrow(scale: Float) = Path().apply {
            moveTo(c.x, c.y - 11.dp.toPx() * scale)
            lineTo(c.x - 7.dp.toPx() * scale, c.y + 6.dp.toPx() * scale)
            lineTo(c.x, c.y + 2.dp.toPx() * scale)
            lineTo(c.x + 7.dp.toPx() * scale, c.y + 6.dp.toPx() * scale)
            close()
        }
        drawPath(arrow(1.3f), StatusColors.OwnPositionRing)
        drawPath(arrow(1f), StatusColors.OwnPosition)
    }
}

// region — Previews

/** Ein erfundener Ausschnitt bei 50 N, 10 E: erst nach Norden, dann nach Osten — nie ein echter Weg. */
private fun previewHud(maneuver: Maneuver, next: Maneuver? = null): RoadbookHud {
    val path = listOf(
        TrackPoint(null, 50.0000, 10.0000, null, null, null),
        TrackPoint(null, 50.0010, 10.0000, null, null, null),
        TrackPoint(null, 50.0020, 10.0000, null, null, null),
        TrackPoint(null, 50.0023, 10.0005, null, null, null),
        TrackPoint(null, 50.0023, 10.0020, null, null, null),
    )
    return RoadbookHud(
        maneuver = maneuver, distanceM = 240.0, triggerM = 400.0, next = next, nextDistanceM = next?.let { 90.0 },
        path = path, apex = path[3], position = path[0], bearingDeg = 0.0,
    )
}

private val PREVIEW_RIGHT = Maneuver(220.0, 240.0, 260.0, 95.0, ManeuverKind.NORMAL)
private val PREVIEW_LEFT = Maneuver(330.0, 340.0, 350.0, -70.0, ManeuverKind.NORMAL)

@Preview(name = "RoadbookHud · full · dark", showBackground = true)
@Composable
private fun PreviewRoadbookHudFullDark() = PreviewTheme(dark = true) { RoadbookHudView(previewHud(PREVIEW_RIGHT, PREVIEW_LEFT), compact = false) }

@Preview(name = "RoadbookHud · full · light", showBackground = true)
@Composable
private fun PreviewRoadbookHudFullLight() = PreviewTheme(dark = false) { RoadbookHudView(previewHud(PREVIEW_RIGHT), compact = false) }

@Preview(name = "RoadbookHud · compact · dark", showBackground = true)
@Composable
private fun PreviewRoadbookHudCompactDark() = PreviewTheme(dark = true) { RoadbookHudView(previewHud(Maneuver(220.0, 240.0, 260.0, -170.0, ManeuverKind.HAIRPIN)), compact = true) }

@Preview(name = "RoadbookHud · destination · light", showBackground = true)
@Composable
private fun PreviewRoadbookHudDestinationLight() = PreviewTheme(dark = false) { RoadbookHudView(previewHud(Maneuver(240.0, 240.0, 240.0, 0.0, ManeuverKind.DESTINATION)), compact = true) }

// endregion
