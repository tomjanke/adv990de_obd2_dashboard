package de.adv990de.obd2.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import de.adv990de.obd2.ui.theme.PreviewTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

/**
 * Strichstaerke und Punktradius der Linien in den Kacheln: **eine** Stelle fuer die Sparkline und den Steigungs-Verlauf der Gelaende-Kachel (`GradeGraph`), damit beide gleich dick sind
 * (Nutzerwunsch 2026-10-18; die Steigung zeichnete vorher 3 dp / 3,5 dp). `SparklineStrokeSourceTest` haelt fest, dass `GradeGraph` diese Konstanten nutzt.
 */
internal val SPARKLINE_STROKE_WIDTH: Dp = 4.dp
internal val SPARKLINE_DOT_RADIUS: Dp = 4.dp

/**
 * Leichte Glaettung fuer die Sparkline-Trendlinie: zentrierter gleitender Mittelwert.
 * Randpunkte nutzen weniger Nachbarn statt die Kurve dort abzuschneiden oder zu verzerren.
 * Der Rohwert im ViewModel-Puffer bleibt unveraendert — nur die gezeichnete Linie (und ihre
 * Min/Max-Skalierung) sieht diese geglaetteten Werte; der numerische Live-Wert der Kachel
 * kommt nie aus diesem Pfad.
 */
internal fun smoothSparklinePoints(points: List<Float>, windowSize: Int = 3): List<Float> {
    if (points.size < 2 || windowSize <= 1) return points
    val half = windowSize / 2
    return points.indices.map { i ->
        val start = (i - half).coerceAtLeast(0)
        val end = (i + half).coerceAtMost(points.size - 1)
        points.subList(start, end + 1).average().toFloat()
    }
}

/**
 * Expands a too-narrow data range symmetrically around its midpoint so [Sparkline] never
 * visually exaggerates noise within a range too small to be meaningful (backlog item, Sweep
 * 2026-09-28/29) -- without this, a near-constant PID (e.g. battery voltage sitting at ~12.6V
 * with only sensor noise) auto-scales to fill the full tile height on fluctuations that carry no
 * information. [floorFraction] is relative to the series' own magnitude rather than a fixed
 * absolute unit, since [Sparkline] is domain-agnostic and RPM/voltage need very different
 * absolute floors. `coerceAtLeast(0.001f)` on the floor itself covers the degenerate all-zero
 * case (`magnitude == 0f`), mirroring [sparklinePointOffset]'s own divide-by-zero guard.
 */
internal fun effectiveSparklineRange(min: Float, max: Float, floorFraction: Float = 0.05f): Pair<Float, Float> {
    val magnitude = maxOf(kotlin.math.abs(min), kotlin.math.abs(max))
    val floor = (magnitude * floorFraction).coerceAtLeast(0.001f)
    if (max - min >= floor) return min to max
    val mid = (min + max) / 2f
    return (mid - floor / 2f) to (mid + floor / 2f)
}

/**
 * Pure min/max-to-pixel mapping for a single sparkline point, extracted from the [Sparkline]
 * Canvas block for JVM testability (mirrors [smoothSparklinePoints] in that regard). Takes the
 * Canvas' own `size.width`/`size.height` and an already-density-resolved [inset] (the Composable
 * still does the `2.dp.toPx()` conversion itself, since that needs a `Density` receiver this
 * function doesn't have) so the formula itself stays plain-Kotlin/testable.
 *
 * [range] is guarded with `coerceAtLeast(0.001f)` — without it, a perfectly flat line (`min ==
 * max`) would make `range == 0f`, and `(value - min) / range` would evaluate to `0f / 0f == NaN`
 * (float division never throws, it silently produces NaN/Infinity), propagating a non-finite `y`
 * into the drawn path. [usableHeight] is guarded the same way for a degenerate (near-zero-height)
 * Canvas.
 */
internal fun sparklinePointOffset(
    index: Int,
    value: Float,
    pointCount: Int,
    min: Float,
    max: Float,
    width: Float,
    height: Float,
    inset: Float,
): Offset {
    val range = (max - min).coerceAtLeast(0.001f)
    val usableHeight = (height - 2 * inset).coerceAtLeast(1f)
    val x = index / (pointCount - 1f) * width
    val y = inset + usableHeight - (value - min) / range * usableHeight
    return Offset(x, y)
}

/**
 * Trend line for dashboard tiles. Renders nothing if fewer than two points are available;
 * auto-scales to the available range so the trace fills the box vertically. Color defaults to
 * the active theme's primary — sparklines represent trend, not state, so they don't take the
 * [LiveValueTile] status accent.
 *
 * Die Linie ist bewusst kraeftig: 4 dp mit runden Enden, unterlegt mit einem nach unten
 * auslaufenden Verlauf, plus ein Punkt auf dem juengsten Wert. Auf einem Motorrad-Display bei
 * Tageslicht war die urspruengliche 2-dp-Linie ohne Flaeche kaum auszumachen.
 */
@Composable
fun Sparkline(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (points.size < 2) return
    val smoothed = smoothSparklinePoints(points)
    Canvas(modifier) {
        val (min, max) = effectiveSparklineRange(smoothed.min(), smoothed.max())
        // Die Linie laesst oben und unten je 2 dp Luft, damit der runde Strich an den
        // Extremwerten nicht am Rand abgeschnitten wird.
        val inset = 2.dp.toPx()
        val path = Path()
        smoothed.forEachIndexed { i, v ->
            val point = sparklinePointOffset(i, v, smoothed.size, min, max, size.width, size.height, inset)
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        // Flaeche unter der Kurve als auslaufender Verlauf: gibt der Linie Koerper und einen
        // Schwerpunkt, ohne als zweite Flaeche mit dem Wert zu konkurrieren.
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.32f), Color.Transparent),
                startY = 0f,
                endY = size.height,
            ),
        )
        drawPath(
            path,
            color,
            style = Stroke(width = SPARKLINE_STROKE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // Punkt auf dem juengsten Wert: markiert, wo "jetzt" liegt — ohne ihn liest sich die
        // Kurve in beide Richtungen gleich.
        val lastPoint =
            sparklinePointOffset(smoothed.size - 1, smoothed.last(), smoothed.size, min, max, size.width, size.height, inset)
        drawCircle(color, radius = SPARKLINE_DOT_RADIUS.toPx(), center = Offset(size.width, lastPoint.y))
    }
}

// region — Previews

private val PREVIEW_CLIMB = listOf(60f, 62f, 61f, 65f, 70f, 74f, 73f, 78f, 83f, 87f, 90f)
private val PREVIEW_FLAT = List(10) { 88f }

@Preview(name = "Sparkline · rising (light)", showBackground = true, widthDp = 240, heightDp = 96)
@Composable
private fun PreviewSparklineRisingLight() {
    PreviewTheme(dark = false) { Sparkline(PREVIEW_CLIMB, Modifier.fillMaxWidth().height(48.dp).padding(16.dp)) }
}

@Preview(name = "Sparkline · rising (dark)", showBackground = true, widthDp = 240, heightDp = 96)
@Composable
private fun PreviewSparklineRisingDark() {
    PreviewTheme(dark = true) { Sparkline(PREVIEW_CLIMB, Modifier.fillMaxWidth().height(48.dp).padding(16.dp)) }
}

@Preview(name = "Sparkline · flat (dark)", showBackground = true, widthDp = 240, heightDp = 96)
@Composable
private fun PreviewSparklineFlatDark() {
    PreviewTheme(dark = true) { Sparkline(PREVIEW_FLAT, Modifier.fillMaxWidth().height(48.dp).padding(16.dp)) }
}

// endregion
