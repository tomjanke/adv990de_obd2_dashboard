package de.adv990de.obd2.ui.dashboard

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
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
import de.adv990de.obd2.terrain.CompassPoint
import de.adv990de.obd2.terrain.TerrainDisplay
import de.adv990de.obd2.ui.components.Sparkline
import de.adv990de.obd2.ui.theme.AppTypography
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
 * Die Gelaende-Kachel auf dem Geraet: dass jedes Feld da ist und in der Kachel bleibt (Hoch- und Querformat, grosse Systemschrift), dass die Nadel der Windrose in die
 * richtige Richtung zeigt (als Bildpunkte gelesen, nicht nur gerechnet), dass die Beschreibung fuer Screenreader stimmt und dass die Kachel wie jede andere per Long-Press
 * bedient wird. Die Zahlen und Texte selbst pruefen die JVM-Tests von `TerrainTileLogic`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TerrainTileDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private val full = TerrainDisplay(
        altitudeM = 1234f, gradePercent = 4f, headingDeg = 45f,
        gradeHistory = List(40) { i -> if (i % 2 == 0) 3f else 5f },
    )

    private fun show(
        terrain: TerrainDisplay,
        widthDp: Int = 224,
        heightDp: Int = 170,
        fontScale: Float = 1f,
        imperial: Boolean = false,
        onLongPress: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
                    Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                        TerrainTile(terrain = terrain, useImperial = imperial, modifier = Modifier.size(widthDp.dp, heightDp.dp), onLongPress = onLongPress)
                    }
                }
            }
        }
    }

    private fun bounds(tag: String): Rect {
        val b = composeRule.onNodeWithTag(tag).getBoundsInRoot()
        return Rect(b.left.value, b.top.value, b.right.value, b.bottom.value)
    }

    private fun assertInside(inner: Rect, outer: Rect, what: String) {
        val slack = 0.5f
        assertTrue("$what $inner liegt nicht in der Kachel $outer", inner.left >= outer.left - slack && inner.top >= outer.top - slack &&
            inner.right <= outer.right + slack && inner.bottom <= outer.bottom + slack)
    }

    private fun assertNoOverlap(a: Rect, b: Rect, what: String) {
        assertFalse("$what: $a und $b ueberlappen", a.overlaps(b.deflate(0.5f)))
    }

    private fun checkLayout(widthDp: Int, heightDp: Int, fontScale: Float) {
        show(full, widthDp, heightDp, fontScale)
        val tile = bounds(TERRAIN_TILE_TEST_TAG)
        val altitude = bounds(TERRAIN_ALTITUDE_TEST_TAG)
        val rose = bounds(TERRAIN_ROSE_TEST_TAG)
        val heading = bounds(TERRAIN_HEADING_TEST_TAG)
        val graph = bounds(TERRAIN_GRAPH_TEST_TAG)
        val grade = bounds(TERRAIN_GRADE_TEST_TAG)
        for ((name, rect) in listOf("Hoehe" to altitude, "Windrose" to rose, "Peilung" to heading, "Graph" to graph, "Steigung" to grade)) {
            assertInside(rect, tile, name)
        }
        // Ein zusammengedrueckter Text hat ein leeres Rechteck, das nie "ueberlappt": die Groessen muessen stimmen.
        assertTrue("Windrose ${rose.width} x ${rose.height} dp ist nicht quadratisch", abs(rose.width - rose.height) < 1f)
        assertTrue("Windrose ${rose.width} dp ist zu klein oder zu gross", rose.width in 66f..78f)
        assertTrue("Hoehe ist zusammengedrueckt: ${altitude.height} dp", altitude.height >= 20f)
        assertTrue("Peilung ist zusammengedrueckt: ${heading.height} dp", heading.height >= 12f)
        assertTrue("Steigung ist zusammengedrueckt: ${grade.height} dp", grade.height >= 14f)
        assertTrue("Graph ist zusammengedrueckt: ${graph.height} dp", graph.height >= 40f && graph.width >= 60f)
        assertNoOverlap(altitude, rose, "Hoehe und Windrose")
        assertNoOverlap(rose, graph, "Windrose und Graph")
        assertNoOverlap(heading, graph, "Peilung und Graph")
        assertNoOverlap(altitude, graph, "Hoehe und Graph")
        assertNoOverlap(graph, grade, "Graph und Steigung")
    }

    @Test fun portraitTile_keepsEveryFieldInsideAndApart() = checkLayout(widthDp = 224, heightDp = 170, fontScale = 1f)

    @Test fun landscapeTile_keepsEveryFieldInsideAndApart() = checkLayout(widthDp = 250, heightDp = 200, fontScale = 1f)

    @Test fun aNarrowTile_keepsEveryFieldInsideAndApart() = checkLayout(widthDp = 180, heightDp = 170, fontScale = 1f)

    @Test fun aLargeSystemFont_doesNotPushFieldsOutOrTogether() = checkLayout(widthDp = 224, heightDp = 170, fontScale = 1.3f)

    @Test
    fun theFieldsShowTheValues() {
        show(full)

        composeRule.onNodeWithTag(TERRAIN_ALTITUDE_TEST_TAG).assertTextEquals(formatAltitude(1234f, false).first)
        composeRule.onNodeWithTag(TERRAIN_GRADE_TEST_TAG).assertTextEquals(formatGrade(4f))
        val labels = CompassPoint.entries.associateWith { composeRule.activity.getString(compassLabelRes(it)) }
        composeRule.onNodeWithTag(TERRAIN_HEADING_TEST_TAG).assertTextEquals(headingText(45f) { labels.getValue(it) })
    }

    @Test
    fun imperialUnitsShowFeet() {
        show(full, imperial = true)

        composeRule.onNodeWithTag(TERRAIN_ALTITUDE_TEST_TAG).assertTextEquals(formatAltitude(1234f, true).first)
        composeRule.onNodeWithText(" ft").assertExists()
    }

    @Test
    fun withoutValuesEveryFieldShowsDashes() {
        show(TerrainDisplay(null, null, null, emptyList()))

        composeRule.onNodeWithTag(TERRAIN_ALTITUDE_TEST_TAG).assertTextEquals("--")
        composeRule.onNodeWithTag(TERRAIN_GRADE_TEST_TAG).assertTextEquals("--")
        composeRule.onNodeWithTag(TERRAIN_HEADING_TEST_TAG).assertTextEquals("--")
    }

    // --- Nadel der Windrose, als Bildpunkte gelesen ---

    /** Zaehlt die Bildpunkte in der Farbe der Nadel (`primary`) in den vier Vierteln der Rose, ohne den Streifen um die Mittelachse, in dem der Buchstabe N steht. */
    private class NeedlePixels(val top: Int, val bottom: Int, val left: Int, val right: Int)

    private fun needlePixels(headingDeg: Float): NeedlePixels {
        show(full.copy(headingDeg = headingDeg))
        val bitmap = composeRule.onNodeWithTag(TERRAIN_ROSE_TEST_TAG).captureToImage().toPixelMap()
        val primary = blueColorScheme.primary
        fun close(c: Color) = kotlin.math.abs(c.red - primary.red) < 0.12f && kotlin.math.abs(c.green - primary.green) < 0.12f && kotlin.math.abs(c.blue - primary.blue) < 0.12f
        val cx = bitmap.width / 2
        val cy = bitmap.height / 2
        // Nur das Innere der Rose: der Buchstabe N sitzt am oberen Rand des Rings, die Nadel ist kuerzer als der Abstand der Buchstaben zur Mitte.
        val reach = bitmap.width / 4
        var top = 0; var bottom = 0; var left = 0; var right = 0
        for (y in cy - reach..cy + reach) for (x in cx - reach..cx + reach) {
            if (!close(bitmap[x, y])) continue
            if (y < cy - 2) top++ else if (y > cy + 2) bottom++
            if (x < cx - 2) left++ else if (x > cx + 2) right++
        }
        return NeedlePixels(top, bottom, left, right)
    }

    @Test
    fun theNeedlePointsEastWhenTheHeadingIsEast() {
        val p = needlePixels(90f)
        assertTrue("rechts ${p.right} sollte deutlich mehr Nadel haben als links ${p.left}", p.right > p.left * 3 && p.right > 40)
    }

    @Test
    fun theNeedlePointsWestWhenTheHeadingIsWest() {
        val p = needlePixels(270f)
        assertTrue("links ${p.left} sollte deutlich mehr Nadel haben als rechts ${p.right}", p.left > p.right * 3 && p.left > 40)
    }

    @Test
    fun theNeedlePointsNorthWhenTheHeadingIsNorth() {
        val p = needlePixels(0f)
        assertTrue("oben ${p.top} sollte deutlich mehr Nadel haben als unten ${p.bottom}", p.top > p.bottom * 3 && p.top > 40)
    }

    @Test
    fun theNeedlePointsSouthWhenTheHeadingIsSouth() {
        val p = needlePixels(180f)
        assertTrue("unten ${p.bottom} sollte deutlich mehr Nadel haben als oben ${p.top}", p.bottom > p.top * 3 && p.bottom > 40)
    }

    // --- Vorlese-Beschreibung und Bedienung ---

    private fun contentDescription(): String =
        composeRule.onNodeWithTag(TERRAIN_TILE_TEST_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")

    @Test
    fun theDescriptionNamesAllThreeValues() {
        show(full)

        val d = contentDescription()
        val a = composeRule.activity
        assertTrue(d, d.contains(a.getString(R.string.terrain_desc_altitude_m, formatAltitude(1234f, false).first)))
        assertTrue(d, d.contains(a.getString(R.string.terrain_desc_grade_up, "4")))
        assertTrue(d, d.contains(a.getString(R.string.terrain_desc_heading, a.getString(R.string.terrain_compass_name_ne), "45")))
    }

    @Test
    fun aMissingValueIsLeftOutOfTheDescription() {
        show(TerrainDisplay(altitudeM = 100f, gradePercent = null, headingDeg = null, gradeHistory = emptyList()))

        val d = contentDescription()
        val a = composeRule.activity
        assertTrue(d, d.contains(a.getString(R.string.terrain_desc_altitude_m, "100")))
        assertFalse(d, d.contains(a.getString(R.string.terrain_desc_grade_flat)))
        assertFalse(d, d.contains(a.getString(R.string.terrain_compass_name_ne)))
    }

    @Test
    fun theTileNamesItsLongPressAction_andLongPressCallsIt() {
        var pressed = 0
        show(full, onLongPress = { pressed++ })

        val node = composeRule.onNodeWithTag(TERRAIN_TILE_TEST_TAG)
        assertEquals(
            composeRule.activity.getString(R.string.dashboard_slot_change_action),
            node.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label,
        )
        node.performTouchInput { longClick() }
        assertEquals(1, pressed)
    }

    @Test
    fun withoutValuesTheSlotSaysWhatIsMissing() {
        composeRule.setContent {
            MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
                TerrainSlot(terrain = null, useImperial = false, placeholderRes = R.string.dashboard_slot_terrain_gps_off, modifier = Modifier.size(224.dp, 170.dp))
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.dashboard_slot_terrain_gps_off)).assertExists()
    }

    // --- Linienstaerke: die Steigung ist so dick wie eine Sparkline ---

    /** Die Hoehe der Linie in Bildpunkten an der mittleren Spalte: die Punkte in der Farbe `primary` (ein vollstaendig gedeckter Strich, weder Saum noch Verlauf darunter). */
    private fun lineThicknessPx(tag: String): Int {
        val primary = blueColorScheme.primary
        val image = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
        val x = image.width / 2
        fun close(c: Color) = abs(c.red - primary.red) < 0.08f && abs(c.green - primary.green) < 0.08f && abs(c.blue - primary.blue) < 0.08f
        return (0 until image.height).count { y -> close(image[x, y]) }
    }

    @Test
    fun theGradeLineIsAsThickAsASparkline() {
        composeRule.setContent {
            MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
                Column {
                    GradeGraph(values = List(40) { 0f }, modifier = Modifier.size(200.dp, 48.dp).testTag("grade_line"))
                    Sparkline(points = List(40) { 5f }, modifier = Modifier.size(200.dp, 48.dp).testTag("spark_line"))
                }
            }
        }
        composeRule.waitForIdle()

        val grade = lineThicknessPx("grade_line")
        val spark = lineThicknessPx("spark_line")
        Log.i("TerrainTileDeviceTest", "Linienstaerke (Bildpunkte, Dichte %.2f): Steigung %d, Sparkline %d".format(composeRule.activity.resources.displayMetrics.density, grade, spark))

        assertTrue("die Sparkline-Linie wurde gemessen ($spark px)", spark >= 6)
        assertTrue("die Steigungslinie ist $grade px dick, die Sparkline $spark px", abs(grade - spark) <= 1)
    }
}
