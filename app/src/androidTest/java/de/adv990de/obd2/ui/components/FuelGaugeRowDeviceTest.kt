package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.center
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.fuel.FuelGaugeState
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.blueColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die virtuelle Tankanzeige (Nutzerwunsch 2026-10-15) auf dem Geraet: Fuellung, Farbe, Blinken, Long-Press und Trefferflaeche.
 * Die Farben werden aus dem gerenderten Bild gelesen (nicht aus dem Quelltext geschlossen), das Blinken mit angehaltener Uhr Schritt fuer Schritt.
 * Der Balken besteht seit dem 2026-10-15 aus 10 Segmenten (Nutzerwunsch): wie viele leuchten und wie breit die Luecken sind, liest der Test als
 * Bildpunkte aus der Mittelzeile der Spur — Segmente und Luecken haben dort je eine eigene, genau bekannte Farbe.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class FuelGaugeRowDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /** Pflichtbaustein jeder @HiltAndroidTest-Klasse hier, s. [SectionLabelTest]. */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private var resets = 0
    private val current = mutableStateOf(FuelGaugeState(320, 0f))

    /** Das Farbschema der Komposition; die Bildpunkt-Helfer lesen Luecke und unbeleuchtetes Segment daraus. */
    private var scheme: ColorScheme = blueColorScheme

    private fun show(state: FuelGaugeState) {
        current.value = state
        composeRule.setContent {
            MaterialTheme(colorScheme = scheme, typography = AppTypography) {
                Box(Modifier.fillMaxWidth()) {
                    FuelGaugeRow(state = current.value, onResetRequested = { resets++ })
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Ein anderer Zustand in derselben Komposition (setContent geht nur einmal je Test). Bei angehaltener Uhr braucht die Komposition einen Frame. */
    private fun update(state: FuelGaugeState) {
        current.value = state
        if (!composeRule.mainClock.autoAdvance) composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    /**
     * Die Spur ist ein Kind der Zeile, und die Zeile fasst ihre Kinder zu EINEM Knoten zusammen (`combinedClickable`: ein Knoten fuer
     * TalkBack mit einer Beschreibung) — im zusammengefuehrten Baum gibt es sie nicht, nur im ungemergten.
     */
    private fun inner(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true)

    private fun trackImage() = inner(FUEL_GAUGE_TRACK_TEST_TAG).captureToImage().asAndroidBitmap()

    /** Die Farbe in der Mitte des ersten Segments, wie sie auf dem Bildschirm steht (beim Blinken abgeblendet). */
    private fun fillColorArgb(): Int {
        val image = trackImage()
        return image.getPixel(image.width / 20, image.height / 2)
    }

    /** Ein Segment der Spur, gelesen als Bildpunkte: leuchtend oder nicht und seine Breite in genau erkannten Punkten. */
    private data class Cell(val lit: Boolean, val widthPx: Int)

    /** Was die Mittelzeile der Spur zeigt: die Segmente von links nach rechts, die Breite der Luecken dazwischen und der erste/letzte Punkt mit Inhalt. */
    private data class Scan(val cells: List<Cell>, val gapsPx: List<Int>, val firstInkPx: Int, val lastInkPx: Int, val widthPx: Int) {
        val litCount: Int get() = cells.count { it.lit }
    }

    /**
     * Liest die Mittelzeile der Spur. Drei Farben sind genau bekannt: [litArgb] (leuchtendes Segment), `surfaceVariant` (unbeleuchtet) und `surface`
     * (die Luecke: dort scheint die Zeile durch). Punkte an den Kanten, die der Kantenglaettung gehoeren, passen zu keiner und zaehlen nicht mit —
     * deshalb sind die gemessenen Breiten bis zu 2 Punkte schmaler als die echten, die Luecken ebenso.
     */
    private fun scan(litArgb: Int): Scan {
        val image = trackImage()
        val gap = scheme.surface.toArgb()
        val unlit = scheme.surfaceVariant.toArgb()
        val y = image.height / 2
        val cells = mutableListOf<Cell>()
        val gaps = mutableListOf<Int>()
        var segmentLit: Boolean? = null
        var segmentWidth = 0
        var gapWidth = 0
        var first = -1
        var last = -1
        for (x in 0 until image.width) {
            val pixel = image.getPixel(x, y)
            when (pixel) {
                gap -> {
                    if (segmentLit != null) {
                        cells += Cell(segmentLit, segmentWidth)
                        segmentLit = null
                        segmentWidth = 0
                        gapWidth = 0
                    }
                    gapWidth++
                }
                litArgb, unlit -> {
                    val isLit = pixel == litArgb
                    if (segmentLit == null) {
                        if (cells.isNotEmpty()) gaps += gapWidth
                        segmentLit = isLit
                    }
                    segmentWidth++
                    if (first < 0) first = x
                    last = x
                }
                else -> Unit // Kantenglaettung
            }
        }
        if (segmentLit != null) cells += Cell(segmentLit, segmentWidth)
        return Scan(cells, gaps, first, last, image.width)
    }

    private fun lit(state: FuelGaugeState, litArgb: Int = StatusColors.TrafficLightGreen.toArgb()): Int {
        update(state)
        return scan(litArgb).litCount
    }

    @Test
    fun theRowDescribesTheRemainingRangeForScreenReaders() {
        show(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 100f)) // 220 km = 68,75 % -> 69 %

        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG)
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.fuel_gauge_desc, 220, 69))
    }

    @Test
    fun theLongPressIsAnnouncedAsResetForScreenReaders() {
        show(FuelGaugeState(320, 0f))

        val action = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().config[SemanticsActions.OnLongClick]

        assertEquals(composeRule.activity.getString(R.string.fuel_gauge_reset_action), action.label)
    }

    @Test
    fun theBarIsTenSegments_andAFullTankLightsAllOfThem() {
        show(FuelGaugeState(200, 0f))

        val scan = scan(StatusColors.TrafficLightGreen.toArgb())

        assertEquals("zehn Segmente: ${scan.cells}", 10, scan.cells.size)
        assertEquals("alle leuchten", 10, scan.litCount)
    }

    @Test
    fun anEmptyTankLightsNoSegment_butTheTenSegmentsStayVisibleAsTheScale() {
        show(FuelGaugeState(200, 400f))

        val scan = scan(StatusColors.TrafficLightGreen.toArgb())

        assertEquals("die Skala bleibt: ${scan.cells}", 10, scan.cells.size)
        assertEquals(0, scan.litCount)
    }

    @Test
    fun theLitSegmentsStandOnTheLeft_andTheRestIsTheUnlitScale() {
        show(FuelGaugeState(rangeFullKm = 200, kmSinceFill = 50f)) // 75 % -> 8 Segmente

        val scan = scan(StatusColors.TrafficLightGreen.toArgb())

        assertEquals(List(8) { true } + List(2) { false }, scan.cells.map { it.lit })
    }

    @Test
    fun theDisplayDropsBySegment_notContinuously() {
        // Die Anzeige sinkt nicht stufenlos: erst wenn ein Zehntel der Reichweite gefahren ist, geht das erste Segment aus; ein Segment bleibt
        // an, solange noch etwas von seinem Zehntel uebrig ist (aufgerundet, wie der Drehzahlbalken).
        show(FuelGaugeState(200, 0f))
        val green = StatusColors.TrafficLightGreen.toArgb()

        assertEquals("100 %", 10, lit(FuelGaugeState(200, 0f), green))
        assertEquals("99,9 % bleibt voll", 10, lit(FuelGaugeState(200, 0.2f), green))
        assertEquals("90,1 % bleibt voll", 10, lit(FuelGaugeState(200, 19.8f), green))
        assertEquals("90 % = 9 Segmente", 9, lit(FuelGaugeState(200, 20f), green))
        assertEquals("89,9 % bleibt bei 9", 9, lit(FuelGaugeState(200, 20.2f), green))
        assertEquals("50,1 % = 6 Segmente", 6, lit(FuelGaugeState(200, 99.8f), green))
        assertEquals("50 % = 5 Segmente", 5, lit(FuelGaugeState(200, 100f), green))
        assertEquals("41 % = 5 Segmente", 5, lit(FuelGaugeState(200, 118f), green))
    }

    @Test
    fun theLastSegmentStaysLitDownToAnEmptyTank() {
        // Das letzte Segment blinkt: die Uhr steht still, damit die Messung immer die "an"-Phase trifft.
        composeRule.mainClock.autoAdvance = false
        show(FuelGaugeState(200, 0f))
        val red = StatusColors.Critical.toArgb()

        assertEquals("5 %", 1, lit(FuelGaugeState(200, 190f), red))
        assertEquals("4,9 %", 1, lit(FuelGaugeState(200, 190.2f), red))
        assertEquals("0,1 %", 1, lit(FuelGaugeState(200, 199.8f), red))
        assertEquals("leer", 0, lit(FuelGaugeState(200, 200f), red))
    }

    /**
     * Wie weit ein Kantenpunkt von einer **reinen Mischung** aus Segmentfarbe und Luecke entfernt ist (groesste Abweichung eines Farbkanals).
     * Ein Segment, das aus zwei uebereinanderliegenden Rechtecken besteht (unbeleuchtet darunter, leuchtend darueber), hat an der geglaetteten Kante
     * einen Saum, der Farbe des unbeleuchteten Segments enthaelt (am Bildschirmfoto: Randpunkt (40, 164, 87) statt einer reinen Mischung aus Gruen
     * und Luecke). Ein einzelnes Rechteck mischt nur seine eigene Farbe mit dem Hintergrund — so zeichnet auch der Drehzahlbalken.
     * Die Breite der Luecke aendert der Saum kaum (gemessen 3,28 gegen 3,37 Punkte); es geht um saubere Kanten.
     */
    private fun worstEdgeResidual(litArgb: Int): Int {
        val image = trackImage()
        val gap = scheme.surface.toArgb()
        val unlit = scheme.surfaceVariant.toArgb()
        fun ch(argb: Int, shift: Int) = (argb shr shift) and 0xFF
        var worst = 0
        val y = image.height / 2
        for (x in 0 until image.width) {
            val p = image.getPixel(x, y)
            if (p == gap || p == unlit || p == litArgb) continue
            val residual = listOf(litArgb, unlit).minOf { ink ->
                val d = intArrayOf(16, 8, 0).map { ch(gap, it) - ch(ink, it) }
                val v = intArrayOf(16, 8, 0).map { ch(p, it) - ch(ink, it) }
                val t = ((0 until 3).sumOf { v[it] * d[it] }.toDouble() / (0 until 3).sumOf { d[it] * d[it] }).coerceIn(0.0, 1.0)
                (0 until 3).maxOf { kotlin.math.abs(v[it] - t * d[it]).toInt() }
            }
            worst = maxOf(worst, residual)
        }
        return worst
    }

    @Test
    fun theSegmentEdgesAreCleanBlends_likeInTheRpmBar() {
        // 59 % -> 6 leuchtende und 4 unbeleuchtete Segmente: Kanten zwischen leuchtend/Luecke und unbeleuchtet/Luecke.
        // Das Standard-Dunkelschema: dort liegen Luecke (#141218) und unbeleuchtetes Segment (#49454F) weit genug auseinander, dass ein Saum auffaellt
        // (im blauen Cockpit-Schema sind die beiden fast gleich und der Saum nicht messbar).
        scheme = darkColorScheme()
        show(FuelGaugeState(130, 53f))

        val worst = worstEdgeResidual(StatusColors.TrafficLightGreen.toArgb())

        assertTrue("ein Kantenpunkt weicht um $worst von einer reinen Mischung ab (Saum aus uebereinanderliegenden Segmenten?)", worst <= 4)
    }

    @Test
    fun theSegmentsAreSeparatedByTheSameGapAsTheRpmBar_andFillTheTrackEdgeToEdge() {
        // Nutzerwunsch 2026-10-15: der Abstand der Tank-Segmente entspricht dem der Drehzahl-Segmente (SEGMENT_GAP = 1,5 dp, eine Konstante).
        show(FuelGaugeState(200, 0f))
        val density = composeRule.activity.resources.displayMetrics.density
        val gap = 1.5f * density
        val scan = scan(StatusColors.TrafficLightGreen.toArgb())
        val segmentWidth = (scan.widthPx - 9 * gap) / 10

        assertEquals("neun Luecken: ${scan.gapsPx}", 9, scan.gapsPx.size)
        // Gemessen werden nur Punkte in genau der Farbe der Luecke; die Kantenglaettung nimmt bis zu zwei Punkte weg.
        scan.gapsPx.forEach {
            assertTrue("Luecke $it px, erwartet etwa $gap px (1,5 dp)", it >= gap - 2f && it <= gap)
        }
        scan.cells.forEach {
            assertTrue("Segmentbreite ${it.widthPx} px, erwartet etwa $segmentWidth px", it.widthPx >= segmentWidth - 2f && it.widthPx <= segmentWidth + 0.5f)
        }
        assertTrue("der Balken beginnt am linken Rand der Spur: ${scan.firstInkPx}", scan.firstInkPx <= 1)
        assertTrue("der Balken endet am rechten Rand der Spur: ${scan.lastInkPx} von ${scan.widthPx}", scan.lastInkPx >= scan.widthPx - 2)
    }

    // --- Farbe haengt an der Zahl leuchtender Segmente (Nutzerwunsch 2026-10-15): 5-10 gruen, 3-4 gelb, 2 rot, 1 rot blinkend ---

    @Test
    fun theBarIsGreenWithFiveOrMoreSegments() {
        show(FuelGaugeState(200, 100f)) // 50 % = 5 Segmente
        assertEquals(StatusColors.TrafficLightGreen.toArgb(), fillColorArgb())
    }

    @Test
    fun theBarIsYellowWithThreeOrFourSegments() {
        show(FuelGaugeState(200, 140f)) // 30 % = 3 Segmente
        assertEquals(StatusColors.Warning.toArgb(), fillColorArgb())
    }

    @Test
    fun theBarIsRedWithTwoSegments() {
        show(FuelGaugeState(200, 170f)) // 15 % = 2 Segmente
        assertEquals(StatusColors.Critical.toArgb(), fillColorArgb())
    }

    @Test
    fun theColourChangesInTheMomentASegmentGoesOut_andTheRemainingSegmentsTakeTheNewColour() {
        // Genau an den Grenzen aus dem gerenderten Bild gelesen: 41 % = 5 gruene, 40 % = 4 gelbe, 20,1 % = 3 gelbe, 20 % = 2 rote, 10,1 % = 2 rote.
        // Vorher (Prozentschwellen) blieben bei genau 40 % noch 4 gruene Segmente stehen und bei genau 20 % 2 gelbe.
        composeRule.mainClock.autoAdvance = false
        show(FuelGaugeState(200, 0f))
        val green = StatusColors.TrafficLightGreen.toArgb()
        val yellow = StatusColors.Warning.toArgb()
        val red = StatusColors.Critical.toArgb()

        fun check(driven: Float, litColor: Int, segments: Int, label: String) {
            update(FuelGaugeState(200, driven))
            val scan = scan(litColor)
            assertEquals("$label: $segments Segmente in der Farbe ${litColor.toUInt().toString(16)}", segments, scan.litCount)
        }
        check(118f, green, 5, "41 %")
        check(120f, yellow, 4, "genau 40 %")
        check(159.8f, yellow, 3, "20,1 %")
        check(160f, red, 2, "genau 20 %")
        check(179.8f, red, 2, "10,1 %")
        check(180f, red, 1, "genau 10 %: das letzte Segment blinkt rot")
    }

    @Test
    fun aRedBarWithTwoSegmentsDoesNotBlink() {
        composeRule.mainClock.autoAdvance = false
        show(FuelGaugeState(200, 170f)) // 15 % = 2 Segmente

        repeat(4) {
            composeRule.mainClock.advanceTimeBy(500)
            assertEquals("Takt ${it + 1}", StatusColors.Critical.toArgb(), fillColorArgb())
        }
    }

    @Test
    fun theLastSegmentBlinksRed() {
        composeRule.mainClock.autoAdvance = false
        show(FuelGaugeState(200, 180f)) // genau 10 % = 1 Segment
        val on = fillColorArgb()
        assertEquals("zuerst rot und voll sichtbar", StatusColors.Critical.toArgb(), on)

        composeRule.mainClock.advanceTimeBy(FUEL_BLINK_HALF_PERIOD_MS + 50)
        val off = fillColorArgb()
        assertNotEquals("nach einer halben Periode abgeblendet", on, off)

        composeRule.mainClock.advanceTimeBy(FUEL_BLINK_HALF_PERIOD_MS)
        assertEquals("nach einer Periode wieder rot", on, fillColorArgb())
    }

    @Test
    fun aLongPressOnTheRowAsksForAReset() {
        show(FuelGaugeState(320, 100f))

        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals(1, resets)
    }

    @Test
    fun aLongPressOnTheBarItselfAsksForAReset() {
        show(FuelGaugeState(320, 100f))

        inner(FUEL_GAUGE_TRACK_TEST_TAG).performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals(1, resets)
    }

    @Test
    fun aSimpleTapDoesNothing() {
        show(FuelGaugeState(320, 100f))

        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).performTouchInput { click() }
        composeRule.waitForIdle()

        assertEquals(0, resets)
    }

    @Test
    fun aPressThatEndsBeforeTheSystemLongPressTimeoutDoesNothing() {
        show(FuelGaugeState(320, 100f))

        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis / 2)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(0, resets)
    }

    @Test
    fun theRowIsExactly40DpHigh_withEqualSpaceAboveAndBelowTheBar() {
        // Dokumentierte Ausnahme von der 56-dp-Regel (Nutzerwunsch 2026-10-15: engerer Abstand): 40 dp Zeile, 16 dp Balken, 12 dp Luft je Seite.
        show(FuelGaugeState(320, 100f))
        val density = composeRule.activity.resources.displayMetrics.density

        val row = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val track = inner(FUEL_GAUGE_TRACK_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val above = track.top - row.top
        val below = row.bottom - track.bottom

        assertEquals("Zeilenhoehe ${row.height} px", 40 * density, row.height, 1.5f)
        assertEquals("Balkenhoehe ${track.height} px", 16 * density, track.height, 1.5f)
        assertEquals("Luft oben $above px, unten $below px", above, below, 1.5f)
        assertEquals("12 dp Luft", 12 * density, above, 1.5f)
    }

    @Test
    fun theBarEndsAt12DpFromTheRightEdge_andTheLeftMarginIsOnly8Dp() {
        // Nutzerwunsch 2026-10-15: das Tankstellen-Symbol sitzt naeher am linken Rand (8 dp Zeilenrand + 4 dp Eigenrand des Symbols = 12 dp sichtbar, vorher 16 + 4);
        // der Balken endet rechts ebenfalls 12 dp vor dem Rand (vorher 16 dp) — links und rechts dieselbe sichtbare Randbreite
        // (die sichtbare Kante des Symbols misst DashboardLeftEdgeAlignmentDeviceTest als Bildpunkt).
        show(FuelGaugeState(320, 100f))
        val density = composeRule.activity.resources.displayMetrics.density

        val row = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val track = inner(FUEL_GAUGE_TRACK_TEST_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("rechter Rand ${row.right - track.right} px", 12 * density, row.right - track.right, 1.5f)
        // Symbol (24 dp), 8 dp Abstand, die Zahl (64 dp, seit F5) und 12 dp Abstand vor dem Balken: er beginnt bei 8 + 24 + 8 + 64 + 12 = 116 dp.
        assertEquals("Balken beginnt ${track.left - row.left} px vom Zeilenanfang", 116 * density, track.left - row.left, 1.5f)
    }

    // --- F5: die Zahl und die Reserve-Marke (Entwurf 2026-10-25-range-design.md) ---

    private fun numberText() = inner(FUEL_GAUGE_RANGE_TEST_TAG)

    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        inner(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    @Test
    fun theRowShowsTheRemainingRangeAsANumber() {
        show(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 100f)) // 220 km

        numberText().assertTextEquals("220")
    }

    @Test
    fun theNumberIsRoundedDownToWholeKilometers() {
        show(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 132.6f)) // 187,4 km -> 187

        numberText().assertTextEquals("187")
    }

    @Test
    fun anEmptyTankShowsZero() {
        show(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 400f))

        numberText().assertTextEquals("0")
    }

    @Test
    fun theNumberFollowsTheGauge() {
        show(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 0f))
        numberText().assertTextEquals("320")

        update(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 100f))
        numberText().assertTextEquals("220")
    }

    @Test
    fun theNumberIsNormalTextInTheNormalState_andRedInTheReserve() {
        show(FuelGaugeState(rangeFullKm = 260, kmSinceFill = 100f, reserveKm = 40)) // 160 km, nicht in der Reserve
        assertEquals(scheme.onSurface, textLayout(FUEL_GAUGE_RANGE_TEST_TAG).layoutInput.style.color)

        update(FuelGaugeState(rangeFullKm = 260, kmSinceFill = 225f, reserveKm = 40)) // 35 km, in der Reserve
        assertEquals(StatusColors.criticalOn(scheme.surface), textLayout(FUEL_GAUGE_RANGE_TEST_TAG).layoutInput.style.color)
    }

    @Test
    fun withoutAReserveTheNumberIsNeverRed_notEvenOnAnEmptyTank() {
        show(FuelGaugeState(rangeFullKm = 260, kmSinceFill = 255f)) // 5 km, Reserve 0

        assertEquals(scheme.onSurface, textLayout(FUEL_GAUGE_RANGE_TEST_TAG).layoutInput.style.color)
    }

    @Test
    fun theNumberAndItsUnitFitTheirBox_evenWithFourDigitsAtLargeSystemFont() {
        // Nie ein still abgeschnittener Wert: 4 Ziffern bei Schriftskala 1,3 passen nicht in 64 dp, die Zahl schrumpft (LiveValueText minFontSize).
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                MaterialTheme(colorScheme = scheme, typography = AppTypography) {
                    Box(Modifier.width(360.dp)) {
                        FuelGaugeRow(state = FuelGaugeState(rangeFullKm = 1000, kmSinceFill = 0f), onResetRequested = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val number = textLayout(FUEL_GAUGE_RANGE_TEST_TAG)
        val readout = inner(FUEL_GAUGE_READOUT_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val numberBox = inner(FUEL_GAUGE_RANGE_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val unitBox = inner(FUEL_GAUGE_UNIT_TEST_TAG).fetchSemanticsNode().boundsInRoot

        assertEquals("eine Zeile", 1, number.lineCount)
        // Ein Text ohne Zeilenumbruch wird nicht umgebrochen, sondern an der Breite seines Platzes abgeschnitten — und meldet das nicht (`didOverflowWidth` bleibt false, `lineRight`
        // und `size` zeigen nur den sichtbaren Teil: am Geraet gemessen fuer "1000" ohne Verkleinern 2 von 4 Ziffern). Gemessen wird deshalb, was die Zahl BRAUCHT: Plex Mono hat fuer
        // jede Ziffer dieselbe Breite, das Kaestchen der ersten Ziffer mal die Zahl der Ziffern.
        val needed = number.getBoundingBox(0).width * number.layoutInput.text.length
        assertTrue("die Zahl braucht $needed px, ihr Knoten hat ${numberBox.width} px — abgeschnitten", needed <= numberBox.width + 0.5f)
        assertTrue("Zahl ${numberBox} und Einheit ${unitBox} ueberlappen", numberBox.right <= unitBox.left + 0.5f)
        assertTrue("die Einheit ragt aus der Box ($unitBox gegen $readout)", unitBox.right <= readout.right + 0.5f)
        assertEquals("die Box ist 64 dp breit", 64 * composeRule.activity.resources.displayMetrics.density, readout.width, 1.5f)
    }

    // Die Marke steht im Balken an der Stelle `reserve / Reichweite` von links: ein roter Strich (`StatusColors.Critical`), 4 dp breit wie der Streifen zwischen den Dashboard-Kacheln und
    // 16 dp hoch wie der Balken (Nutzerwunsch 2026-10-26, nach Vorabbild bestaetigt), im Bild als Punkte der Mittelzeile gelesen. Vorher: 2 dp breit, 24 dp hoch, `onSurface`.

    private data class Mark(val firstPx: Int, val lastPx: Int, val widthPx: Int) {
        val centerPx: Float get() = (firstPx + lastPx + 1) / 2f
    }

    /** Die Punkte der Mittelzeile der Spur in genau der Farbe der Marke, `StatusColors.Critical` (die Marke); `null` ohne solche Punkte. In diesen Tests leuchten die Segmente gruen — auf einem roten Segment gaebe es keinen Unterschied. */
    private fun markOnTrack(): Mark? {
        val image = trackImage()
        val ink = StatusColors.Critical.toArgb()
        val y = image.height / 2
        val xs = (0 until image.width).filter { image.getPixel(it, y) == ink }
        return if (xs.isEmpty()) null else Mark(xs.first(), xs.last(), xs.size)
    }

    @Test
    fun theReserveMarkSitsAtReserveDividedByRange() {
        show(FuelGaugeState(rangeFullKm = 260, kmSinceFill = 0f, reserveKm = 40))
        val image = trackImage()
        val expectedCenter = image.width * (40f / 260f)

        val mark = markOnTrack()

        assertTrue("die Marke fehlt", mark != null)
        assertEquals("Mitte der Marke ${mark!!.centerPx} px, erwartet $expectedCenter px", expectedCenter, mark.centerPx, 2f)
    }

    @Test
    fun theReserveMarkIsAbout4DpWide() {
        show(FuelGaugeState(rangeFullKm = 260, kmSinceFill = 0f, reserveKm = 40))
        val density = composeRule.activity.resources.displayMetrics.density

        val mark = markOnTrack()!!

        // Kantenglaettung nimmt bis zu zwei Punkte weg (nur Punkte in genau der Farbe zaehlen).
        assertTrue("Breite ${mark.widthPx} px, erwartet etwa ${4 * density} px", mark.widthPx >= 4 * density - 2f && mark.widthPx <= 4 * density + 0.5f)
    }

    @Test
    fun theReserveMarkMovesWithTheReserve() {
        show(FuelGaugeState(rangeFullKm = 200, kmSinceFill = 0f, reserveKm = 20))
        val low = markOnTrack()!!.centerPx

        update(FuelGaugeState(rangeFullKm = 200, kmSinceFill = 0f, reserveKm = 100))
        val high = markOnTrack()!!.centerPx

        assertTrue("20 km bei 200 km Reichweite stehen links von 100 km: $low gegen $high", low < high)
        assertEquals("100 von 200 km = Mitte", trackImage().width / 2f, high, 2f)
    }

    @Test
    fun withoutAReserveThereIsNoMark() {
        show(FuelGaugeState(rangeFullKm = 260, kmSinceFill = 0f))

        assertEquals(null, markOnTrack())
    }

    @Test
    fun aReserveAsLargeAsTheRangeHasNoMark() {
        show(FuelGaugeState(rangeFullKm = 100, kmSinceFill = 0f, reserveKm = 100))
        assertEquals(null, markOnTrack())

        update(FuelGaugeState(rangeFullKm = 100, kmSinceFill = 0f, reserveKm = 250))
        assertEquals(null, markOnTrack())
    }

    @Test
    fun theReserveMarkIsExactlyAsTallAsTheBar() {
        // 16 dp Strich, so hoch wie der Balken: er ragt weder oben noch unten ueber die Segmente (Nutzerwunsch 2026-10-26; vorher 24 dp, 4 dp ueber und unter dem Balken).
        show(FuelGaugeState(rangeFullKm = 260, kmSinceFill = 0f, reserveKm = 40))
        val density = composeRule.activity.resources.displayMetrics.density
        val row = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).captureToImage().asAndroidBitmap()
        val rowTop = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().boundsInRoot.top
        val track = inner(FUEL_GAUGE_TRACK_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val trackLeft = track.left - composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().boundsInRoot.left
        val x = (trackLeft + markOnTrack()!!.centerPx).toInt()
        val ink = StatusColors.Critical.toArgb()

        val ys = (0 until row.height).filter { row.getPixel(x, it) == ink }

        assertTrue("Hoehe ${ys.size} px, erwartet etwa ${16 * density} px", ys.size >= 16 * density - 2f && ys.size <= 16 * density + 0.5f)
        val trackTopInRow = (track.top - rowTop).toInt()
        val trackBottomInRow = (track.bottom - rowTop).toInt()
        assertTrue("der Strich (${ys.first()} bis ${ys.last()}) ragt ueber den Balken ($trackTopInRow bis $trackBottomInRow)", ys.first() >= trackTopInRow - 1 && ys.last() <= trackBottomInRow)
    }

    @Test
    fun theReserveMarkIsRed() {
        show(FuelGaugeState(rangeFullKm = 260, kmSinceFill = 0f, reserveKm = 40))
        val image = trackImage()
        val mark = markOnTrack()!!

        assertEquals("Farbe in der Mitte der Marke", StatusColors.Critical.toArgb(), image.getPixel((mark.centerPx).toInt(), image.height / 2))
        assertTrue("die Marke ist nicht die Farbe des Textes", StatusColors.Critical.toArgb() != scheme.onSurface.toArgb())
    }

    @Test
    fun theDescriptionNamesTheReserve_andSaysWhenTheRangeIsInIt() {
        show(FuelGaugeState(rangeFullKm = 260, kmSinceFill = 100f, reserveKm = 40)) // 160 km
        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG)
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.fuel_gauge_desc_reserve, 160, 62, 40))

        update(FuelGaugeState(rangeFullKm = 260, kmSinceFill = 225f, reserveKm = 40)) // 35 km, 13 %
        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG)
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.fuel_gauge_desc_in_reserve, 35, 13))
    }

    @Test
    fun theDescriptionSpeaksTheDisplayedNumber() {
        show(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 132.6f)) // 187,4 km, angezeigt 187, 58,6 % -> 59 %

        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG)
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.fuel_gauge_desc, 187, 59))
    }

    @Test
    fun theNumberIsNotAnnouncedTwice() {
        // Die Beschreibung der Zeile traegt schon die Restreichweite: die Zahl darf TalkBack nicht zusaetzlich ansagen.
        show(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 100f))

        val text = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        assertTrue("Text im zusammengefuehrten Knoten: $text", text.isNullOrEmpty())
    }

    // --- Eine gemeinsame Grundlinie fuer Symbol, Zahl und Einheit (Nutzerwunsch 2026-10-26) ---

    /** Die unterste Bildzeile, in der es im Bereich [xFrom] bis [xTo] Tinte gibt: Punkte, die sich deutlich vom Hintergrund unterscheiden (die Kantenglaettung zaehlt nicht). */
    private fun inkBottom(image: android.graphics.Bitmap, xFrom: Int, xTo: Int, background: Int): Int {
        fun ch(argb: Int, shift: Int) = (argb shr shift) and 0xFF
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in xFrom.coerceAtLeast(0) until xTo.coerceAtMost(image.width)) {
                val p = image.getPixel(x, y)
                if (intArrayOf(16, 8, 0).maxOf { kotlin.math.abs(ch(p, it) - ch(background, it)) } >= 100) { bottom = y; break }
            }
        }
        return bottom
    }

    private fun assertOneBaseline(scale: Float) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = scale)) {
                MaterialTheme(colorScheme = scheme, typography = AppTypography) {
                    Box(Modifier.fillMaxWidth()) {
                        FuelGaugeRow(state = FuelGaugeState(rangeFullKm = 320, kmSinceFill = 100f), onResetRequested = {}) // "220 km": Ziffern und Buchstaben ohne Unterlaengen
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.activity.resources.displayMetrics.density
        val rowBox = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val image = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).captureToImage().asAndroidBitmap()
        val background = scheme.surface.toArgb()
        val number = inner(FUEL_GAUGE_RANGE_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val unit = inner(FUEL_GAUGE_UNIT_TEST_TAG).fetchSemanticsNode().boundsInRoot

        // Das Symbol: 24 dp Kasten, 8 dp vom Zeilenrand (der Fuss der Zapfsaeule ist die unterste Tinte).
        val symbolBottom = inkBottom(image, (8 * density).toInt(), (32 * density).toInt(), background)
        val numberBottom = inkBottom(image, (number.left - rowBox.left).toInt(), (number.right - rowBox.left).toInt(), background)
        val unitBottom = inkBottom(image, (unit.left - rowBox.left).toInt(), (unit.right - rowBox.left).toInt(), background)

        assertTrue("Symbol $symbolBottom, Zahl $numberBottom, Einheit $unitBottom (Bildzeile der untersten Tinte)", symbolBottom > 0 && numberBottom > 0 && unitBottom > 0)
        val tolerance = 2 // Punkte: Ueberhang der runden Ziffern und Kantenglaettung (rund 0,9 dp)
        assertEquals("Zahl und Einheit stehen auf verschiedenen Grundlinien: $numberBottom gegen $unitBottom", numberBottom.toFloat(), unitBottom.toFloat(), tolerance.toFloat())
        assertEquals("Symbol und Zahl stehen auf verschiedenen Grundlinien: $symbolBottom gegen $numberBottom", symbolBottom.toFloat(), numberBottom.toFloat(), tolerance.toFloat())
    }

    @Test
    fun theSymbolTheNumberAndTheUnitShareOneBaseline() = assertOneBaseline(scale = 1f)

    @Test
    fun theSharedBaselineHoldsWithLargeSystemFont() = assertOneBaseline(scale = 1.3f)

    @Test
    fun theNumberIs25PercentLargerThanTitleMedium_andThreeDigitsKeepThatSize() {
        // Nutzerwunsch 2026-10-26 (Variante B): die Zahl ist groesser als vorher. Plex Mono hat je Ziffer 0,6 em Breite; drei Ziffern und "km" passen in die 64-dp-Box, ohne zu schrumpfen.
        show(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 100f)) // "220"
        val density = composeRule.activity.resources.displayMetrics.density

        val digitWidth = textLayout(FUEL_GAUGE_RANGE_TEST_TAG).getBoundingBox(0).width

        val expected = AppTypography.titleMedium.fontSize.value * 1.25f * 0.6f * density
        assertEquals("Ziffernbreite $digitWidth px, erwartet $expected px", expected, digitWidth, 1f)
    }
}
