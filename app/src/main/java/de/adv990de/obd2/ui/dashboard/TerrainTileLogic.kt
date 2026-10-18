package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.geometry.Offset
import de.adv990de.obd2.obd.convertUnit
import de.adv990de.obd2.obd.formatSensorNumber
import de.adv990de.obd2.terrain.CompassPoint
import de.adv990de.obd2.terrain.GradeEstimator
import de.adv990de.obd2.terrain.compassPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Die reinen Entscheidungen der Gelaende-Kachel ([TerrainTile]): Zahlen und Texte, die Skala des Steigungs-Graphen, die Geometrie von Graph und Windrose.
 * Ohne Compose-Zeichnen, damit sie im JVM-Test stehen (Muster `smoothSparklinePoints`).
 */

/** Anzeige fuer fehlende Werte — dieselbe wie jede Kachel (`SensorCard`). */
internal const val NO_VALUE_TEXT = "--"

/** Das Minuszeichen der Steigung: das echte (U+2212), das mit dem Plus gleich breit ist; der Bindestrich saehe neben der Plex-Mono-Ziffer zu kurz aus. */
private const val MINUS_SIGN = "−"

/**
 * Die Hoehe als (Zahl, Einheit): ganze Meter, bei imperialen Einheiten Fuss (`convertUnit` kennt "m" nicht, die Rechnung steht hier: 1 m = 3,28084 ft). Ein negativer Wert,
 * der auf 0 rundet, zeigt kein Vorzeichen (`formatSensorNumber`). Ohne Wert "--", die Einheit bleibt stehen.
 */
internal fun formatAltitude(meters: Float?, imperial: Boolean): Pair<String, String> {
    val unit = if (imperial) "ft" else "m"
    if (meters == null || !meters.isFinite()) return NO_VALUE_TEXT to unit
    val shown = if (imperial) meters * FEET_PER_METRE else meters
    return formatSensorNumber(shown, unit) to unit
}

private const val FEET_PER_METRE = 3.28084f

/**
 * Die Steigung: "+4 %", "−6 %", "0 %" — ganze Prozent, ein Vorzeichen nur, wenn die gerundete Zahl nicht 0 ist (nie "−0"). Ohne Wert "--".
 */
internal fun formatGrade(percent: Float?): String {
    if (percent == null || !percent.isFinite()) return NO_VALUE_TEXT
    val rounded = abs(percent).roundToInt()
    val number = formatSensorNumber(rounded.toFloat(), "%")
    return when {
        rounded == 0 -> "$number %"
        percent > 0f -> "+$number %"
        else -> "$MINUS_SIGN$number %"
    }
}

/** Die Richtung der Steigung fuer die Vorlese-Beschreibung ("plus", "minus", "null"): nach der **gerundeten** Zahl, wie sie auf der Kachel steht. */
internal enum class GradeTrend { Up, Down, Flat }

internal fun gradeTrend(percent: Float?): GradeTrend? {
    if (percent == null || !percent.isFinite()) return null
    return when {
        abs(percent).roundToInt() == 0 -> GradeTrend.Flat
        percent > 0f -> GradeTrend.Up
        else -> GradeTrend.Down
    }
}

/**
 * Die Peilung als Himmelsrichtung mit ganzen Grad, "NO 45°". Die Richtung kommt aus dem ungerundeten Winkel ([compassPoint]), die Grad werden auf ganze Zahlen
 * gerundet und bei 360 auf 0 gewickelt. [label] liefert den Text einer Richtung (aus den Zeichenketten der Sprache). Ohne Wert "--".
 */
internal fun headingText(headingDeg: Float?, label: (CompassPoint) -> String): String {
    if (headingDeg == null || !headingDeg.isFinite()) return NO_VALUE_TEXT
    val point = compassPoint(headingDeg) ?: return NO_VALUE_TEXT
    val normalized = ((headingDeg.toDouble() % 360.0) + 360.0) % 360.0
    val degrees = normalized.roundToInt() % 360
    return "${label(point)} ${formatSensorNumber(degrees.toFloat(), "°")}°"
}

/** Die Steigung (in %), die der Graph an seinem oberen bzw. unteren Rand zeigt: mindestens 10, bei steilerem Verlauf auf das naechste Vielfache von 5 aufgerundet. */
internal fun gradeGraphScale(values: List<Float>): Float {
    val peak = values.maxOfOrNull { if (it.isFinite()) abs(it) else 0f } ?: 0f
    return maxOf(MIN_GRADE_SCALE_PERCENT, ceil(peak / 5f) * 5f)
}

private const val MIN_GRADE_SCALE_PERCENT = 10f

/**
 * Die Punkte des Steigungs-Graphen in Pixeln: **Null liegt auf der Mittellinie**, `+scale` am oberen, `-scale` am unteren Rand (ein Wert darueber hinaus wird an den Rand
 * gedrueckt); die [GradeEstimator.HISTORY_POINTS] Plaetze (die letzten 1000 m) teilen die Breite, der **juengste Wert sitzt am rechten Rand** und der Verlauf waechst nach links —
 * wie ein Schreiber, nicht gestreckt auf die Breite, damit ein Punkt je 25 m immer gleich weit auseinander liegt.
 */
internal fun gradeGraphPoints(values: List<Float>, scale: Float, widthPx: Float, heightPx: Float): List<Offset> {
    if (values.isEmpty()) return emptyList()
    val step = widthPx / (GradeEstimator.HISTORY_POINTS - 1)
    val middle = heightPx / 2f
    return values.mapIndexed { index, value ->
        val x = widthPx - (values.size - 1 - index) * step
        val y = (middle - value / scale * middle).coerceIn(0f, heightPx)
        Offset(x, y)
    }
}

/**
 * Die Nadel der Windrose als Pfeil mit vier Punkten (Spitze, rechter Fuss, Kerbe, linker Fuss): die Spitze liegt [lengthPx] vom Mittelpunkt entfernt in Richtung der Peilung
 * (0 Grad = oben, im Uhrzeigersinn), die Kerbe ein Viertel der Laenge dahinter, die Fuesse quer dazu, je 30 % der Laenge von der Mittelachse.
 */
internal fun roseNeedle(headingDeg: Float, lengthPx: Float, center: Offset): List<Offset> {
    val radians = Math.toRadians(headingDeg.toDouble())
    val dx = sin(radians).toFloat()
    val dy = -cos(radians).toFloat()          // Bildschirm-y waechst nach unten
    val px = -dy                              // quer zur Richtung (um 90 Grad im Uhrzeigersinn gedreht)
    val py = dx
    val half = lengthPx * 0.30f
    return listOf(
        Offset(center.x + dx * lengthPx, center.y + dy * lengthPx),
        Offset(center.x + px * half, center.y + py * half),
        Offset(center.x - dx * lengthPx * 0.25f, center.y - dy * lengthPx * 0.25f),
        Offset(center.x - px * half, center.y - py * half),
    )
}
