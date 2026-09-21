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
import androidx.compose.ui.unit.dp

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
    Canvas(modifier) {
        val min = points.min()
        val max = points.max()
        val range = (max - min).coerceAtLeast(0.001f)
        // Die Linie laesst oben und unten je 2 dp Luft, damit der runde Strich an den
        // Extremwerten nicht am Rand abgeschnitten wird.
        val inset = 2.dp.toPx()
        val usableHeight = (size.height - 2 * inset).coerceAtLeast(1f)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i / (points.size - 1f) * size.width
            val y = inset + usableHeight - (v - min) / range * usableHeight
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
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
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // Punkt auf dem juengsten Wert: markiert, wo "jetzt" liegt — ohne ihn liest sich die
        // Kurve in beide Richtungen gleich.
        val lastY = inset + usableHeight - (points.last() - min) / range * usableHeight
        drawCircle(color, radius = 4.dp.toPx(), center = Offset(size.width, lastY))
    }
}
