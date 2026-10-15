package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.components.formatOilTimer
import de.adv990de.obd2.ui.theme.StatusColors
import io.mockk.every
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [RpmBar] — previously without any instrumentation coverage (documented gap,
 * confirmed via grep before writing these: zero test references to either
 * `R.string.dashboard_gear_desc` or `R.string.dashboard_gear_unknown_desc` anywhere in the repo).
 *
 * The four pure decision functions `RpmBar` delegates to (`rpmBlinkPeriodMs`, `rpmSegmentColor`,
 * `rpmThresholdTempScale`, `shouldStartRpmDemoSweep`) are already exhaustively covered as JVM tests
 * in `DashboardComponentsTest` (`app/src/test/java/de/adv990de/obd2/ui/dashboard/DashboardComponentsTest.kt`).
 * This class covers only what those cannot reach: the actual Composable output — the gear digit
 * (or the neutral "N", or the "–" placeholder) and its accompanying `contentDescription`, the [OilTemperatureBarStrip]
 * `contentDescription` for each `OilBarColor` state (that composable has no visible text of its own,
 * s. its KDoc) — plus a basic "composes without crashing" smoke check across the segment `Canvas`
 * and the blink `produceState` loop's below-/above-threshold branches.
 *
 * The oil-bar's actual on-screen *color* per state (as opposed to its `contentDescription`, asserted
 * below) was verified visually instead, via this project's established temporary-`captureToImage`
 * pattern (s. MEMORY.md) — not re-added here permanently, matching this class's existing precedent of
 * not asserting pixel-level `Canvas` output (s. paragraph below).
 *
 * Deliberately NOT covered (matching this project's established ceiling elsewhere, e.g.
 * `SlotPickerSheetTest`/`LiveValueTileDeviceTest`/`SectionLabelTest`, none of which assert pixel- or
 * frame-level state): the blink toggle's actual timing/frequency. `visible` in `RpmBar` is driven by
 * a `produceState` coroutine calling `delay(blinkPeriodMs)` in a `while(true)` loop — asserting *when*
 * it flips would need either idling-resource plumbing or a real-time wait, neither of which exists
 * anywhere in this codebase (`rpmBlinkPeriodMs` itself, the pure function computing that period, is
 * already exhaustively tested on the JVM side). The segment `Canvas`'s drawn pixels are likewise not
 * asserted — no `captureToImage`/pixel-level assertion exists anywhere in this project's test suite.
 *
 * Hilt/HiltTestActivity setup mirrored from [SlotPickerSheetTest] in this same package (and
 * `de.adv990de.obd2.ui.components.LiveValueTileDeviceTest`): `@HiltAndroidTest` is required for every test class
 * in this module (Hilt validates the full app graph, incl. `ObdForegroundService`'s entry point, per
 * test class) even though `RpmBar` itself needs no Hilt object — `ObdRepository` still needs a
 * `@BindValue` mock because its real constructor needs Bluetooth/connection dependencies unavailable
 * in tests.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RpmBarTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    /** The undetermined-gear placeholder digit box shows — an EN DASH (U+2013), not a hyphen. */
    private val undeterminedGearGlyph = "–"

    // --- QS C (UID-13/SCR-18): the segment bar was a bare Canvas — invisible to TalkBack ---

    @Test
    fun rpmBar_isDescribedWithTheRpmRoundedTo100() {
        composeRule.setContent { RpmBar(rpm = 2840f, threshold = 7000, gear = 3) }
        composeRule.waitForIdle()

        // 2840 -> 2800: TalkBack must not chatter at every 20-Hz sweep step.
        val expected = composeRule.activity.getString(R.string.dashboard_rpm_bar_desc, 2800)
        composeRule.onNodeWithContentDescription(expected).assertIsDisplayed()
    }

    @Test
    fun rpmBar_saysNoValue_whenNotLive_insteadOfADemoSweepRpm() {
        composeRule.setContent { RpmBar(rpm = 4500f, threshold = 7000, gear = null, isLive = false) }
        composeRule.waitForIdle()

        val expected = composeRule.activity.getString(R.string.dashboard_rpm_bar_unavailable_desc)
        composeRule.onNodeWithContentDescription(expected).assertIsDisplayed()
    }

    @Test
    fun determinedGear_showsDigit_andMatchingContentDescription() {
        composeRule.setContent {
            RpmBar(rpm = 4500f, threshold = 7000, gear = 3)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.dashboard_gear_desc, 3)
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun undeterminedGear_showsDash_andUnknownContentDescription() {
        composeRule.setContent {
            RpmBar(rpm = 4500f, threshold = 7000, gear = null)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.dashboard_gear_unknown_desc)
        composeRule.onNodeWithText(undeterminedGearGlyph).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun notLive_hidesGear_evenWhenADeterminedGearIsPassedIn() {
        // isLive=false forces displayGear=null regardless of `gear` (RpmBar: "keine Zahl, die eine
        // Drehzahl vortaeuscht, die es nicht gibt" applies to the neighbouring gear digit too, not
        // only to the segment bar's own alert colour/blink).
        composeRule.setContent {
            RpmBar(rpm = 0f, threshold = 7000, gear = 5, isLive = false)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.dashboard_gear_unknown_desc)
        composeRule.onNodeWithText(undeterminedGearGlyph).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun neutral_showsN_andNeutralContentDescription() {
        composeRule.setContent {
            RpmBar(rpm = 1100f, threshold = 7000, gear = null, isNeutral = true)
        }
        composeRule.waitForIdle()

        val symbol = composeRule.activity.getString(R.string.dashboard_gear_neutral_symbol)
        val expectedDescription = composeRule.activity.getString(R.string.dashboard_gear_neutral_desc)
        composeRule.onNodeWithText(symbol).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun neutral_winsOverAGearThatIsStillPassedIn() {
        composeRule.setContent {
            RpmBar(rpm = 1100f, threshold = 7000, gear = 2, isNeutral = true)
        }
        composeRule.waitForIdle()

        val symbol = composeRule.activity.getString(R.string.dashboard_gear_neutral_symbol)
        composeRule.onNodeWithText(symbol).assertIsDisplayed()
        composeRule.onNodeWithText("2").assertDoesNotExist()
    }

    @Test
    fun notLive_hidesNeutral_evenWhenNeutralIsPassedIn() {
        // Ohne Verbindung gibt es keinen Stillstand zu melden — dieselbe Regel wie fuer die Gangziffer.
        composeRule.setContent {
            RpmBar(rpm = 0f, threshold = 7000, gear = null, isNeutral = true, isLive = false)
        }
        composeRule.waitForIdle()

        val neutralDescription = composeRule.activity.getString(R.string.dashboard_gear_neutral_desc)
        val unknownDescription = composeRule.activity.getString(R.string.dashboard_gear_unknown_desc)
        composeRule.onNodeWithText(undeterminedGearGlyph).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(unknownDescription).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(neutralDescription).assertDoesNotExist()
    }

    @Test
    fun oilBarRed_showsColdContentDescription() {
        composeRule.setContent {
            RpmBar(rpm = 4500f, threshold = 7000, gear = 3, oilBarColor = OilBarColor.Red)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.oil_bar_red_desc)
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun oilBarYellow_showsWarmingUpContentDescription() {
        composeRule.setContent {
            RpmBar(rpm = 4500f, threshold = 7000, gear = 3, oilBarColor = OilBarColor.Yellow)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.oil_bar_yellow_desc)
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun oilBarGreen_showsOperatingTemperatureContentDescription() {
        composeRule.setContent {
            RpmBar(rpm = 4500f, threshold = 7000, gear = 3, oilBarColor = OilBarColor.Green)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.oil_bar_green_desc)
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun oilBarNullColor_showsUnknownContentDescription() {
        // Live, but no color yet — e.g. before the first coolant reading has arrived.
        composeRule.setContent {
            RpmBar(rpm = 4500f, threshold = 7000, gear = 3, oilBarColor = null)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.oil_bar_unknown_desc)
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun notLive_showsUnknownOilBarContentDescription_evenWhenAColorIsPassedIn() {
        // isLive=false forces the grey/unknown oil bar regardless of a stale oilBarColor — same
        // rationale as notLive_hidesGear_evenWhenADeterminedGearIsPassedIn for the gear digit.
        composeRule.setContent {
            RpmBar(rpm = 0f, threshold = 7000, gear = 5, oilBarColor = OilBarColor.Red, isLive = false)
        }
        composeRule.waitForIdle()

        val expectedDescription = composeRule.activity.getString(R.string.oil_bar_unknown_desc)
        composeRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun rendersWithoutCrashing_belowBlinkThreshold() {
        // rpm < threshold: rpmBlinkPeriodMs() == 0, the produceState loop takes its idle-poll branch.
        composeRule.setContent {
            RpmBar(rpm = 1500f, threshold = 7000, gear = 2)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun rendersWithoutCrashing_aboveBlinkThreshold() {
        // rpm > threshold with isLive=true: rpmBlinkPeriodMs() > 0, the produceState loop takes its
        // delay-and-toggle branch. Only that composition succeeds is asserted — see class KDoc for
        // why the toggle's timing itself is out of scope.
        composeRule.setContent {
            RpmBar(rpm = 8000f, threshold = 7000, gear = 6)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("6").assertIsDisplayed()
    }

    // ── Geometrie der Oel-Zeile (Nutzerwunsch 2026-10-15): Oelkanne vor dem Streifen, Streifen 16 dp wie der Tankbalken, Segmente 40 dp ──

    private val rpmBarRootTag = "rpm_bar_root"

    private fun showGeometryBar(oil: OilBarColor = OilBarColor.Green) {
        composeRule.setContent {
            RpmBar(
                rpm = 4500f, threshold = 7000, gear = 3, oilBarColor = oil,
                modifier = androidx.compose.ui.Modifier.testTag(rpmBarRootTag),
            )
        }
        composeRule.waitForIdle()
    }

    private val density get() = composeRule.activity.resources.displayMetrics.density

    private fun oilStrip() = composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.oil_bar_green_desc))
    private fun rpmSegments() = composeRule.onNodeWithContentDescription(
        composeRule.activity.getString(R.string.dashboard_rpm_bar_desc, rpmForSpeech(4500f))
    )

    @Test
    fun rpmBarRow_staysExactly78DpHigh() {
        showGeometryBar()

        val height = composeRule.onNodeWithTag(rpmBarRootTag).fetchSemanticsNode().size.height
        org.junit.Assert.assertEquals("Zeilenhoehe $height px", 78 * density, height.toFloat(), 1.5f)
    }

    @Test
    fun oilStrip_isAsHighAsTheFuelBar_16dp() {
        showGeometryBar()

        val height = oilStrip().fetchSemanticsNode().size.height
        org.junit.Assert.assertEquals("Streifenhoehe $height px", 16 * density, height.toFloat(), 1.5f)
    }

    @Test
    fun rpmSegments_are40dpHigh_8dpLessThanBefore() {
        showGeometryBar()

        val height = rpmSegments().fetchSemanticsNode().size.height
        org.junit.Assert.assertEquals("Segmenthoehe $height px", 40 * density, height.toFloat(), 1.5f)
    }

    @Test
    fun oilRow_adds_up_toTheColumn_segments_gap_row_is_62dp() {
        showGeometryBar()

        val segments = rpmSegments().fetchSemanticsNode().boundsInRoot
        val strip = oilStrip().fetchSemanticsNode().boundsInRoot
        // Nutzerwunsch 2026-10-15 („Abstand zwischen RPM-Bar und Oeltemperaturbalken etwas groesser, z. B. 2“): Streifen und Kanne stehen unten buendig in der
        // 20-dp-Zeile statt mittig — Luecke 2 dp + (20 dp Zeile - 16 dp Streifen) = 6 dp zwischen Segmenten und Streifen (vorher mittig: 2 + 2 = 4 dp);
        // der Streifen endet an der Unterkante der Spalte, die Segmente bleiben 40 dp hoch.
        org.junit.Assert.assertEquals("Abstand Segmente -> Streifen", 6 * density, strip.top - segments.bottom, 1.5f)
        org.junit.Assert.assertEquals(
            "Segmente + Luecke + Oel-Zeile = 40 + 2 + 20 dp, der Streifen schliesst die Spalte unten ab",
            62 * density, strip.bottom - segments.top, 1.5f,
        )
    }

    @Test
    fun theOilCan_standsDirectlyLeftOfTheStrip_andIsCentredOnIt() {
        showGeometryBar()

        val icon = composeRule.onNodeWithTag(OIL_BAR_ICON_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val strip = oilStrip().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals("Oelkanne 16 dp hoch", 16 * density, icon.height, 1.5f)
        org.junit.Assert.assertEquals("8 dp zwischen Kanne und Streifen", 8 * density, strip.left - icon.right, 1.5f)
        org.junit.Assert.assertEquals("Kanne mittig zum Streifen", strip.center.y, icon.center.y, 1.5f)
        org.junit.Assert.assertTrue("Kanne links vom Streifen", icon.right <= strip.left)
    }

    @Test
    fun theOilStrip_endsWhereTheSegmentsEnd_andStartsAfterTheCan() {
        showGeometryBar()

        val segments = rpmSegments().fetchSemanticsNode().boundsInRoot
        val strip = oilStrip().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals("rechte Kante gleich", segments.right, strip.right, 1.5f)
        org.junit.Assert.assertTrue("Streifen beginnt rechts der linken Kante der Segmente", strip.left > segments.left)
    }

    // ── Linker Rand (Nutzerwunsch 2026-10-15): Segmente buendig wie die Kachel, Oelkanne an der Kante des Tankstellen-Symbols ──

    @Test
    fun rpmSegments_startExactlyAtTheLeftScreenEdge() {
        showGeometryBar()

        val left = rpmSegments().fetchSemanticsNode().boundsInRoot.left
        org.junit.Assert.assertEquals("Segmente buendig am linken Rand: $left px", 0f, left, 1.5f)
    }

    @Test
    fun theOilCan_boxStarts11dpFromTheEdge_soItsVisibleEdgeSitsAt12dpLikeThePump() {
        showGeometryBar()

        // 8 dp Zeilenrand der Tankzeile + 4 dp Eigenrand des Material-Symbols = 12 dp sichtbar; die Kanne hat 1 dp Eigenrand -> 11 dp.
        // Dass die SICHTBAREN Kanten wirklich gleich liegen, misst DashboardLeftEdgeAlignmentDeviceTest gegen die echte Tankzeile.
        val left = composeRule.onNodeWithTag(OIL_BAR_ICON_TEST_TAG).fetchSemanticsNode().boundsInRoot.left
        org.junit.Assert.assertEquals("Kanne beginnt 11 dp vom Rand: $left px", 11 * density, left, 1.5f)
    }

    @Test
    fun theGearBox_keepsItsRightMargin_8dp() {
        showGeometryBar()

        val root = composeRule.onNodeWithTag(rpmBarRootTag).fetchSemanticsNode().boundsInRoot
        val gearBox = composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.dashboard_gear_desc, 3))
            .fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals("rechts bleiben 8 dp Rand", 8 * density, root.right - gearBox.right, 1.5f)
    }

    // ── Der Timer als fortlaufender Farbwechsel des Streifens (Nutzerwunsch 2026-10-16) ──
    // Solange der Timer wirklich laeuft, faerbt sich der gelbe Streifen anteilig von links nach rechts gruen (Anteil = 1 - Restzeit / 10 min).

    private val stripColor = androidx.compose.runtime.mutableStateOf<OilBarColor?>(OilBarColor.Yellow)
    private val stripGreenIn = androidx.compose.runtime.mutableLongStateOf(OIL_BAR_GREEN_HOLD_MS)
    private val stripLive = androidx.compose.runtime.mutableStateOf(true)

    /** Zeigt den Balken (nur einmal je Test, `setContent` geht nicht zweimal); danach mit [updateStrip] verstellen. */
    private fun showStrip(color: OilBarColor?, greenInMillis: Long, isLive: Boolean = true) {
        stripColor.value = color
        stripGreenIn.longValue = greenInMillis
        stripLive.value = isLive
        composeRule.setContent {
            RpmBar(
                rpm = 4500f, threshold = 7000, gear = 3,
                oilBarColor = stripColor.value, oilBarGreenInMillis = stripGreenIn.longValue, isLive = stripLive.value,
            )
        }
        composeRule.waitForIdle()
    }

    private fun updateStrip(color: OilBarColor?, greenInMillis: Long) {
        stripColor.value = color
        stripGreenIn.longValue = greenInMillis
        composeRule.waitForIdle()
    }

    private fun stripImage() = composeRule.onNodeWithTag(OIL_BAR_STRIP_TEST_TAG).captureToImage().asAndroidBitmap()

    /** Die Farben der Mittelzeile des Streifens an den Stellen [fractions] (0..1 der Breite), wie sie auf dem Bildschirm stehen. */
    private fun stripColoursAt(vararg fractions: Float): List<Int> {
        val image = stripImage()
        return fractions.map { image.getPixel((image.width * it).toInt().coerceIn(0, image.width - 1), image.height / 2) }
    }

    /** Wo (Bruchteil der Breite) in der Mittelzeile Gruen in Gelb uebergeht; `null`, wenn es keinen Uebergang gibt. */
    private fun greenToYellowEdge(): Float? {
        val image = stripImage()
        val y = image.height / 2
        val lastGreen = (0 until image.width).lastOrNull { image.getPixel(it, y) == green } ?: return null
        val firstYellow = (0 until image.width).firstOrNull { image.getPixel(it, y) == yellow } ?: return null
        return ((lastGreen + firstYellow) / 2f) / image.width
    }

    private val green get() = StatusColors.TrafficLightGreen.toArgb()
    private val yellow get() = StatusColors.Warning.toArgb()
    private val red get() = StatusColors.Critical.toArgb()

    @Test
    fun oilStrip_halfWayThroughTheWarmUp_isGreenOnTheLeftHalfAndYellowOnTheRightHalf() {
        showStrip(OilBarColor.Yellow, greenInMillis = OIL_BAR_GREEN_HOLD_MS / 2)

        org.junit.Assert.assertEquals(listOf(green, yellow), stripColoursAt(0.25f, 0.75f))
        val edge = greenToYellowEdge() ?: error("kein Uebergang von Gruen zu Gelb im Streifen")
        org.junit.Assert.assertEquals("der Uebergang liegt bei der Haelfte", 0.5f, edge, 0.02f)
    }

    @Test
    fun oilStrip_theGreenPartGrowsFromLeftToRightWithTheElapsedTime() {
        showStrip(OilBarColor.Yellow, greenInMillis = OIL_BAR_GREEN_HOLD_MS * 9 / 10) // 10 % verstrichen
        val early = greenToYellowEdge() ?: error("kein Uebergang bei 10 %")

        updateStrip(OilBarColor.Yellow, greenInMillis = OIL_BAR_GREEN_HOLD_MS / 10) // 90 % verstrichen
        val late = greenToYellowEdge() ?: error("kein Uebergang bei 90 %")

        org.junit.Assert.assertEquals("10 %", 0.1f, early, 0.02f)
        org.junit.Assert.assertEquals("90 %", 0.9f, late, 0.02f)
    }

    @Test
    fun oilStrip_whileTheTimerIsNotRunning_isPlainYellow() {
        showStrip(OilBarColor.Yellow, greenInMillis = OIL_BAR_GREEN_HOLD_MS) // volle Zeit = Timer laeuft nicht

        org.junit.Assert.assertEquals(listOf(yellow, yellow, yellow), stripColoursAt(0.02f, 0.5f, 0.98f))
        org.junit.Assert.assertNull("kein Gruen im Streifen", greenToYellowEdge())
    }

    @Test
    fun oilStrip_redGreenAndGrey_ignoreTheTimerValue() {
        showStrip(OilBarColor.Red, greenInMillis = OIL_BAR_GREEN_HOLD_MS / 2)
        org.junit.Assert.assertEquals("rot", listOf(red, red), stripColoursAt(0.1f, 0.9f))

        updateStrip(OilBarColor.Green, greenInMillis = 0L)
        org.junit.Assert.assertEquals("gruen", listOf(green, green), stripColoursAt(0.1f, 0.9f))

        updateStrip(OilBarColor.Green, greenInMillis = OIL_BAR_GREEN_HOLD_MS / 2)
        org.junit.Assert.assertEquals("gruen bleibt ganz gruen", listOf(green, green), stripColoursAt(0.1f, 0.9f))
    }

    @Test
    fun oilStrip_withoutAConnection_isGreyEvenWithARunningTimer() {
        showStrip(OilBarColor.Yellow, greenInMillis = OIL_BAR_GREEN_HOLD_MS / 2, isLive = false)

        val (left, right) = stripColoursAt(0.25f, 0.75f)
        org.junit.Assert.assertEquals("links wie rechts grau", left, right)
        org.junit.Assert.assertNotEquals(green, left)
        org.junit.Assert.assertNotEquals(yellow, left)
    }

    @Test
    fun oilStrip_descriptionNamesTheRemainingTimeWhileTheTimerRuns() {
        showStrip(OilBarColor.Yellow, greenInMillis = 372_000L) // 6:12

        val locale = composeRule.activity.resources.configuration.locales[0]
        composeRule.onNodeWithTag(OIL_BAR_STRIP_TEST_TAG).assertContentDescriptionEquals(
            composeRule.activity.getString(R.string.oil_bar_warmup_desc, formatOilTimer(372_000L, locale)),
        )
    }

    @Test
    fun oilStrip_descriptionIsThePlainYellowOneWhileTheTimerIsNotRunning() {
        showStrip(OilBarColor.Yellow, greenInMillis = OIL_BAR_GREEN_HOLD_MS)

        composeRule.onNodeWithTag(OIL_BAR_STRIP_TEST_TAG)
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.oil_bar_yellow_desc))
    }

    @Test
    fun theOilCan_isShownInEveryOilState_includingTheGreyOneWithoutConnection() {
        val color = androidx.compose.runtime.mutableStateOf<OilBarColor?>(OilBarColor.Red)
        composeRule.setContent { RpmBar(rpm = 0f, threshold = 7000, gear = null, oilBarColor = color.value, isLive = color.value != null) }
        for (state in listOf(OilBarColor.Red, OilBarColor.Yellow, OilBarColor.Green, null)) {
            color.value = state
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(OIL_BAR_ICON_TEST_TAG).assertIsDisplayed()
        }
    }
}
