package de.adv990de.obd2.ui.dashboard

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.fuel.FuelGaugeState
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.components.FUEL_GAUGE_ROW_TEST_TAG
import de.adv990de.obd2.ui.components.FuelGaugeRow
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Die Oelkanne im Drehzahlbalken und das Tankstellen-Symbol der Tankzeile stehen an derselben **sichtbaren** linken Kante
 * (Nutzerwunsch 2026-10-15: „Das Oelsymbol im gleichen Abstand wie das Tankstellen-Icon“).
 *
 * Das ist eine Aussage ueber gezeichnete Pixel, nicht ueber Layoutgrenzen: das Material-Symbol hat 4 dp Eigenrand, die selbstgezeichnete Kanne
 * 1 dp — gleiche Boxen ergaeben also eine Kante, die 3 dp daneben liegt. Deshalb misst dieser Test, wo in jeder Zeile der erste Bildpunkt beginnt, der
 * sich vom Hintergrund am Rand abhebt. Eine Ausnahme von der Linie der Klasse `RpmBarTest` („keine Pixel-Pruefung“), mit Absicht: die Zahlen dort
 * (11 dp, 8 dp) sind nur der Weg, die Kante ist das Ziel — und ein neues Material-Symbol mit anderem Eigenrand finge sonst niemand.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DashboardLeftEdgeAlignmentDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /** Pflichtbaustein jeder @HiltAndroidTest-Klasse hier, s. `SectionLabelTest`. */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private val density get() = composeRule.activity.resources.displayMetrics.density

    private fun showBarAboveFuelRow() {
        composeRule.setContent {
            MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
                Column(Modifier.fillMaxWidth()) {
                    RpmBar(rpm = 4500f, threshold = 7000, gear = 3, oilBarColor = OilBarColor.Green)
                    FuelGaugeRow(state = FuelGaugeState(rangeFullKm = 320, kmSinceFill = 70f), onResetRequested = {})
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Summe der Kanalabstaende zweier Farben (0 = gleich, 765 = Schwarz gegen Weiss). */
    private fun distance(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    /**
     * Der kleinste x-Wert (in px) zwischen `top` und `bottom`, an dem ein Bildpunkt vom Hintergrund der Zeile abweicht — als Hintergrund gilt der
     * Bildpunkt ganz links (x = 0) derselben Zeile. [maxX] begrenzt die Suche auf das Symbol (der Balken dahinter zaehlt nicht).
     * Schwelle 60: ein Rand, der nur zu etwa 15 % abgedeckt ist (Kantenglaettung), zaehlt schon.
     */
    private fun firstInkX(bitmap: Bitmap, top: Int, bottom: Int, maxX: Int): Int {
        var best = Int.MAX_VALUE
        for (y in top until bottom) {
            val background = bitmap.getPixel(0, y)
            for (x in 1 until minOf(maxX, best)) {
                if (distance(bitmap.getPixel(x, y), background) > 60) {
                    best = x
                    break
                }
            }
        }
        return best
    }

    @Test
    fun theOilCan_andThePumpIcon_startAtTheSameVisibleLeftEdge() {
        showBarAboveFuelRow()

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val can = composeRule.onNodeWithTag(OIL_BAR_ICON_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val fuelRow = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val searchWidth = (48 * density).toInt()   // Symbole liegen bei 16..40 dp, der Balken der Tankzeile beginnt erst bei 52 dp

        val canEdge = firstInkX(bitmap, can.top.toInt(), can.bottom.toInt(), searchWidth)
        val pumpEdge = firstInkX(bitmap, fuelRow.top.toInt(), fuelRow.bottom.toInt(), searchWidth)

        assertTrue("Oelkanne gefunden ($canEdge)", canEdge < searchWidth)
        assertTrue("Tankstellen-Symbol gefunden ($pumpEdge)", pumpEdge < searchWidth)
        assertEquals("sichtbare linke Kante: Kanne $canEdge px, Tankstellen-Symbol $pumpEdge px", pumpEdge.toFloat(), canEdge.toFloat(), 1.5f)
    }

    /** Die Tinte eines Symbols: Umriss (px) und Anteil der Tintenpunkte an der Box. Tinte = weicht deutlich (> 260 von 765) vom Hintergrund der Zeile ab (Bildpunkt x = 0). */
    private data class Ink(val left: Int, val right: Int, val top: Int, val bottom: Int, val share: Float)

    private fun inkOf(bitmap: Bitmap, box: androidx.compose.ui.geometry.Rect): Ink {
        var left = Int.MAX_VALUE; var right = -1; var top = Int.MAX_VALUE; var bottom = -1; var ink = 0
        val x0 = box.left.toInt(); val x1 = box.right.toInt(); val y0 = box.top.toInt(); val y1 = box.bottom.toInt()
        for (y in y0 until y1) {
            val background = bitmap.getPixel(0, y)
            for (x in x0 until x1) {
                if (distance(bitmap.getPixel(x, y), background) > 260) {
                    ink++
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return Ink(left, right, top, bottom, ink.toFloat() / ((x1 - x0) * (y1 - y0)))
    }

    @Test
    fun theOilCan_isAnOutlineLikeTheOilPressureSymbol_notASolidSilhouette() {
        showBarAboveFuelRow()

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val can = composeRule.onNodeWithTag(OIL_BAR_ICON_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val ink = inkOf(bitmap, can)

        // Nutzerwunsch 2026-10-16: die Kanne "analog zum Oeldruck-Symbol" (ISO 7000): hohler Koerper, hohler Griff, Umriss statt Flaeche. Die fruehere gefuellte
        // Silhouette deckte 45 % ihrer Box (am Geraet gemessen: 0,4527), die Kontur etwa ein Drittel (aus den Pfaden gerechnet: 0,33). Die Obergrenze 0,40 trennt
        // beide; die Untergrenze faengt eine verschwundene Zeichnung ab.
        assertTrue("Anteil der Tinte in der Kanne: ${ink.share}", ink.share in 0.15f..0.40f)
    }

    @Test
    fun theOilCan_fillsItsBoxWithOnly1DpOfMarginOnTheLeft() {
        showBarAboveFuelRow()

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val can = composeRule.onNodeWithTag(OIL_BAR_ICON_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val ink = inkOf(bitmap, can)

        // Auf den Inhalt zugeschnitten: die Hoehe der Box ist die Hoehe der Zeichnung (16 dp), links 1 dp Eigenrand (darauf beruht OIL_ROW_START_PADDING),
        // rechts hoechstens 1 dp. Ein Symbol mit mehr leerem Rand verschoeben die sichtbare Kante gegen die Tankstelle und liessen die Kanne kleiner wirken.
        assertEquals("Tinte oben bis unten: ${ink.top}..${ink.bottom} von ${can.top}..${can.bottom}", can.height, (ink.bottom - ink.top + 1).toFloat(), 1.5f)
        assertEquals("linker Eigenrand 1 dp: ${ink.left - can.left} px", 1 * density, ink.left - can.left, 1.5f)
        assertTrue("rechter Rand hoechstens 1,5 dp: ${can.right - ink.right} px", can.right - ink.right <= 1.5f * density)
    }

    @Test
    fun thePumpIcon_sitsAt12DpFromTheLeftEdge() {
        showBarAboveFuelRow()

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val fuelRow = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val pumpEdge = firstInkX(bitmap, fuelRow.top.toInt(), fuelRow.bottom.toInt(), (48 * density).toInt())

        // Nutzerwunsch 2026-10-15: Oelkanne und Tankstellen-Symbol 12 dp vom linken Rand (8 dp Zeilenrand + 4 dp Eigenrand des Symbols; vorher 16 + 4 = 20 dp).
        assertEquals("Tankstellen-Symbol sichtbar bei 12 dp: $pumpEdge px", 12 * density, pumpEdge.toFloat(), 1.5f)
    }
}
