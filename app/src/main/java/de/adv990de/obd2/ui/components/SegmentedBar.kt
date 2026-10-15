package de.adv990de.obd2.ui.components

import androidx.compose.ui.unit.dp

/**
 * Abstand zwischen zwei Segmenten einer Segmentanzeige — **eine** Konstante fuer Drehzahlbalken (`RpmBar`) und Tankanzeige (`FuelGaugeRow`)
 * (Nutzerwunsch 2026-10-15: der Abstand der Tank-Segmente entspricht dem der Drehzahl-Segmente). Durch die Luecke scheint der Hintergrund,
 * so bleibt die Skala auch bei unbeleuchteten Segmenten in `surfaceVariant` lesbar. `FuelGaugePlacementSourceTest` verbietet ein zweites Literal.
 */
internal val SEGMENT_GAP = 1.5.dp

/**
 * Breite eines Segments in Pixeln: der Rest der Breite [totalPx] nach Abzug der `count - 1` Luecken à [gapPx], gleichmaessig auf [count] Segmente
 * geteilt — das letzte Segment endet damit genau am rechten Rand. Mindestens ein Pixel, damit ein zu schmaler Balken nicht verschwindet.
 * Reine Funktion, JVM-getestet; die Segmente liegen bei `i * (breite + gapPx)`.
 */
internal fun segmentWidthPx(totalPx: Float, gapPx: Float, count: Int): Float =
    ((totalPx - gapPx * (count - 1)) / count).coerceAtLeast(1f)
