package de.adv990de.obd2.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.formatSensorNumber
import de.adv990de.obd2.terrain.CompassPoint
import de.adv990de.obd2.terrain.TerrainDisplay
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.components.MIN_VALUE_FONT_SIZE
import de.adv990de.obd2.ui.components.TileFrame
import de.adv990de.obd2.ui.components.TileStatus
import de.adv990de.obd2.ui.components.liveValueTileAccentColor
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.ValueTileStyle
import de.adv990de.obd2.ui.theme.asLiveValue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Kantenlaenge der Windrose. */
private val ROSE_SIZE = 72.dp

/**
 * Hoehe des Bandes mit dem Steigungs-Graphen. 4 dp niedriger als das Sparkline-Band der Wert-Kachel (48 dp): in der 170 dp hohen Kachel (146 dp Innenhoehe) bleiben so 102 dp fuer
 * Windrose (72 dp) und Peilungstext (rund 28 dp Zeilenhoehe) — mit 48 dp schrumpfte der Peilungstext, weil ihm die Hoehe fehlte (am Geraet gesehen).
 */
private val GRADE_BAND_HEIGHT = 44.dp

/** Breite der Zahl neben dem Graphen („+12 %“, „−10 %“: fuenf Zeichen in Plex Mono). */
private val GRADE_TEXT_WIDTH = 68.dp

internal const val TERRAIN_TILE_TEST_TAG = "terrain_tile"
internal const val TERRAIN_ROSE_TEST_TAG = "terrain_rose"
internal const val TERRAIN_GRAPH_TEST_TAG = "terrain_graph"
internal const val TERRAIN_ALTITUDE_TEST_TAG = "terrain_altitude"
internal const val TERRAIN_GRADE_TEST_TAG = "terrain_grade"
internal const val TERRAIN_HEADING_TEST_TAG = "terrain_heading"

/**
 * Ein Slot mit der Gelaende-Kachel: ohne Werte (GPS aus, Berechtigung fehlt, noch kein Fix) der Hinweis wie bei jedem leeren Slot ([slotPlaceholderRes]), sonst die Kachel.
 * **Unabhaengig von der Verbindung zum Fahrzeug:** die Werte kommen vom Telefon, die Kachel zeigt sie auch ohne Adapter (sie ist die einzige, die im Zustand
 * "Vorschau · nicht verbunden" echte Werte hat).
 */
@Composable
internal fun TerrainSlot(
    terrain: TerrainDisplay?,
    useImperial: Boolean,
    @StringRes placeholderRes: Int,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    if (terrain == null) {
        EmptySlot(placeholderRes = placeholderRes, modifier = modifier, onLongPress = onLongPress)
    } else {
        TerrainTile(terrain = terrain, useImperial = useImperial, modifier = modifier.fillMaxSize(), onLongPress = onLongPress)
    }
}

/**
 * Die Gelaende-Kachel: **Hoehe** als grosse Zahl, **Steigung** als Verlauf der letzten 1000 m (Graph mit Nulllinie, daneben der aktuelle Wert) und **Peilung** als Windrose
 * (Nord oben fest, die Nadel zeigt die Fahrtrichtung, darunter Himmelsrichtung und Grad). Ein Rahmen wie jede Kachel ([TileFrame]); die Statusleiste ist immer die
 * Akzentfarbe (die Kachel kennt keinen Warnzustand, und keine Vorschau-Dämpfung: ihre Werte sind immer die des Telefons). Jedes Feld zeigt "--", wenn es keinen Wert hat.
 *
 * ```
 * ┌─┬────────────────────────────┐
 * │ │ GELÄNDE            ┌─N─┐   │
 * │ │                    W ➚ O   │
 * │ │ 1234 m             └─S─┘   │
 * │ │                    NO 45°  │
 * │ │ ╱╲__╱‾╲___            +4 % │
 * └─┴────────────────────────────┘
 * ```
 */
@Composable
internal fun TerrainTile(
    terrain: TerrainDisplay,
    useImperial: Boolean,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val accent = liveValueTileAccentColor(
        status = TileStatus.Ok,
        isPreview = false,
        onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
        primary = MaterialTheme.colorScheme.primary,
        surface = MaterialTheme.colorScheme.surface,
    )
    val (altitudeText, altitudeUnit) = formatAltitude(terrain.altitudeM, useImperial)
    val gradeText = formatGrade(terrain.gradePercent)
    val labels = CompassPoint.entries.associateWith { stringResource(compassLabelRes(it)) }
    val headingText = headingText(terrain.headingDeg) { labels.getValue(it) }
    val title = stringResource(R.string.pid_terrain)
    val description = terrainDescription(title, terrain, useImperial)

    TileFrame(
        label = title,
        accentColor = accent,
        modifier = modifier.testTag(TERRAIN_TILE_TEST_TAG).semantics { contentDescription = description },
        onLongPress = onLongPress,
        // Das Neubelegen per Long-Press ist die einzige Geste dafuer — TalkBack soll sie beim Namen nennen (UID-13).
        onLongPressLabel = stringResource(R.string.dashboard_slot_change_action),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                // Links unten die Hoehe; das Label liegt oben links im Rahmen darueber.
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        // weight(fill = false): die Einheit behaelt ihren Platz, der Wert schrumpft statt abgeschnitten zu werden (B27).
                        LiveValueText(
                            value = altitudeText,
                            modifier = Modifier.weight(1f, fill = false).testTag(TERRAIN_ALTITUDE_TEST_TAG),
                            style = ValueTileStyle,
                            color = accent,
                            minFontSize = MIN_VALUE_FONT_SIZE,
                        )
                        Text(
                            text = " $altitudeUnit",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                // Rechts die Windrose mit der Peilung als Text darunter.
                Column(Modifier.padding(start = 8.dp).width(ROSE_SIZE), horizontalAlignment = Alignment.CenterHorizontally) {
                    CompassRose(
                        headingDeg = terrain.headingDeg,
                        modifier = Modifier.size(ROSE_SIZE).testTag(TERRAIN_ROSE_TEST_TAG),
                    )
                    LiveValueText(
                        value = headingText,
                        modifier = Modifier.fillMaxWidth().testTag(TERRAIN_HEADING_TEST_TAG),
                        style = MaterialTheme.typography.titleMedium.asLiveValue(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        minFontSize = 9.sp,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().height(GRADE_BAND_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                GradeGraph(
                    values = terrain.gradeHistory,
                    modifier = Modifier.weight(1f).fillMaxHeight().testTag(TERRAIN_GRAPH_TEST_TAG),
                )
                LiveValueText(
                    value = gradeText,
                    modifier = Modifier.width(GRADE_TEXT_WIDTH).testTag(TERRAIN_GRADE_TEST_TAG),
                    style = MaterialTheme.typography.titleMedium.asLiveValue(),
                    color = accent,
                    textAlign = TextAlign.End,
                    minFontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * Der Steigungs-Verlauf der letzten 1000 m: Linie in `primary` (wie die Sparkline), die Nulllinie als Haarlinie in `outline`, der juengste Wert am rechten Rand mit einem Punkt.
 * Die Skala ist **symmetrisch um null** ([gradeGraphScale]) und wird nicht auf den Verlauf gespreizt wie bei der Sparkline: Rauschen auf der Ebene bleibt eine flache Linie.
 * Ohne Punkte zeigt er nur die Nulllinie.
 */
@Composable
internal fun GradeGraph(values: List<Float>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val zero = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val inset = 4.dp.toPx()
        val middle = size.height / 2f
        drawLine(zero, Offset(0f, middle), Offset(size.width, middle), strokeWidth = 1.dp.toPx())
        if (values.isEmpty()) return@Canvas
        val points = gradeGraphPoints(values, gradeGraphScale(values), size.width - inset, size.height - 2 * inset)
            .map { Offset(it.x, it.y + inset) }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(line, radius = 3.5.dp.toPx(), center = points.last())
    }
}

/**
 * Die Windrose: Ring in `outline`, vier Teilstriche an den Zwischenrichtungen, die Buchstaben der vier Himmelsrichtungen (Nord in `primary`, die uebrigen
 * `onSurfaceVariant`) und die Nadel, die die Fahrtrichtung zeigt. **Nord bleibt oben, die Nadel dreht sich** — die Rose steht wie eine Karte. Ohne [headingDeg] keine Nadel.
 * Die Buchstaben haben eine feste Groesse in dp (nicht in sp): eine grosse Systemschrift soll sie nicht in die Nadel schieben.
 */
@Composable
internal fun CompassRose(headingDeg: Float?, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.outline
    val north = MaterialTheme.colorScheme.primary
    val others = MaterialTheme.colorScheme.onSurfaceVariant
    val needle = MaterialTheme.colorScheme.primary
    val letters = listOf(
        stringResource(R.string.terrain_compass_n),
        stringResource(R.string.terrain_compass_e),
        stringResource(R.string.terrain_compass_s),
        stringResource(R.string.terrain_compass_w),
    )
    val measurer = rememberTextMeasurer()
    val letterStyle = MaterialTheme.typography.labelSmall.copy(fontSize = with(LocalDensity.current) { 11.dp.toSp() })
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 1.dp.toPx()
        drawCircle(ring, r, c, style = Stroke(width = 1.5.dp.toPx()))
        // Zwischenrichtungen: ein kurzer Strich nach innen (die Hauptrichtungen tragen ihren Buchstaben).
        for (i in 1..7 step 2) {
            val a = Math.toRadians(i * 45.0)
            val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
            drawLine(ring, c + dir * r, c + dir * (r - 4.dp.toPx()), strokeWidth = 1.5.dp.toPx())
        }
        letters.forEachIndexed { i, letter ->
            val a = Math.toRadians(i * 90.0)
            val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
            drawLetter(measurer, letter, letterStyle, if (i == 0) north else others, c + dir * (r - 9.dp.toPx()))
        }
        if (headingDeg != null && headingDeg.isFinite()) {
            val points = roseNeedle(headingDeg, r * 0.55f, c)
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (k in 1 until points.size) lineTo(points[k].x, points[k].y)
                close()
            }
            drawPath(path, needle, style = Fill)
        }
    }
}

private fun DrawScope.drawLetter(measurer: TextMeasurer, text: String, style: androidx.compose.ui.text.TextStyle, color: Color, center: Offset) {
    val layout = measurer.measure(text, style)
    drawText(layout, color = color, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

@StringRes
internal fun compassLabelRes(point: CompassPoint): Int = when (point) {
    CompassPoint.N -> R.string.terrain_compass_n
    CompassPoint.NE -> R.string.terrain_compass_ne
    CompassPoint.E -> R.string.terrain_compass_e
    CompassPoint.SE -> R.string.terrain_compass_se
    CompassPoint.S -> R.string.terrain_compass_s
    CompassPoint.SW -> R.string.terrain_compass_sw
    CompassPoint.W -> R.string.terrain_compass_w
    CompassPoint.NW -> R.string.terrain_compass_nw
}

@StringRes
internal fun compassNameRes(point: CompassPoint): Int = when (point) {
    CompassPoint.N -> R.string.terrain_compass_name_n
    CompassPoint.NE -> R.string.terrain_compass_name_ne
    CompassPoint.E -> R.string.terrain_compass_name_e
    CompassPoint.SE -> R.string.terrain_compass_name_se
    CompassPoint.S -> R.string.terrain_compass_name_s
    CompassPoint.SW -> R.string.terrain_compass_name_sw
    CompassPoint.W -> R.string.terrain_compass_name_w
    CompassPoint.NW -> R.string.terrain_compass_name_nw
}

/** Die Beschreibung der ganzen Kachel fuer Screenreader; ein Feld ohne Wert entfaellt. */
@Composable
private fun terrainDescription(title: String, terrain: TerrainDisplay, useImperial: Boolean): String {
    val parts = buildList {
        terrain.altitudeM?.takeIf { it.isFinite() }?.let { meters ->
            val (number, _) = formatAltitude(meters, useImperial)
            add(stringResource(if (useImperial) R.string.terrain_desc_altitude_ft else R.string.terrain_desc_altitude_m, number))
        }
        gradeTrend(terrain.gradePercent)?.let { trend ->
            val magnitude = formatSensorNumber(abs((terrain.gradePercent ?: 0f).roundToInt()).toFloat(), "%")
            add(
                when (trend) {
                    GradeTrend.Up -> stringResource(R.string.terrain_desc_grade_up, magnitude)
                    GradeTrend.Down -> stringResource(R.string.terrain_desc_grade_down, magnitude)
                    GradeTrend.Flat -> stringResource(R.string.terrain_desc_grade_flat)
                },
            )
        }
        terrain.headingDeg?.takeIf { it.isFinite() }?.let { degrees ->
            val point = de.adv990de.obd2.terrain.compassPoint(degrees)
            if (point != null) {
                val whole = (((degrees.toDouble() % 360.0) + 360.0) % 360.0).roundToInt() % 360
                add(stringResource(R.string.terrain_desc_heading, stringResource(compassNameRes(point)), whole.toString()))
            }
        }
    }
    return if (parts.isEmpty()) title else "$title: ${parts.joinToString(", ")}"
}

// region — Previews
//
// Die Kachel braucht eine Hoehe (im Dashboard gibt die Reihe sie vor): hier 170 dp wie im Hochformat.

@Composable
private fun TerrainTilePreviewContent() {
    val tile = Modifier.width(224.dp).height(170.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TerrainTile(
            terrain = TerrainDisplay(
                altitudeM = 1234f, gradePercent = 4f, headingDeg = 45f,
                gradeHistory = List(40) { i -> 4f * kotlin.math.sin(i / 6f) + 2f },
            ),
            useImperial = false, modifier = tile,
        )
        // Noch ohne Werte (z. B. Hoehe vor der ersten GPS-Probe): "--" in jedem Feld, Rose ohne Nadel (drei Kacheln passen nicht ins Vorschaufenster).
        TerrainTile(terrain = TerrainDisplay(null, null, null, emptyList()), useImperial = false, modifier = tile)
    }
}

@Preview(name = "TerrainTile (light)", showBackground = true)
@Composable
private fun PreviewTerrainTileLight() {
    PreviewTheme(dark = false) { TerrainTilePreviewContent() }
}

@Preview(name = "TerrainTile (dark)", showBackground = true)
@Composable
private fun PreviewTerrainTileDark() {
    PreviewTheme(dark = true) { TerrainTilePreviewContent() }
}

// endregion
