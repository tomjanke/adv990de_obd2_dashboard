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
