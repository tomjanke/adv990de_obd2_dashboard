package de.adv990de.obd2.ui.dashboard

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
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
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.MIN_TOUCH_TARGET
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme
import de.adv990de.obd2.ui.theme.greenColorScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Das laute Band fuer fehlende Fahrzeugwerte (`ValuesLostBanner`) auf dem Geraet, ohne das Dashboard: die Texte in beiden Sprachen, die Hoehe (mindestens 56 dp, waechst mit der Schrift, im
 * Querformat nicht hoeher als noetig), dass es nichts zum Antippen ist, die Bedienungshilfe und — **aus dem gerenderten Bild gelesen** — Hintergrund, Kontrast von Titel, Detailzeile und Symbol in
 * allen fuenf Farbschemata. Dieselbe Technik wie `AlarmBannerDeviceTest`; eine Komposition je Test (`setContent` geht nur einmal), Sprache und Standard-Locale haengen am Test.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ValuesLostBannerDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    // Pflichtbaustein jeder @HiltAndroidTest-Klasse hier (der Hilt-Graph wird je Klasse validiert).
    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    private var previousLocale: Locale = Locale.getDefault()

    @Before
    fun setup() {
        hiltRule.inject()
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreLocale() = Locale.setDefault(previousLocale)

    private val frozen = ValuesBanner(R.string.values_lost_frozen_title, detailRes = R.string.values_lost_frozen_detail)
    private val silent = ValuesBanner(R.string.values_lost_silent_title)
    private val reconnecting = ValuesBanner(R.string.values_lost_frozen_reconnecting, titleArgs = listOf(2, 5))

    private val banner = mutableStateOf(frozen)
    private val inline = mutableStateOf(false)
    private val scheme = mutableStateOf<ColorScheme>(lightColorScheme())
    private val fontScale = mutableFloatStateOf(1f)
    private val widthDp = mutableFloatStateOf(0f) // 0 = die ganze Breite des Fensters; sonst genau diese Breite, auch ueber das Fenster hinaus (requiredWidth: das Testgeraet steht im Hochformat, das Querformat ist breiter)
    private var locale: Locale = Locale.GERMANY
    private var titleLargeSp = 0f

    private fun resourcesFor(locale: Locale): Resources {
        val base = composeRule.activity
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) }).resources
    }

    private fun show(initial: ValuesBanner = frozen) {
        banner.value = initial
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalResources provides resourcesFor(locale),
                LocalDensity provides Density(baseDensity.density, fontScale.floatValue),
            ) {
                AppTheme(colorScheme = scheme.value) {
                    titleLargeSp = MaterialTheme.typography.titleLarge.fontSize.value
                    val sized = if (widthDp.floatValue > 0f) Modifier.requiredWidth(widthDp.floatValue.dp) else Modifier.fillMaxWidth()
                    Box(sized.testTag("values_lost_wrapper")) { ValuesLostBanner(banner = banner.value, inline = inline.value) }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun update(block: () -> Unit) {
        block()
        composeRule.waitForIdle()
    }

    private fun band() = composeRule.onNodeWithTag(VALUES_LOST_BANNER_TEST_TAG)

    /** Die Flaeche um das Band: ihre Hoehe ist die des ganzen Bandes. Der Knoten des Bandes selbst ist sein Inneres (ohne Rand), weil `semantics` hinter `padding` steht. */
    private fun wrapper() = composeRule.onNodeWithTag("values_lost_wrapper")

    private fun textNode(text: String) = composeRule.onNode(hasText(text), useUnmergedTree = true)

    private val frozenDetailDe = "Fahrzeugwerte werden nicht angezeigt und nicht aufgezeichnet"

    private fun heightDp(): Float = with(Density(composeRule.activity)) { wrapper().fetchSemanticsNode().boundsInRoot.height.toDp() }.value

    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    // --- Texte ---

    @Test
    fun theFrozenBandSaysFrozenAndWhatThatMeansForTheDisplay() {
        show(frozen)

        composeRule.onNodeWithText("Werte eingefroren").assertIsDisplayed()
        composeRule.onNodeWithText(frozenDetailDe).assertIsDisplayed()
    }

    @Test
    fun theSilentBandSaysNoCurrentValues_withoutADetailLineAndWithoutAClaimAboutTheCause() {
        show(silent)

        composeRule.onNodeWithText("Keine aktuellen Werte").assertIsDisplayed()
        composeRule.onAllNodesWithText(frozenDetailDe).assertCountEquals(0)
        assertEquals("kein 'Adapter' im Text (die Ursache ist nicht belegt)", false, description().contains("Adapter"))
    }

    @Test
    fun theReconnectBandNamesTheAttempt() {
        show(reconnecting)

        composeRule.onNodeWithText("Werte eingefroren · verbinde neu 2/5").assertIsDisplayed()
    }

    @Test
    fun theEnglishTextsFollowTheSameRules() {
        locale = Locale.US
        Locale.setDefault(Locale.US)
        show(frozen)
        composeRule.onNodeWithText("Values frozen").assertIsDisplayed()
        composeRule.onNodeWithText("Vehicle values are neither shown nor recorded").assertIsDisplayed()

        update { banner.value = silent }
        composeRule.onNodeWithText("No current values").assertIsDisplayed()

        update { banner.value = reconnecting }
        composeRule.onNodeWithText("Values frozen · reconnecting 2/5").assertIsDisplayed()
    }

    // --- Hoehe und Platz ---

    @Test
    fun theBannerIsAtLeast56dpHigh_inEveryLayoutAndGrowsWithTheSystemFont() {
        show(frozen)
        assertTrue("56 dp sind die Mindesthoehe des Entwurfs", VALUES_LOST_BANNER_MIN_HEIGHT == MIN_TOUCH_TARGET)

        val heights = mutableListOf<String>()
        for ((name, apply) in listOf<Pair<String, () -> Unit>>(
            "ganze Breite" to {},
            "schmales Fenster 360 dp" to { widthDp.floatValue = 360f },
            "Schriftskala 1,3" to { fontScale.floatValue = 1.3f },
            "stumm" to { banner.value = silent },
            "Neuverbinden" to { banner.value = reconnecting },
        )) {
            update(apply)
            val h = heightDp()
            heights += "$name ${"%.1f".format(Locale.ROOT, h)} dp"
            assertTrue("$name: Band $h dp hoch", h >= VALUES_LOST_BANNER_MIN_HEIGHT.value - 0.5f)
        }
        Log.i("ValuesLostBanner", "Hoehen: " + heights.joinToString("; "))

        update { fontScale.floatValue = 1f; widthDp.floatValue = 0f; banner.value = frozen }
        val normal = heightDp()
        update { fontScale.floatValue = 1.3f }
        assertTrue("mit grosser Systemschrift waechst das Band (statt abzuschneiden): $normal -> ${heightDp()}", heightDp() > normal)
    }

    @Test
    fun inAWideWindowTheInlineDetailKeepsTheBandAtTheMinimumHeight_theStackedOneIsTaller() {
        widthDp.floatValue = 900f   // das Querformat des Testgeraets ist rund 960 dp breit
        show(frozen)
        val stacked = heightDp()
        update { inline.value = true }
        val inlineHeight = heightDp()

        Log.i("ValuesLostBanner", "Querformat: untereinander ${"%.1f".format(Locale.ROOT, stacked)} dp, in einer Zeile ${"%.1f".format(Locale.ROOT, inlineHeight)} dp")
        assertTrue("die Zeile in einer Zeile ist das Minimum: $inlineHeight dp", inlineHeight <= VALUES_LOST_BANNER_MIN_HEIGHT.value + 0.5f)
        assertTrue("untereinander ist es hoeher ($stacked dp) — deshalb steht die Detailzeile im Querformat hinter dem Titel", stacked > inlineHeight + 4f)
        assertEquals("Titel und Detail stehen in EINEM Text auf einer Zeile", 1, layoutOf(composeRule.onNode(hasText("Werte eingefroren", substring = true), useUnmergedTree = true)).lineCount)
    }

    @Test
    fun theTextIsNotClippedInANarrowWindowAtFontScale13_atMostTwoLinesEach() {
        widthDp.floatValue = 360f
        fontScale.floatValue = 1.3f
        show(frozen)

        for (b in listOf(frozen, silent, reconnecting)) {
            update { banner.value = b }
            val bounds = wrapper().fetchSemanticsNode().boundsInRoot
            val title = composeRule.onNode(hasText("Werte", substring = true).or(hasText("Keine", substring = true)), useUnmergedTree = true)
            val text = title.fetchSemanticsNode().boundsInRoot
            assertTrue("der Text liegt im Band (Text $text, Band $bounds)", text.left >= bounds.left && text.right <= bounds.right && text.top >= bounds.top && text.bottom <= bounds.bottom)
            assertTrue("hoechstens zwei Zeilen", layoutOf(title).lineCount <= 2)
        }
    }

    // --- Verhalten und Bedienungshilfe ---

    @Test
    fun thereIsNothingToTap_noAcknowledge_theStateEndsByItself() {
        show(frozen)

        assertNull(band().fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick))
    }

    @Test
    fun theBandIsAnAssertiveLiveRegion_andTheDescriptionHasTheTitleAndTheDetail() {
        show(frozen)

        assertEquals(LiveRegionMode.Assertive, band().fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals("Werte eingefroren. $frozenDetailDe", description())

        update { banner.value = silent }
        assertEquals("Keine aktuellen Werte", description())
    }

    private fun description(): String = band().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")

    @Test
    fun theTitleIsSetInTheLargeTitleStyle_likeTheAlarmBandAndTheStatusRow() {
        show(frozen)

        val size = layoutOf(textNode("Werte eingefroren")).layoutInput.style.fontSize.value

        assertTrue("Schrift $size sp ist nicht die von titleLarge ($titleLargeSp sp)", size == titleLargeSp)
        assertTrue("titleLarge ($titleLargeSp sp) ist groesser als 24 sp", titleLargeSp > 24f)
    }

    // --- Farben, aus dem gerenderten Bild gelesen ---

    private data class Rgb(val r: Int, val g: Int, val b: Int) {
        constructor(argb: Int) : this((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        fun toColor() = Color(r, g, b)
    }

    private fun bandImage(): Bitmap = band().captureToImage().asAndroidBitmap()

    /** Der Hintergrund: ein Punkt in der linken oberen Ecke des Bildes (das Innere des Bandes), wo weder Symbol noch Text stehen. */
    private fun backgroundOf(image: Bitmap) = Rgb(image.getPixel(1, 1))

    private fun inkIn(image: Bitmap, node: SemanticsNodeInteraction, background: Rgb): Rgb {
        val box = node.fetchSemanticsNode().boundsInRoot
        val origin = band().fetchSemanticsNode().boundsInRoot
        val left = (box.left - origin.left).toInt().coerceIn(0, image.width - 1)
        val right = (box.right - origin.left).toInt().coerceIn(left + 1, image.width)
        val top = (box.top - origin.top).toInt().coerceIn(0, image.height - 1)
        val bottom = (box.bottom - origin.top).toInt().coerceIn(top + 1, image.height)
        return farthestFrom(image, left, right, top, bottom, background)
    }

    /** Der Punkt, dessen Leuchtdichte am weitesten vom Hintergrund liegt: ein vollstaendig gedeckter Punkt eines Strichs, nicht sein Saum. */
    private fun farthestFrom(image: Bitmap, left: Int, right: Int, top: Int, bottom: Int, background: Rgb): Rgb {
        val backgroundLuminance = background.toColor().luminance()
        var best = background
        var bestDistance = -1f
        for (y in top until bottom) for (x in left until right) {
            val rgb = Rgb(image.getPixel(x, y))
            val distance = abs(rgb.toColor().luminance() - backgroundLuminance)
            if (distance > bestDistance) { bestDistance = distance; best = rgb }
        }
        return best
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance(); val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private fun assertNear(label: String, expected: Color, actual: Rgb, tolerance: Int = 2) {
        val e = Rgb(expected.toArgb())
        assertTrue("$label: erwartet $e, gelesen $actual", abs(e.r - actual.r) <= tolerance && abs(e.g - actual.g) <= tolerance && abs(e.b - actual.b) <= tolerance)
    }

    private val schemes = listOf(
        "Standard hell" to lightColorScheme(),
        "Standard dunkel" to darkColorScheme(),
        "Cockpit blau" to blueColorScheme,
        "Cockpit amber" to amberColorScheme,
        "Cockpit gruen" to greenColorScheme,
    )

    @Test
    fun theBackgroundIsTheYellowStatusSurface_inEveryScheme() {
        show(frozen)

        for ((name, colors) in schemes) {
            update { scheme.value = colors }
            val background = backgroundOf(bandImage())
            assertNear("$name", StatusColors.container(StatusColors.Warning, colors.surface), background)
            assertTrue("$name: gelb getoent (Rot und Gruen ueber Blau): $background", background.r > background.b && background.g > background.b)
        }
    }

    @Test
    fun theTitleAndTheDetailReach45To1AgainstTheBackground_inEveryScheme() {
        show(frozen)

        val measured = mutableListOf<String>()
        for ((name, colors) in schemes) {
            update { scheme.value = colors }
            val image = bandImage()
            val background = backgroundOf(image)
            val titleInk = inkIn(image, textNode("Werte eingefroren"), background)
            val detailInk = inkIn(image, textNode(frozenDetailDe), background)
            val titleRatio = contrast(titleInk.toColor(), background.toColor())
            val detailRatio = contrast(detailInk.toColor(), background.toColor())
            measured += "$name Titel ${"%.1f".format(Locale.ROOT, titleRatio)}:1, Detail ${"%.1f".format(Locale.ROOT, detailRatio)}:1"
            assertTrue("$name: Titel $titleInk auf $background nur $titleRatio:1", titleRatio >= 4.5f)
            assertTrue("$name: Detail $detailInk auf $background nur $detailRatio:1", detailRatio >= 4.5f)
            assertNear("$name: Titelfarbe ist onSurface", colors.onSurface, titleInk, tolerance = 6)
        }
        Log.i("ValuesLostBannerContrast", measured.joinToString("; "))
    }

    @Test
    fun theWarningSymbolReaches3To1AgainstTheBand_inEveryScheme() {
        show(frozen)
        val density = Density(composeRule.activity)

        val measured = mutableListOf<String>()
        for ((name, colors) in schemes) {
            update { scheme.value = colors }
            val image = bandImage()
            val background = backgroundOf(image)
            // Der Knoten des Bandes ist sein Inneres (ohne den Rand von 16 dp, `semantics` steht hinter `padding`): das Symbol steht bei 0 bis 32 dp.
            val left = 0
            val right = with(density) { 32.dp.toPx() }.toInt().coerceIn(left + 1, image.width)
            val ink = farthestFrom(image, left, right, 0, image.height, background)
            val ratio = contrast(ink.toColor(), background.toColor())
            measured += "$name ${"%.2f".format(Locale.ROOT, ratio)}:1"
            assertTrue("$name: Symbol $ink auf $background nur $ratio:1 (Nicht-Text braucht 3:1)", ratio >= 3f)
            assertNear("$name: Symbolfarbe", StatusColors.warningOn(colors.surface), ink, tolerance = 6)
        }
        Log.i("ValuesLostBannerContrast", "Symbol: " + measured.joinToString("; "))
    }
}
