package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.geometry.Offset
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.terrain.CompassPoint
import de.adv990de.obd2.withDefaultLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.hypot

/** Die reinen Entscheidungen der Gelaende-Kachel: Zahlen und Texte, die Skala des Graphen, die Geometrie von Graph und Nadel. */
class TerrainTileLogicTest {

    private val letters: (CompassPoint) -> String = { it.name }

    private fun us(block: () -> String) = withDefaultLocale(Locale.US, block)

    // --- Hoehe ---

    private fun alt(meters: Float?, imperial: Boolean = false) =
        withDefaultLocale(Locale.US) { formatAltitude(meters, imperial) }

    @Test
    fun `the altitude is a whole number in metres`() {
        assertEquals("1234" to "m", alt(1234.4f))
        assertEquals("1235" to "m", alt(1234.5f))
    }

    @Test
    fun `imperial units show feet`() {
        // 1000 m = 3280,84 ft
        assertEquals("3281" to "ft", alt(1000f, imperial = true))
    }

    @Test
    fun `an altitude below sea level keeps its sign`() {
        assertEquals("-12" to "m", alt(-12f))
    }

    @Test
    fun `a small negative altitude shows 0, not -0`() {
        assertEquals("0" to "m", alt(-0.3f))
    }

    @Test
    fun `no altitude is two dashes and still has its unit`() {
        assertEquals("--" to "m", formatAltitude(null, imperial = false))
        assertEquals("--" to "ft", formatAltitude(null, imperial = true))
        assertEquals("--" to "m", formatAltitude(Float.NaN, imperial = false))
    }

    // --- Steigung ---

    @Test
    fun `a climb has a plus, a descent a real minus sign`() {
        assertEquals("+4 %", us { formatGrade(4f) })
        assertEquals("−6 %", us { formatGrade(-6f) })
    }

    @Test
    fun `the grade is rounded to whole percent`() {
        assertEquals("+5 %", us { formatGrade(4.5f) })
        assertEquals("+4 %", us { formatGrade(4.49f) })
        assertEquals("−5 %", us { formatGrade(-4.5f) })
    }

    @Test
    fun `a grade that rounds to zero has no sign at all`() {
        assertEquals("0 %", us { formatGrade(0f) })
        assertEquals("0 %", us { formatGrade(0.49f) })
        assertEquals("0 %", us { formatGrade(-0.49f) })
        assertEquals("0 %", us { formatGrade(-0.0f) })
    }

    @Test
    fun `no grade is two dashes`() {
        assertEquals("--", formatGrade(null))
        assertEquals("--", formatGrade(Float.NaN))
    }

    @Test
    fun `the trend follows the rounded number as shown`() {
        assertEquals(GradeTrend.Up, gradeTrend(0.5f))
        assertEquals(GradeTrend.Down, gradeTrend(-0.5f))
        assertEquals(GradeTrend.Flat, gradeTrend(0.49f))
        assertEquals(GradeTrend.Flat, gradeTrend(-0.49f))
        assertEquals(null, gradeTrend(null))
        assertEquals(null, gradeTrend(Float.NaN))
    }

    // --- Peilung ---

    @Test
    fun `the heading reads as direction and whole degrees`() {
        assertEquals("NE 45°", us { headingText(45f, letters) })
        assertEquals("W 270°", us { headingText(270f, letters) })
    }

    @Test
    fun `degrees round to whole numbers and wrap at 360`() {
        assertEquals("N 0°", us { headingText(359.6f, letters) })
        assertEquals("N 0°", us { headingText(0f, letters) })
        assertEquals("NE 23°", us { headingText(22.6f, letters) })
    }

    @Test
    fun `no heading is two dashes`() {
        assertEquals("--", headingText(null, letters))
        assertEquals("--", headingText(Float.NaN, letters))
    }

    // --- Skala des Graphen ---

    @Test
    fun `the scale is at least ten percent`() {
        assertEquals(10f, gradeGraphScale(emptyList()), 0f)
        assertEquals(10f, gradeGraphScale(listOf(2f, -3f, 7f)), 0f)
        assertEquals(10f, gradeGraphScale(listOf(10f)), 0f)
    }

    @Test
    fun `a steeper history rounds the scale up to the next multiple of five`() {
        assertEquals(15f, gradeGraphScale(listOf(12f)), 0f)
        assertEquals(25f, gradeGraphScale(listOf(1f, -23f)), 0f)
        assertEquals(15f, gradeGraphScale(listOf(15f)), 0f)
        assertEquals(20f, gradeGraphScale(listOf(15.1f)), 0f)
    }

    // --- Punkte des Graphen ---

    @Test
    fun `zero is on the middle line, plus scale at the top, minus scale at the bottom`() {
        val points = gradeGraphPoints(listOf(0f, 10f, -10f), scale = 10f, widthPx = 390f, heightPx = 100f)
        assertEquals(50f, points[0].y, 0.001f)
        assertEquals(0f, points[1].y, 0.001f)
        assertEquals(100f, points[2].y, 0.001f)
    }

    @Test
    fun `a value beyond the scale is clamped to the edge`() {
        val points = gradeGraphPoints(listOf(40f, -40f), scale = 10f, widthPx = 390f, heightPx = 100f)
        assertEquals(0f, points[0].y, 0.001f)
        assertEquals(100f, points[1].y, 0.001f)
    }

    @Test
    fun `the newest point sits at the right edge and the history grows leftwards`() {
        val points = gradeGraphPoints(listOf(1f, 2f, 3f), scale = 10f, widthPx = 390f, heightPx = 100f)
        // 40 Plaetze (die letzten 1000 m), Abstand 390 / 39 = 10 px
        assertEquals(390f, points.last().x, 0.001f)
        assertEquals(380f, points[1].x, 0.001f)
        assertEquals(370f, points[0].x, 0.001f)
    }

    @Test
    fun `a full history spans the whole width`() {
        val points = gradeGraphPoints(List(40) { 0f }, scale = 10f, widthPx = 390f, heightPx = 100f)
        assertEquals(0f, points.first().x, 0.001f)
        assertEquals(390f, points.last().x, 0.001f)
    }

    @Test
    fun `no history gives no points`() {
        assertTrue(gradeGraphPoints(emptyList(), scale = 10f, widthPx = 390f, heightPx = 100f).isEmpty())
    }

    // --- Nadel der Windrose ---

    private val center = Offset(100f, 100f)

    @Test
    fun `north points up, east right, south down, west left`() {
        val n = roseNeedle(0f, 50f, center).first()
        assertEquals(100f, n.x, 0.01f); assertEquals(50f, n.y, 0.01f)
        val e = roseNeedle(90f, 50f, center).first()
        assertEquals(150f, e.x, 0.01f); assertEquals(100f, e.y, 0.01f)
        val s = roseNeedle(180f, 50f, center).first()
        assertEquals(100f, s.x, 0.01f); assertEquals(150f, s.y, 0.01f)
        val w = roseNeedle(270f, 50f, center).first()
        assertEquals(50f, w.x, 0.01f); assertEquals(100f, w.y, 0.01f)
    }

    @Test
    fun `the tip is exactly the needle length from the centre`() {
        for (deg in listOf(0f, 30f, 45f, 137f, 200f, 359f)) {
            val tip = roseNeedle(deg, 50f, center).first()
            assertEquals("bei $deg Grad", 50f, hypot(tip.x - center.x, tip.y - center.y), 0.01f)
        }
    }

    @Test
    fun `north east is half way between up and right`() {
        val tip = roseNeedle(45f, 50f, center).first()
        assertEquals(100f + 50f * 0.70710678f, tip.x, 0.01f)
        assertEquals(100f - 50f * 0.70710678f, tip.y, 0.01f)
    }

    @Test
    fun `the needle is a symmetric arrow with four points`() {
        val points = roseNeedle(90f, 50f, center)
        assertEquals(4, points.size)
        // Bei Ost zeigt der Pfeil nach rechts: die beiden Fusspunkte liegen ueber und unter der Mittelachse, gleich weit.
        val (_, right, _, left) = points
        assertEquals(center.y - right.y, left.y - center.y, 0.01f)
        assertTrue(right.y != left.y)
    }

    // --- Texte der leeren Kachel ---

    @Test
    fun `the terrain slot says what is missing instead of unsupported by the vehicle`() {
        val terrain = DerivedPids.TERRAIN_ID
        // Mit Steuergeraet, das TERRAIN nicht meldet (es ist kein PID): nie "Nicht vom Fahrzeug".
        assertEquals(R.string.dashboard_slot_terrain_gps_off, slotPlaceholderRes(terrain, setOf("010C"), isConnected = true, gpsSpeedEnabled = false))
        assertEquals(R.string.dashboard_slot_gps_permission, slotPlaceholderRes(terrain, setOf("010C"), isConnected = true, gpsSpeedEnabled = true, gpsPermissionMissing = true))
        assertEquals(R.string.dashboard_slot_waiting_gps, slotPlaceholderRes(terrain, setOf("010C"), isConnected = true, gpsSpeedEnabled = true))
    }
}
