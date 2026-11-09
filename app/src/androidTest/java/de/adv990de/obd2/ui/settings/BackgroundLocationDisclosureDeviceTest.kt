package de.adv990de.obd2.ui.settings

import android.content.pm.ActivityInfo
import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.assertTouchTargetAtLeast56
import de.adv990de.obd2.ui.theme.AppTypography
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die Erklaerung vor der Anfrage nach dem Hintergrund-Standort (Play-Richtlinie „Prominent Disclosure“, Backlog B): [BackgroundLocationDisclosureDialog].
 * Geprueft werden die Texte (aus den Ressourcen, also in jeder Sprache des Geraets), die zwei Antworten, dass Wegtippen und Zurueck nie als Zustimmung zaehlen,
 * die Touch-Ziele (56 dp, ein Dialog ist ein eigenes Fenster und erbt die Regel nicht) und dass der Text im Querformat und bei grosser Schrift erreichbar bleibt
 * (der Dialog ist laenger als die uebrigen: er nennt, was erfasst wird, wozu, wohin es nicht geht und dass es freiwillig ist).
 *
 * Dass `SettingsScreen` den Dialog zeigt und die System-Anfrage nur hinter „Weiter“ steht, kann dieser Test nicht pruefen (die Anfrage ist nicht bedienbar):
 * dafuer `BackgroundDisclosureWiringSourceTest`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BackgroundLocationDisclosureDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /** Required boilerplate of every @HiltAndroidTest class here, see [SectionLabelTest]. */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @After
    fun unlockOrientation() {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    private var continued = 0
    private var notNow = 0

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun launch(fontScale: Float? = null) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale ?: base.fontScale)) {
                MaterialTheme(typography = AppTypography) {
                    BackgroundLocationDisclosureDialog(onContinue = { continued++ }, onNotNow = { notNow++ })
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun requestOrientation(orientation: Int) {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
    }

    @Test
    fun showsTitleBodyAndBothAnswers() {
        launch()
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_body)).assertExists()
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_continue)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_not_now)).assertIsDisplayed()
    }

    @Test
    fun theBodyNamesWhatIsCollectedWhyAndThatItIsOptional() {
        // Die Pflichtangaben der Erklaerung stehen im Text — nicht nur „irgendein Text“. Jede Sprache nennt den Namen der Einstellung und den Satz „freiwillig“
        // sinngemaess; hier geprueft ueber die Ressourcen, die der Test der Sprachen (StringResourcesParityTest) auf Vollstaendigkeit prueft.
        val body = string(R.string.bg_location_disclosure_body)
        assertEquals("die Einstellung wird genannt", true, body.contains(string(R.string.settings_gps_speed)))
        assertEquals("der Text hat mehrere Absaetze (Zweck, Hintergrund, Verbleib, freiwillig)", 4, body.split("\n\n").size)
    }

    @Test
    fun continueIsTheOnlyConsent() {
        launch()
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_continue)).performClick()
        assertEquals(1, continued)
        assertEquals(0, notNow)
    }

    @Test
    fun notNowDoesNotConsent() {
        launch()
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_not_now)).performClick()
        assertEquals(0, continued)
        assertEquals(1, notNow)
    }

    @Test
    fun pressingBackIsNotNowAndNeverConsent() {
        launch()
        pressBack()
        assertEquals("Zurueck darf nie Zustimmung sein", 0, continued)
        assertEquals("Zurueck gilt als „Nicht jetzt“", 1, notNow)
    }

    @Test
    fun bothAnswersHaveA56dpTouchTarget() {
        launch()
        val density = Density(composeRule.activity)
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_continue)).assertTouchTargetAtLeast56(density)
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_not_now)).assertTouchTargetAtLeast56(density)
    }

    @Test
    fun inLandscapeTheWholeTextIsReachableAndBothAnswersStayVisible() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) // 448 dp hoch: der lange Text passt nicht ganz
        launch()
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_continue)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_not_now)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_body)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun atLargeFontScaleTheAnswersStayVisibleAndTheTextIsReachable() {
        launch(fontScale = 1.5f)
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_continue)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_not_now)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.bg_location_disclosure_body)).performScrollTo().assertIsDisplayed()
    }
}
