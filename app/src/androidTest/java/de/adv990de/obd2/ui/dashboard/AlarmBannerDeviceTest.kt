package de.adv990de.obd2.ui.dashboard

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.alarm.Alarm
import de.adv990de.obd2.alarm.AlarmKind
import de.adv990de.obd2.alarm.AlarmSeverity
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.MIN_TOUCH_TARGET
import de.adv990de.obd2.ui.theme.PlexMonoFamily
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme
import de.adv990de.obd2.ui.theme.greenColorScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * Das Alarmband (`AlarmBanner`) auf dem Geraet, ohne das Dashboard: Text und Zahl, Quittieren, Trefferflaeche, „+N“, die Farben der Stufen und der Kontrast von Text zu Hintergrund —
 * **gelesen aus dem gerenderten Bild**, nicht aus dem Quelltext geschlossen — und die Bedienungshilfe (Live-Region, Beschreibung).
 *
 * Sprache und Zahlenschreibweise haengen am Test, nicht am Geraet: die Ressourcen kommen aus einem Konfigurationskontext, die Standard-Locale (aus der `formatSensorNumber` das Komma oder den
 * Punkt nimmt) wird gesetzt und danach zurueckgegeben — wie `AlarmMessagesDeviceTest`. Eine Komposition je Test (`setContent` geht nur einmal): Alarm, Farbschema, Schriftskala und Breite sind Zustand,
 * den ein Test zwischen zwei Pruefungen aendert.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AlarmBannerDeviceTest {

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

    private val coolant = Alarm(AlarmKind.COOLANT, AlarmSeverity.WARNING, value = 97.4f)
    private val voltage = Alarm(AlarmKind.VOLTAGE, AlarmSeverity.NOTICE, value = 12.34f)
    private val oneFault = Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301"))
    private val twoFaults = Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.NOTICE, codes = listOf("P0301", "P0420"))

    private val alarm = mutableStateOf(coolant)
    private val more = mutableIntStateOf(0)
    private val scheme = mutableStateOf<ColorScheme>(lightColorScheme())
    private val fontScale = mutableFloatStateOf(1f)
    private val widthDp = mutableFloatStateOf(0f) // 0 = die ganze Breite des Fensters
    private var acknowledged = 0
    private var locale: Locale = Locale.GERMANY
    private var titleLargeSp = 0f

    private fun resourcesFor(locale: Locale): Resources {
        val base = composeRule.activity
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) }).resources
    }

    private fun show(initial: Alarm = coolant, moreCount: Int = 0) {
        alarm.value = initial
        more.intValue = moreCount
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalResources provides resourcesFor(locale),
                LocalDensity provides Density(baseDensity.density, fontScale.floatValue),
            ) {
                AppTheme(colorScheme = scheme.value) {
                    titleLargeSp = MaterialTheme.typography.titleLarge.fontSize.value
                    val sized = if (widthDp.floatValue > 0f) Modifier.width(widthDp.floatValue.dp) else Modifier.fillMaxWidth()
                    Box(sized) {
                        AlarmBanner(alarm = alarm.value, moreCount = more.intValue, onAcknowledge = { acknowledged++ })
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun banner() = composeRule.onNodeWithTag(ALARM_BANNER_TEST_TAG)

    /** Der Text steht in einem Kind des Bandes, das Band fasst seine Kinder zu EINEM Knoten zusammen (`clickable`): das einzelne Feld gibt es nur im ungemergten Baum. */
    private fun messageNode(text: String) = composeRule.onNode(hasText(text), useUnmergedTree = true)

    private fun update(block: () -> Unit) {
        block()
        composeRule.waitForIdle()
    }

    // --- Text und Zahl ---

    @Test
    fun theVoltageAlarmSaysItWithOneDecimalAndAComma() {
        show(voltage)

        composeRule.onNodeWithText("Spannung niedrig: 12,3 V").assertIsDisplayed()
    }

    @Test
    fun theCoolantAlarmSaysItInWholeDegrees() {
        show(coolant)

        composeRule.onNodeWithText("Kühlmittel heiß: 97 °C").assertIsDisplayed()
    }

    @Test
    fun oneFaultCodeIsSingularAndTwoArePlural() {
        show(oneFault)
        composeRule.onNodeWithText("Neuer Fehlercode: P0301").assertIsDisplayed()

        update { alarm.value = twoFaults }

        composeRule.onNodeWithText("Neue Fehlercodes: P0301, P0420").assertIsDisplayed()
    }

    @Test
    fun theEnglishTextsFollowTheSameRules() {
        locale = Locale.US
        Locale.setDefault(Locale.US)
        show(voltage)
        composeRule.onNodeWithText("Low voltage: 12.3 V").assertIsDisplayed()

        update { alarm.value = oneFault }
        composeRule.onNodeWithText("New fault code: P0301").assertIsDisplayed()

        update { alarm.value = twoFaults }
        composeRule.onNodeWithText("New fault codes: P0301, P0420").assertIsDisplayed()
    }

    @Test
    fun theNumberIsSetInThePlexMonoNumberFont_andTheWordsAreNot() {
        show(voltage)

        val styled = messageNode("Spannung niedrig: 12,3 V").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val spans = styled.spanStyles

        assertEquals("genau eine Spanne: ${spans}", 1, spans.size)
        assertEquals("12,3", styled.text.substring(spans.single().start, spans.single().end))
        assertEquals(PlexMonoFamily, spans.single().item.fontFamily)
        assertEquals("tnum", spans.single().item.fontFeatureSettings)
    }

    @Test
    fun theFaultCodesAreTheNumberSpanToo() {
        show(twoFaults)

        val styled = messageNode("Neue Fehlercodes: P0301, P0420").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val span = styled.spanStyles.single()

        assertEquals("P0301, P0420", styled.text.substring(span.start, span.end))
    }

    // --- Quittieren und Trefferflaeche ---

    @Test
    fun oneTapAcknowledgesExactlyOnce() {
        show(coolant)

        banner().performClick()
        composeRule.waitForIdle()

        assertEquals(1, acknowledged)
    }

    @Test
    fun theTapIsAnnouncedAsAcknowledgeForScreenReaders() {
        show(coolant)

        val action = banner().fetchSemanticsNode().config[SemanticsActions.OnClick]

        assertEquals("Alarm quittieren", action.label)
    }

    @Test
    fun theBannerIsAtLeast72dpHigh_andTheTouchTargetToo_inEveryLayout() {
        show(coolant, moreCount = 1)

        for ((name, apply) in listOf<Pair<String, () -> Unit>>(
            "ganze Breite" to {},
            "schmales Fenster 360 dp" to { widthDp.floatValue = 360f },
            "Schriftskala 1,3" to { fontScale.floatValue = 1.3f },
            "ein Fehlercode" to { alarm.value = oneFault },
        )) {
            update(apply)
            val node = banner().fetchSemanticsNode()
            val density = Density(composeRule.activity)
            val min = ALARM_BANNER_MIN_HEIGHT.value - 0.5f // Rundung auf Pixel
            assertTrue("das Band ist hoeher als ein Touch-Ziel verlangt", ALARM_BANNER_MIN_HEIGHT >= MIN_TOUCH_TARGET)
            val heightDp = with(density) { node.boundsInRoot.height.toDp() }.value
            val touchHeightDp = with(density) { node.touchBoundsInRoot.height.toDp() }.value
            val touchWidthDp = with(density) { node.touchBoundsInRoot.width.toDp() }.value
            val widthDp = with(density) { node.boundsInRoot.width.toDp() }.value
            assertTrue("$name: Band $heightDp dp hoch", heightDp >= min)
            assertTrue("$name: Trefferflaeche $touchWidthDp x $touchHeightDp dp", touchHeightDp >= min && touchWidthDp >= min)
            assertTrue("$name: die Trefferflaeche ist das ganze Band ($touchWidthDp von $widthDp dp breit)", touchWidthDp >= widthDp - 0.5f)
        }
    }

    // --- „+N“ ---

    @Test
    fun theMoreCounterIsOnlyThereWhenOtherAlarmsWait() {
        show(coolant, moreCount = 0)
        composeRule.onAllNodesWithTag(ALARM_BANNER_MORE_TEST_TAG, useUnmergedTree = true).assertCountEquals(0)

        update { more.intValue = 1 }
        composeRule.onNodeWithTag(ALARM_BANNER_MORE_TEST_TAG, useUnmergedTree = true).assertTextEquals("+1")

        update { more.intValue = 3 }
        composeRule.onNodeWithTag(ALARM_BANNER_MORE_TEST_TAG, useUnmergedTree = true).assertTextEquals("+3")

        update { more.intValue = 0 }
        composeRule.onAllNodesWithTag(ALARM_BANNER_MORE_TEST_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    // --- Bedienungshilfe ---

    @Test
    fun theBannerIsAnAssertiveLiveRegion_soANewAlarmIsAnnounced() {
        show(coolant)

        assertEquals(LiveRegionMode.Assertive, banner().fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun theDescriptionNamesTheSeverityAndTheTextAndSaysATapAcknowledges() {
        show(coolant)
        assertEquals("Warnung: Kühlmittel heiß: 97 °C. Zum Quittieren tippen.", description())

        update { alarm.value = voltage }
        assertEquals("Hinweis: Spannung niedrig: 12,3 V. Zum Quittieren tippen.", description())

        update { alarm.value = twoFaults }
        assertEquals("Hinweis: Neue Fehlercodes: P0301, P0420. Zum Quittieren tippen.", description())
    }

    private fun description(): String =
        banner().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")

    // --- Groesse: das Band liegt ueber den Kacheln und muss auffallen ---

    @Test
    fun theMessageIsSetInTheLargeTitleStyle_notTheMediumOne() {
        show(coolant)

        val textNode = composeRule.onNode(hasText(":", substring = true), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        textNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val size = layouts.single().layoutInput.style.fontSize.value

        assertTrue("Schrift $size sp ist nicht die Groesse von titleLarge ($titleLargeSp sp)", size == titleLargeSp)
        assertTrue("titleLarge ($titleLargeSp sp) ist groesser als 24 sp", titleLargeSp > 24f)
    }

    // --- Text nicht abgeschnitten ---

    /**
     * Die Schrift ist `titleLarge` (26 sp, bei Schriftskala 1,3 rund 34 sp): in einem 360-dp-Fenster passen zwei neue Fehlercodes bei Schriftskala 1,3 nicht mehr in zwei Zeilen — dann folgt „…“ (die
     * Beschreibung für TalkBack nennt jeden Code). Das ist die **einzige** Kombination, die kürzt; jede andere zeigt den ganzen Text. Zwei Zeilen sind die Grenze: das Band überlagert die Kacheln, mehr Höhe
     * deckte mehr von ihnen ab.
     */
    @Test
    fun theTextIsNotClippedInANarrowWindowAndAtFontScale13_exceptTwoFaultCodesAtScale13_whichShortenWithAnEllipsis() {
        show(twoFaults, moreCount = 1)
        widthDp.floatValue = 360f

        for ((scale, expectedAlarm) in listOf(1f to twoFaults, 1.3f to twoFaults, 1f to voltage, 1.3f to voltage, 1.3f to coolant)) {
            update { fontScale.floatValue = scale; alarm.value = expectedAlarm }
            val bannerBounds = banner().fetchSemanticsNode().boundsInRoot
            val textNode = composeRule.onNode(hasText(":", substring = true), useUnmergedTree = true).fetchSemanticsNode()
            val text = textNode.boundsInRoot
            val label = "Schriftskala $scale, ${expectedAlarm.kind}"
            val mayEllipsize = scale == 1.3f && expectedAlarm === twoFaults

            assertTrue("$label: Textknoten ${text.width} x ${text.height} px", text.width > 0f && text.height > 0f)
            assertTrue("$label: der Text liegt im Band (Text $text, Band $bannerBounds)",
                text.left >= bannerBounds.left && text.right <= bannerBounds.right && text.top >= bannerBounds.top && text.bottom <= bannerBounds.bottom)
            val layouts = mutableListOf<TextLayoutResult>()
            textNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            if (!mayEllipsize) assertTrue("$label: der Text ist abgeschnitten (${layout.lineCount} Zeilen, Ueberlauf ${layout.hasVisualOverflow})", !layout.hasVisualOverflow)
            assertTrue("$label: hoechstens zwei Zeilen, waren ${layout.lineCount}", layout.lineCount <= 2)
        }
    }

    @Test
    fun aLongListOfFaultCodesStopsAtTwoLines_theDescriptionStillNamesAllOfThem() {
        val many = Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301", "P0302", "P0303", "P0304", "P0305", "P0306", "P0420"))
        show(many)
        widthDp.floatValue = 360f
        composeRule.waitForIdle()

        val textNode = composeRule.onNode(hasText(":", substring = true), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        textNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)

        assertEquals("zwei Zeilen, nicht mehr", 2, layouts.single().lineCount)
        assertTrue("TalkBack nennt jeden Code: ${description()}", listOf("P0301", "P0306", "P0420").all { description().contains(it) })
    }

    // --- Farben, aus dem gerenderten Bild gelesen ---

    private data class Rgb(val r: Int, val g: Int, val b: Int) {
        constructor(argb: Int) : this((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        fun toColor() = Color(r, g, b)
    }

    private fun bannerImage(): Bitmap = banner().captureToImage().asAndroidBitmap()

    /** Die Hintergrundfarbe des Bandes: ein Punkt im linken Rand (16 dp), wo weder Symbol noch Text stehen — mittig in der Hoehe. */
    private fun backgroundOf(image: Bitmap) = Rgb(image.getPixel(3, image.height / 2))

    /** Die Zeichenfarbe des Textes: der Punkt im Textfeld, dessen Leuchtdichte am weitesten vom Hintergrund liegt (ein vollstaendig gedeckter Punkt eines Strichs, nicht sein Saum). */
    private fun textInkOf(image: Bitmap, textNode: SemanticsNodeInteraction, bannerNode: SemanticsNodeInteraction, background: Rgb): Rgb {
        val box = textNode.fetchSemanticsNode().boundsInRoot
        val origin = bannerNode.fetchSemanticsNode().boundsInRoot
        val left = (box.left - origin.left).toInt().coerceIn(0, image.width - 1)
        val right = (box.right - origin.left).toInt().coerceIn(left + 1, image.width)
        val top = (box.top - origin.top).toInt().coerceIn(0, image.height - 1)
        val bottom = (box.bottom - origin.top).toInt().coerceIn(top + 1, image.height)
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
    fun theBackgroundIsTheStatusSurface_redForAWarningAndYellowForANotice() {
        show(coolant)

        for ((name, colors) in schemes) {
            update { scheme.value = colors; alarm.value = Alarm(AlarmKind.COOLANT, AlarmSeverity.WARNING, value = 105f) }
            val warning = backgroundOf(bannerImage())
            assertNear("$name, Warnung", StatusColors.container(StatusColors.Critical, colors.surface), warning)

            update { alarm.value = Alarm(AlarmKind.COOLANT, AlarmSeverity.NOTICE, value = 96f) }
            val notice = backgroundOf(bannerImage())
            assertNear("$name, Hinweis", StatusColors.container(StatusColors.Warning, colors.surface), notice)

            assertNotEquals("$name: Warnung und Hinweis haben dieselbe Hintergrundfarbe", warning, notice)
            assertTrue("$name: die Warnung ist rot getoent: $warning", warning.r > warning.g && warning.r > warning.b)
            assertTrue("$name: der Hinweis ist gelb getoent (Rot und Gruen ueber Blau): $notice", notice.r > notice.b && notice.g > notice.b)
        }
    }

    @Test
    fun theTextReaches45To1AgainstTheBackground_inEveryScheme_forEverySeverity() {
        show(coolant)

        val measured = mutableListOf<String>()
        for ((name, colors) in schemes) for (severity in AlarmSeverity.entries) {
            update { scheme.value = colors; alarm.value = Alarm(AlarmKind.VOLTAGE, severity, value = 12.1f) }
            val image = bannerImage()
            val background = backgroundOf(image)
            val ink = textInkOf(image, messageNode("Spannung niedrig: 12,1 V"), banner(), background)
            val ratio = contrast(ink.toColor(), background.toColor())
            measured += "$name/$severity ${"%.1f".format(Locale.ROOT, ratio)}:1"
            assertTrue("$name, $severity: Text $ink auf $background nur ${ratio}:1", ratio >= 4.5f)
            // Es ist die Schrift des Schemas, nicht ein Zufallspunkt: onSurface.
            assertNear("$name, $severity: Textfarbe", colors.onSurface, ink, tolerance = 6)
        }
        Log.i("AlarmBannerContrast", measured.joinToString("; "))
    }
}
