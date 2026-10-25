package de.adv990de.obd2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Das Balkendiagramm auf dem Geraet, **aus dem gerenderten Bild gelesen**: Die Geometrie rechnet [barChartLayout] (JVM-getestet); hier steht, dass die Zeichnung sie einhaelt — Balken auf der
 * Grundlinie in der Farbe `primary`, die Hoehen im Verhaeltnis, der juengste rechts, hoechstens 24 Balken, die gestrichelte Linie des Durchschnitts in der richtigen Hoehe, die Grundlinie, die
 * Beschriftung und die Beschreibung fuer Screenreader. Die Farben des Schemas sind im Test grell und verschieden, damit ein Bildpunkt eindeutig einer Zeichnung gehoert.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BarChartDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private val bar = Color(0xFFE53935)
    private val baseline = Color(0xFF00A000)
    private val average = Color(0xFF1E40FF)
    private val white = Color.White

    private val colors = lightColorScheme(primary = bar, outline = baseline, onSurfaceVariant = average, surface = white, background = white)

    private fun show(values: List<Float>, avg: Float?, showLabels: Boolean = false, widthDp: Int = 300, heightDp: Int = 150) {
        composeRule.setContent {
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                // Ein deckender Grund: ohne ihn liest das Bild "Hintergrund" als transparentes Schwarz.
                Box(Modifier.size(widthDp.dp, heightDp.dp).background(white)) {
                    BarChart(
                        values = values,
                        average = avg,
                        valueLabel = { "%.1f".format(it) },
                        description = DESCRIPTION,
                        modifier = Modifier.fillMaxSize().testTag(TAG),
                        showLabels = showLabels,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun image(): PixelMap = composeRule.onNodeWithTag(TAG).captureToImage().toPixelMap()

    private fun Color.near(other: Color, tolerance: Float = 0.12f) =
        abs(red - other.red) < tolerance && abs(green - other.green) < tolerance && abs(blue - other.blue) < tolerance

    private val density: Float get() = composeRule.activity.resources.displayMetrics.density

    /** Die Hoehe der Farbe [color] in der Spalte [x]: Abstand vom unteren Rand bis zum obersten Bildpunkt der Farbe in einer zusammenhaengenden Saeule (0, wenn keiner). */
    private fun PixelMap.columnHeight(x: Int, color: Color, fromBottom: Int): Int {
        var y = height - 1 - fromBottom
        var count = 0
        while (y >= 0 && this[x, y].near(color)) { count++; y-- }
        return count
    }

    @Test
    fun theBarsSitOnTheBaselineWhereTheLayoutPutsThem_andAboveThemIsBackground() {
        val values = listOf(3f, 5f, 4f, 2f)
        show(values, null)
        val img = image()
        val layout = barChartLayout(values, null, img.width.toFloat(), img.height.toFloat())

        for (b in layout.bars) {
            val cx = ((b.left + b.right) / 2f).roundToInt()
            assertTrue("auf der Grundlinie, Spalte $cx", img[cx, img.height - 8].near(bar))
            assertTrue("mitten im Balken, Spalte $cx", img[cx, ((b.top + b.bottom) / 2f).roundToInt()].near(bar))
            assertTrue("ueber dem Balken ist Hintergrund, Spalte $cx", img[cx, (b.top - 4f).roundToInt().coerceAtLeast(0)].near(white))
        }
    }

    @Test
    fun theHeightsAreInTheRatioOfTheValues_andTheNewestIsTheRightmostBar() {
        val values = listOf(2f, 4f, 6f)
        show(values, null)
        val img = image()
        val layout = barChartLayout(values, null, img.width.toFloat(), img.height.toFloat())
        val heights = layout.bars.map { img.columnHeight(((it.left + it.right) / 2f).roundToInt(), bar, fromBottom = 8) }

        assertTrue("$heights", heights[0] < heights[1] && heights[1] < heights[2])
        // Gemessen wird ab 8 Bildpunkten ueber dem unteren Rand (dort deckt die Grundlinie die Balken nicht mehr): die Hoehe laut Rechnung minus diese 9 Bildpunkte, auf 3 genau
        layout.bars.forEachIndexed { i, b -> assertEquals("Balken $i", (b.bottom - b.top) - 9.0, heights[i].toDouble(), 3.0) }
        assertTrue("der juengste Balken endet am rechten Rand", layout.bars.last().right > img.width * 0.9f)
    }

    @Test
    fun atMost24BarsAreDrawn_theNewest() {
        val values = (1..30).map { it.toFloat() }
        show(values, null, widthDp = 360)
        val img = image()
        val y = img.height - 8
        var runs = 0
        var inBar = false
        for (x in 0 until img.width) {
            val isBar = img[x, y].near(bar)
            if (isBar && !inBar) runs++
            inBar = isBar
        }

        assertEquals(24, runs)
    }

    @Test
    fun theAverageLineIsDashedAndSitsAtItsValue() {
        val values = listOf(2f, 4f, 3f, 5f)
        show(values, 3.5f)
        val img = image()
        val layout = barChartLayout(values, 3.5f, img.width.toFloat(), img.height.toFloat())
        val y = layout.averageY!!.roundToInt()
        val row = (0 until img.width).map { img[it, y] }
        val dashed = row.count { it.near(average, 0.2f) }
        val gaps = row.count { !it.near(average, 0.2f) }

        assertTrue("Linienfarbe in der Hoehe des Durchschnitts: $dashed von ${img.width}", dashed > img.width * 0.25f)
        assertTrue("gestrichelt, nicht durchgezogen: $gaps Luecken", gaps > img.width * 0.15f)
        // ein paar Bildpunkte darueber und darunter ist keine Linie
        assertTrue((0 until img.width).none { img[it, (y - 12).coerceAtLeast(0)].near(average, 0.1f) })
    }

    @Test
    fun theBaselineRunsAcrossTheWholeBottom() {
        show(listOf(3f, 5f), null)
        val img = image()
        val y = img.height - 1 - (density / 2f).roundToInt()

        assertTrue("Grundlinie in der Farbe outline", (0 until img.width step 7).all { img[it, y].near(baseline) })
    }

    @Test
    fun withoutValuesNothingIsDrawnExceptTheBaseline() {
        show(emptyList(), null)
        val img = image()

        assertTrue((0 until img.width step 5).all { x -> (0 until img.height - 8 step 5).all { y -> img[x, y].near(white) } })
    }

    @Test
    fun withLabels_aGutterOnTheLeftKeepsTheBarsAwayAndHoldsTheTextInTheLabelColor() {
        val values = listOf(3f, 5f, 4f)
        show(values, 4f, showLabels = true)
        val img = image()
        val firstBarColumn = (0 until img.width).first { x -> (0 until img.height).any { y -> img[x, y].near(bar) } }
        val textPixelsInGutter = (0 until firstBarColumn).sumOf { x -> (0 until img.height).count { y -> img[x, y].near(average, 0.25f) } }

        assertTrue("die Balken beginnen nicht ganz links: $firstBarColumn", firstBarColumn > 12)
        assertTrue("Text in der Beschriftungsfarbe links: $textPixelsInGutter Bildpunkte", textPixelsInGutter > 20)
    }

    @Test
    fun theDescriptionIsWhatTheScreenReaderGets() {
        show(listOf(3f, 5f), 4f)

        val description = composeRule.onNodeWithTag(TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertEquals(listOf(DESCRIPTION), description)
    }

    private companion object {
        const val TAG = "bar_chart_under_test"
        const val DESCRIPTION = "Verlauf, 2 Werte, Durchschnitt 4,0, zuletzt 5,0"
    }
}
