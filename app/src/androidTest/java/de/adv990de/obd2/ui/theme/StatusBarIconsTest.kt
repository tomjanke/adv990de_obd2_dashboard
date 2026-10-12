package de.adv990de.obd2.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die Symbole der Statusleiste folgen dem Farbschema der App, nicht dem Systemmodus (ab Android 15, wo die App hinter die
 * durchsichtige Leiste zeichnet). `isAppearanceLightStatusBars = true` heisst "helle Leiste, DUNKLE Symbole".
 *
 * Die API-Stufe schraenkt `@SdkSuppress` ein, nicht `assumeTrue`: AGP 9.4 schreibt eine verletzte Annahme als <failure> ins Geraete-
 * Testprotokoll (Gradle zaehlt sie trotzdem als bestanden); der Filter nimmt den Test VOR dem Lauf heraus (CodeHygieneSourceTest).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class StatusBarIconsTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    // Hilt validiert den App-Graphen je Testklasse (s. DeviceRowTest) — die Komponente hier nutzt ihn nicht.
    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private fun darkIconsNow(): Boolean {
        var value = false
        composeRule.runOnUiThread {
            val window = composeRule.activity.window
            value = WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
        }
        return value
    }

    /** Stellt das Gegenteil des erwarteten Werts ein, damit ein zufaellig passender Ausgangszustand nichts beweist. */
    private fun presetStatusBarIcons(darkIcons: Boolean) {
        composeRule.runOnUiThread {
            val window = composeRule.activity.window
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = darkIcons
        }
    }

    private fun showWith(scheme: ColorScheme) {
        composeRule.setContent { AppTheme(colorScheme = scheme) { Text("x") } }
        composeRule.waitForIdle()
    }

    @Test
    @SdkSuppress(minSdkVersion = EDGE_TO_EDGE_ENFORCED_SDK)
    fun aLightSchemeGetsDarkIcons() {
        presetStatusBarIcons(darkIcons = false)
        showWith(lightColorScheme())
        assertEquals(true, darkIconsNow())
    }

    @Test
    @SdkSuppress(minSdkVersion = EDGE_TO_EDGE_ENFORCED_SDK)
    fun aDarkSchemeGetsLightIcons() {
        presetStatusBarIcons(darkIcons = true)
        showWith(darkColorScheme())
        assertEquals(false, darkIconsNow())
    }

    @Test
    @SdkSuppress(minSdkVersion = EDGE_TO_EDGE_ENFORCED_SDK)
    fun aCockpitSchemeGetsLightIcons_whateverTheSystemMode() {
        presetStatusBarIcons(darkIcons = true)   // das, was ein helles System ohne Eingriff ergaebe
        showWith(amberColorScheme)
        assertEquals(false, darkIconsNow())
    }

    @Test
    @SdkSuppress(minSdkVersion = EDGE_TO_EDGE_ENFORCED_SDK)
    fun changingTheSchemeAtRuntimeChangesTheIcons() {
        var scheme by mutableStateOf(lightColorScheme())
        presetStatusBarIcons(darkIcons = false)
        composeRule.setContent { AppTheme(colorScheme = scheme) { Text("x") } }
        composeRule.waitForIdle()
        assertEquals(true, darkIconsNow())

        composeRule.runOnUiThread { scheme = blueColorScheme }
        composeRule.waitForIdle()
        assertEquals(false, darkIconsNow())
    }

    @Test
    @SdkSuppress(maxSdkVersion = EDGE_TO_EDGE_ENFORCED_SDK - 1)
    fun beforeAndroid15TheAppLeavesTheIconsToTheSystem() {
        presetStatusBarIcons(darkIcons = false)
        showWith(lightColorScheme())
        assertEquals("die Leiste hat dort eine eigene Farbe", false, darkIconsNow())
    }
}
