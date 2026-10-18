package de.adv990de.obd2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Steigungs-Linie der Gelaende-Kachel hat **dieselbe Staerke** wie die Sparklines der anderen Kacheln (Nutzerwunsch 2026-10-18: 4 dp Strich, 4 dp Punkt). Das haelt eine gemeinsame
 * Konstante fest, kein zweites Literal: wer die Staerke der Sparkline aendert, aendert die der Steigung mit. Gegenprobe am Bild: `TerrainTileDeviceTest`
 * (`theGradeLineIsAsThickAsASparkline`).
 */
class SparklineStrokeSourceTest {

    private val main = File(projectRoot, "app/src/main/java/de/adv990de/obd2/ui")

    private val sparkline get() = File(main, "components/Sparkline.kt").readText()
    private val gradeGraph get() = File(main, "dashboard/TerrainTile.kt").readText().substringAfter("internal fun GradeGraph(").substringBefore("\n}\n")

    @Test
    fun `the sparkline names its stroke width and its dot radius once`() {
        assertTrue("SPARKLINE_STROKE_WIDTH = 4.dp", Regex("""val SPARKLINE_STROKE_WIDTH\b[^=]*=\s*4\.dp""").containsMatchIn(sparkline))
        assertTrue("SPARKLINE_DOT_RADIUS = 4.dp", Regex("""val SPARKLINE_DOT_RADIUS\b[^=]*=\s*4\.dp""").containsMatchIn(sparkline))
        assertTrue("die Sparkline zeichnet mit der Konstante", sparkline.contains("Stroke(width = SPARKLINE_STROKE_WIDTH.toPx()"))
        assertTrue("der Punkt der Sparkline nutzt die Konstante", sparkline.contains("radius = SPARKLINE_DOT_RADIUS.toPx()"))
        assertEquals("kein zweites Literal fuer die Strichstaerke in der Sparkline", 0, Regex("""Stroke\(width = \d""").findAll(sparkline).count())
    }

    @Test
    fun `the grade line is drawn with the sparkline stroke width and dot radius`() {
        val graph = gradeGraph

        assertTrue("GradeGraph gefunden", graph.contains("drawPath("))
        assertTrue("die Steigungslinie nutzt SPARKLINE_STROKE_WIDTH", graph.contains("Stroke(width = SPARKLINE_STROKE_WIDTH.toPx()"))
        assertTrue("der Punkt der Steigung nutzt SPARKLINE_DOT_RADIUS", graph.contains("radius = SPARKLINE_DOT_RADIUS.toPx()"))
        assertFalse("kein eigenes Literal fuer die Linienstaerke der Steigung", Regex("""Stroke\(width = \d""").containsMatchIn(graph))
        assertFalse("kein eigener Punktradius der Steigung", Regex("""radius = \d""").containsMatchIn(graph))
    }
}
