package de.adv990de.obd2.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.asLiveValue

/** Hoechstens so viele Balken zeigt das Diagramm: die juengsten Werte. */
internal const val BAR_CHART_MAX_BARS = 24

/** So viele Platzstellen hat das Diagramm mindestens: wenige Werte bleiben schmal und stehen rechts, statt die ganze Breite zu fuellen. */
private const val MIN_SLOTS = 6

/** Anteil der Platzstelle, den die Luecke zwischen zwei Balken einnimmt. */
private const val GAP_FRACTION = 0.25f

/** Luft ueber dem hoechsten Wert: die Skala endet bei Hoechstwert × diesem Faktor. */
private const val HEADROOM = 1.1f

/** Ein Balken in Bildpunkten (Ursprung oben links). */
internal data class BarRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Die Geometrie des Diagramms: [bars] (einer je gezeigtem Wert, der aelteste links), [shownValues] (die juengsten bis zu [BAR_CHART_MAX_BARS]), die Skala bis [axisMax],
 * [baselineY] (unten) und [averageY] (die Linie des Durchschnitts, `null` ohne Durchschnitt).
 */
internal data class BarChartLayout(
    val bars: List<BarRect>,
    val shownValues: List<Float>,
    val axisMax: Float,
    val baselineY: Float,
    val averageY: Float?,
)

/**
 * Rechnet die Geometrie fuer eine Zeichenflaeche von [width] × [height] Bildpunkten. Die Skala beginnt bei **0** und endet ueber dem hoechsten gezeigten Wert (oder dem
 * Durchschnitt, falls der hoeher ist); die Balken sind proportional, der **juengste steht rechts**, auch bei wenigen Werten (die Platzstellen beginnen von rechts). Ein Wert unter 0
 * hat keine Hoehe; ohne positive Werte gilt die Skala 0 bis 1 (keine Division durch null). Rein, damit sie ohne Compose pruefbar ist.
 */
internal fun barChartLayout(values: List<Float>, average: Float?, width: Float, height: Float): BarChartLayout {
    val shown = values.takeLast(BAR_CHART_MAX_BARS)
    val top = maxOf(shown.maxOrNull() ?: 0f, average ?: 0f)
    val axisMax = if (top > 0f) top * HEADROOM else 1f
    val slots = maxOf(shown.size, MIN_SLOTS)
    val slotWidth = width / slots
    val barWidth = slotWidth * (1f - GAP_FRACTION)
    val bars = shown.mapIndexed { i, value ->
        val left = (slots - shown.size + i) * slotWidth + (slotWidth - barWidth) / 2f
        BarRect(left, height - value.coerceAtLeast(0f) / axisMax * height, left + barWidth, height)
    }
    return BarChartLayout(bars, shown, axisMax, baselineY = height, averageY = average?.let { height - it.coerceAtLeast(0f) / axisMax * height })
}

/**
 * Ein Balkendiagramm als `Canvas`-Zeichnung (keine Bibliothek, wie [Sparkline]): ein Balken je Wert in `primary`, der **juengste rechts**, hoechstens die letzten
 * [BAR_CHART_MAX_BARS]; die Grundlinie bei 0 in `outline`; der Durchschnitt [average] als gestrichelte Linie in `onSurfaceVariant`. Mit [showLabels] stehen links der hoechste Wert und
 * die 0 sowie rechts ueber der Linie der Durchschnitt (`labelSmall`, Plex Mono mit `tnum`, Text von [valueLabel]); ohne bleibt die ganze Flaeche dem Diagramm (Miniatur).
 * Nicht animiert und nicht beruehrbar. [description] ist die Zusammenfassung fuer Screenreader (die Zeichnung selbst hat keine Semantik).
 */
@Composable
internal fun BarChart(
    values: List<Float>,
    average: Float?,
    valueLabel: (Float) -> String,
    description: String,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val barColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.outline
    val averageColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelBackground = MaterialTheme.colorScheme.surface
    val labelStyle = MaterialTheme.typography.labelSmall.asLiveValue().copy(color = averageColor)

    Canvas(modifier.semantics { contentDescription = description }) {
        val stroke = 1.dp.toPx()
        val pad = 4.dp.toPx()
        val shown = values.takeLast(BAR_CHART_MAX_BARS)
        val maxLabel = if (showLabels && shown.isNotEmpty()) measurer.measure(valueLabel(shown.max()), labelStyle) else null
        val zeroLabel = if (showLabels && shown.isNotEmpty()) measurer.measure("0", labelStyle) else null
        val gutter = if (maxLabel != null && zeroLabel != null) maxOf(maxLabel.size.width, zeroLabel.size.width) + pad else 0f

        val layout = barChartLayout(values, average, size.width - gutter, size.height)
        translate(left = gutter) {
            layout.bars.forEach { bar ->
                drawRect(barColor, topLeft = Offset(bar.left, bar.top), size = Size(bar.right - bar.left, bar.bottom - bar.top))
            }
            drawLine(lineColor, Offset(0f, size.height - stroke / 2f), Offset(size.width - gutter, size.height - stroke / 2f), strokeWidth = stroke)
            layout.averageY?.let { y ->
                drawLine(
                    averageColor,
                    Offset(0f, y),
                    Offset(size.width - gutter, y),
                    strokeWidth = stroke * 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                )
            }
        }

        if (maxLabel != null && zeroLabel != null) {
            val highest = layout.bars.minOfOrNull { it.top } ?: 0f
            drawText(maxLabel, topLeft = Offset(0f, highest.coerceIn(0f, size.height - maxLabel.size.height)))
            drawText(zeroLabel, topLeft = Offset(0f, size.height - zeroLabel.size.height))
            if (average != null && layout.averageY != null) drawAverageLabel(measurer, valueLabel(average), labelStyle, labelBackground, layout.averageY, gutter, pad)
        }
    }
}

/** Der Durchschnitt als Wert ueber der Linie am rechten Rand; ein Rechteck in der Flaechenfarbe haelt ihn auch ueber den Balken lesbar. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAverageLabel(
    measurer: TextMeasurer,
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    background: androidx.compose.ui.graphics.Color,
    lineY: Float,
    gutter: Float,
    pad: Float,
) {
    val label = measurer.measure("Ø $text", style)
    val x = size.width - label.size.width - pad
    val y = (lineY - label.size.height - 2.dp.toPx()).coerceAtLeast(0f)
    drawRect(background, topLeft = Offset(x - pad / 2f, y), size = Size(label.size.width + pad, label.size.height.toFloat()))
    drawText(label, topLeft = Offset(x, y))
}

// region — Previews

@Composable
private fun BarChartPreviewContent() {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        BarChart(
            values = listOf(7.4f, 8.1f, 6.9f, 7.7f, 9.2f, 7.1f, 7.5f, 8.0f, 6.6f, 7.9f, 7.3f, 7.8f),
            average = 7.7f,
            valueLabel = { "%.1f".format(it) },
            description = "Preview",
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        BarChart(
            values = listOf(7.4f, 8.1f, 6.9f),
            average = 7.5f,
            valueLabel = { "%.1f".format(it) },
            description = "Preview",
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = 16.dp),
            showLabels = false,
        )
    }
}

@Preview(name = "Bar chart (light)", showBackground = true)
@Composable
private fun PreviewBarChartLight() {
    PreviewTheme(dark = false) { BarChartPreviewContent() }
}

@Preview(name = "Bar chart (dark)", showBackground = true)
@Composable
private fun PreviewBarChartDark() {
    PreviewTheme(dark = true) { BarChartPreviewContent() }
}

// endregion
