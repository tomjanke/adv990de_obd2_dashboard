package de.adv990de.obd2.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.formatSensorNumber
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.blueColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Der Lade-/Entlade-Balken der Spannungs-Kachel auf dem Geraet (Entwurf `2026-10-19-voltage-gauge-design.md`): Lage und Mass im Kachelrahmen, die Fuellung als Bildpunkte gelesen (nicht nur
 * gerechnet), die Farbe je Zustand, die Nulllinie, der Zustand ohne Trend und die Beschreibung fuer Screenreader. Zustand, Glaettung und Geometrie-Rechnung pruefen die JVM-Tests.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VoltageGaugeDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private val colors = blueColorScheme

    private fun volts(value: Float) = SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, value)

    private fun show(
        reading: SensorValue = volts(13.6f),
        trend: VoltageTrend? = null,
        isLive: Boolean = true,
        widthDp: Int = 224,
        heightDp: Int = 170,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(colorScheme = colors, typography = AppTypography) {
                    Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                        SensorCard(value = reading, modifier = Modifier.size(widthDp.dp, heightDp.dp), voltageTrend = trend, isLive = isLive)
                    }
                }
            }
        }
    }

    private fun gauge() = composeRule.onNodeWithTag(VOLTAGE_GAUGE_TEST_TAG)

    private fun bounds(node: androidx.compose.ui.test.SemanticsNodeInteraction): Rect {
        val b = node.getBoundsInRoot()
        return Rect(b.left.value, b.top.value, b.right.value, b.bottom.value)
    }

    private fun Color.near(other: Color, tolerance: Float = 0.06f) =
        abs(red - other.red) < tolerance && abs(green - other.green) < tolerance && abs(blue - other.blue) < tolerance

    // --- Wo der Balken steht ---------------------------------------------------------------------------

    @Test
    fun onlyTheVoltageTileHasTheBar() {
        show(reading = SensorValue(PidRegistry.COOLANT_TEMP, 87f))
        gauge().assertDoesNotExist()
    }

    @Test
    fun theVoltageTileHasTheBar() {
        show()
        gauge().assertExists()
    }

    private fun checkGeometry(widthDp: Int, heightDp: Int, fontScale: Float) {
        show(trend = VoltageTrend(13.6f, ChargeState.Charging), widthDp = widthDp, heightDp = heightDp, fontScale = fontScale)
        val g = bounds(gauge())
        val label = bounds(composeRule.onNodeWithText(composeRule.activity.getString(R.string.pid_control_module_voltage).uppercase()))
        val value = bounds(composeRule.onNodeWithText(formatSensorNumber(13.6f, "V")))
        val tile = Rect(0f, 0f, widthDp.toFloat(), heightDp.toFloat())

        // Kachelrahmen: 4 dp Statusleiste, 8 dp Luft, 16 dp Balken; oben und unten der 12-dp-Innenrand.
        assertEquals("Balken beginnt 4 + 8 dp vom linken Rand", 12f, g.left, 0.6f)
        assertEquals("Balken ist 16 dp breit", 16f, g.width, 0.6f)
        assertEquals("oben 12 dp Luft", 12f, g.top, 0.6f)
        assertEquals("unten 12 dp Luft", heightDp - 12f, g.bottom, 0.6f)
        // Beschriftung und Wert liegen rechts vom Balken (12 dp Innenrand dahinter) und in der Kachel.
        assertTrue("Beschriftung $label ueberlappt den Balken $g", label.left >= g.right + 11f)
        assertTrue("Wert $value ueberlappt den Balken $g", value.left >= g.right)
        assertTrue("Beschriftung $label liegt nicht in der Kachel", label.right <= tile.right + 0.5f && label.top >= 0f)
        assertTrue("Wert $value liegt nicht in der Kachel", value.right <= tile.right + 0.5f && value.bottom <= tile.bottom + 0.5f)
    }

    @Test fun portraitTile_placesTheBarAndKeepsTheContentRightOfIt() = checkGeometry(224, 170, 1f)

    @Test fun landscapeTile_placesTheBarAndKeepsTheContentRightOfIt() = checkGeometry(250, 200, 1f)

    @Test fun aNarrowTile_placesTheBarAndKeepsTheContentRightOfIt() = checkGeometry(180, 170, 1f)

    @Test fun aLargeSystemFont_keepsTheContentRightOfTheBar() = checkGeometry(224, 170, 1.3f)

    // --- Die Fuellung, als Bildpunkte gelesen --------------------------------------------------------------

    private class Extent(val first: Int, val last: Int) { val bottomEdge get() = last + 1 }

    /** Die Zeilen der Mittelspalte des Balkens, die [color] tragen; null, wenn keine. */
    private fun extentOf(color: Color): Extent? {
        val map = gauge().captureToImage().toPixelMap()
        val x = map.width / 2
        val rows = (0 until map.height).filter { map[x, it].near(color) }
        return if (rows.isEmpty()) null else Extent(rows.first(), rows.last())
    }

    private fun gaugeHeightPx() = gauge().captureToImage().height

    private val lineHeightPx get() = with(composeRule.density) { 2.dp.toPx() }

    private fun checkFill(volts: Float, state: ChargeState, expected: Color, engineRunning: Boolean = true) {
        show(reading = volts(volts), trend = VoltageTrend(volts, state, engineRunning))
        val h = gaugeHeightPx()
        val span = voltageBarFill(volts)!!
        val extent = extentOf(expected)
        assertTrue("keine Fuellung in $expected bei $volts V", extent != null)
        extent!!
        val zeroY = h * (1f - VOLTAGE_BAR_ZERO_FRACTION)
        if (volts >= VOLTAGE_BAR_ZERO_V) {
            // Nach oben: die Oberkante bei bis, die Unterkante an der Nulllinie (deren Haelfte die Fuellung ueberdeckt).
            assertEquals("Oberkante bei $volts V", h * (1f - span.toFraction), extent.first.toFloat(), 2f)
            assertEquals("Unterkante bei $volts V", zeroY - lineHeightPx / 2f, extent.bottomEdge.toFloat(), 2.5f)
        } else {
            // Nach unten: die Oberkante an der Nulllinie, die Unterkante bei von.
            assertEquals("Oberkante bei $volts V", zeroY + lineHeightPx / 2f, extent.first.toFloat(), 2.5f)
            assertEquals("Unterkante bei $volts V", h * (1f - span.fromFraction), extent.bottomEdge.toFloat(), 2f)
        }
    }

    @Test fun charging_fillsUpwardsFromTheZeroLineInGreen() = checkFill(13.6f, ChargeState.Charging, StatusColors.TrafficLightGreen)

    @Test fun barelyCharging_fillsUpwardsInYellow() = checkFill(13.0f, ChargeState.Marginal, StatusColors.Warning)

    @Test fun discharging_fillsDownwardsFromTheZeroLineInRed() = checkFill(12.3f, ChargeState.Discharging, StatusColors.Critical)

    // Vor dem Start (Motor aus, verbunden) zeigt der Balken die Spannung ebenso: gleiche Lage, gleiche Farben (Nutzerwunsch 2026-10-19).
    @Test fun beforeTheStart_aLowVoltageFillsDownwardsInRed() = checkFill(11.9f, ChargeState.Discharging, StatusColors.Critical, engineRunning = false)

    @Test fun beforeTheStart_aRestedBatteryFillsUpwardsInYellow() = checkFill(12.9f, ChargeState.Marginal, StatusColors.Warning, engineRunning = false)

    @Test
    fun theFillIsCutOffAtTheTopOfTheScale() {
        show(reading = volts(17f), trend = VoltageTrend(17f, ChargeState.Charging))
        assertEquals("Oberkante an der Oberkante des Balkens", 0, extentOf(StatusColors.TrafficLightGreen)!!.first)
    }

    @Test
    fun theFillIsCutOffAtTheBottomOfTheScale() {
        show(reading = volts(9f), trend = VoltageTrend(9f, ChargeState.Discharging))
        assertEquals("Unterkante an der Unterkante des Balkens", gaugeHeightPx(), extentOf(StatusColors.Critical)!!.bottomEdge)
    }

    @Test
    fun theZeroLineSitsAt42Point5PercentAndIsDrawnOverTheFill() {
        show(trend = VoltageTrend(13.6f, ChargeState.Charging))
        val map = gauge().captureToImage().toPixelMap()
        val y = (map.height * (1f - VOLTAGE_BAR_ZERO_FRACTION)).toInt()

        for (x in listOf(1, map.width / 2, map.width - 2)) {
            assertTrue("Nulllinie bei ($x, $y): ${map[x, y]}", map[x, y].near(colors.onSurface))
        }
        // Ueber und unter der Linie (jeweils 4 Zeilen weiter) steht die Fuellung bzw. die Spur, nicht die Linie.
        assertTrue(map[map.width / 2, y - 6].near(StatusColors.TrafficLightGreen))
        assertTrue(map[map.width / 2, y + 6].near(colors.surfaceVariant))
    }

    // --- Ohne Zustand ----------------------------------------------------------------------------------------

    private fun fillPixels(): Int {
        val map = gauge().captureToImage().toPixelMap()
        val x = map.width / 2
        return (0 until map.height).count { y ->
            listOf(StatusColors.TrafficLightGreen, StatusColors.Warning, StatusColors.Critical).any { map[x, y].near(it) }
        }
    }

    @Test
    fun withoutATrendThereIsOnlyTheTrackAndTheZeroLine() {
        show(trend = null)

        assertEquals(0, fillPixels())
        val map = gauge().captureToImage().toPixelMap()
        assertTrue("Spur oben", map[map.width / 2, 3].near(colors.surfaceVariant))
        assertTrue("Spur unten", map[map.width / 2, map.height - 4].near(colors.surfaceVariant))
        assertTrue("Nulllinie", map[map.width / 2, (map.height * (1f - VOLTAGE_BAR_ZERO_FRACTION)).toInt()].near(colors.onSurface))
    }

    @Test
    fun withoutAConnectionTheTrendIsIgnoredAndTheZeroLineIsDimmed() {
        show(reading = volts(0f), trend = VoltageTrend(13.6f, ChargeState.Charging), isLive = false)

        assertEquals("keine Fuellung ohne Verbindung", 0, fillPixels())
        val map = gauge().captureToImage().toPixelMap()
        val line = map[map.width / 2, (map.height * (1f - VOLTAGE_BAR_ZERO_FRACTION)).toInt()]
        assertFalse("die Nulllinie ist gedaempft, nicht onSurface: $line", line.near(colors.onSurface, 0.03f))
    }

    // --- Beschreibung fuer Screenreader --------------------------------------------------------------------

    private fun descriptionOfTile(): String {
        val nodes = composeRule.onAllNodes(androidx.compose.ui.test.hasContentDescription(composeRule.activity.getString(R.string.pid_control_module_voltage), substring = true))
        return nodes.fetchSemanticsNodes().first().config[SemanticsProperties.ContentDescription].joinToString(" ")
    }

    private fun checkDescription(state: ChargeState, wordRes: Int) {
        show(trend = VoltageTrend(13.6f, state))
        val label = composeRule.activity.getString(R.string.pid_control_module_voltage)
        assertEquals("$label: ${formatSensorNumber(13.6f, "V")} V, ${composeRule.activity.getString(wordRes)}", descriptionOfTile())
    }

    @Test fun theDescriptionNamesCharging() = checkDescription(ChargeState.Charging, R.string.voltage_state_charging)

    @Test fun theDescriptionNamesBarelyCharging() = checkDescription(ChargeState.Marginal, R.string.voltage_state_marginal)

    @Test fun theDescriptionNamesDischarging() = checkDescription(ChargeState.Discharging, R.string.voltage_state_discharging)

    // Vor dem Start sagt die Beschreibung kein Zustandswort: "laedt" und "laedt kaum" waeren bei stehendem Motor falsch, die Zahl steht dort. (Ein Test je Zustand: setContent laeuft je Test nur einmal.)
    private fun checkNoWordBeforeTheStart(state: ChargeState) {
        show(reading = volts(13.0f), trend = VoltageTrend(13.0f, state, engineRunning = false))
        val label = composeRule.activity.getString(R.string.pid_control_module_voltage)
        assertEquals("$label: ${formatSensorNumber(13.0f, "V")} V", descriptionOfTile())
    }

    @Test fun beforeTheStartTheDescriptionNamesNoWord_charging() = checkNoWordBeforeTheStart(ChargeState.Charging)

    @Test fun beforeTheStartTheDescriptionNamesNoWord_barelyCharging() = checkNoWordBeforeTheStart(ChargeState.Marginal)

    @Test fun beforeTheStartTheDescriptionNamesNoWord_discharging() = checkNoWordBeforeTheStart(ChargeState.Discharging)

    @Test
    fun withoutAStateTheDescriptionIsTheOldOne() {
        show(trend = null)
        val label = composeRule.activity.getString(R.string.pid_control_module_voltage)
        assertEquals("$label: ${formatSensorNumber(13.6f, "V")} V", descriptionOfTile())
    }

    @Test
    fun withoutAConnectionTheDescriptionNamesNoState() {
        show(reading = volts(0f), trend = VoltageTrend(13.6f, ChargeState.Charging), isLive = false)
        val label = composeRule.activity.getString(R.string.pid_control_module_voltage)
        assertEquals("$label: -- V", descriptionOfTile())
    }
}
