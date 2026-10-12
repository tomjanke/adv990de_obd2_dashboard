package de.adv990de.obd2.ui.licenses

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QS C / SEC-10: Die Lizenzseite. Gelesen werden die Texte aus den ECHTEN Assets des APK — ein Test gegen `docs/licenses`
 * im Repository wuerde nicht merken, wenn Gradle sie nicht einpackt.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LicensesScreenTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private fun launch() {
        composeRule.setContent { LicensesScreen(rememberNavController()) }
        composeRule.waitForIdle()
    }

    @Test
    fun everyLicenceTextIsPackagedIntoTheApk() {
        // Die Assets der APP (nicht des Test-APK): targetContext.
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        for (kind in LicenseKind.values()) {
            val text = assets.open(kind.assetName).bufferedReader().use { it.readText() }
            assertTrue("${kind.assetName} ist leer oder fehlt im APK", text.length > 500)
        }
    }

    @Test
    fun theScreenListsEveryComponentWithItsLicence() {
        launch()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.licenses_intro)).assertIsDisplayed()
        for (component in LICENSED_COMPONENTS) {
            composeRule.onNodeWithTag(LICENSES_LIST_TEST_TAG).performScrollToNode(hasText(component.name))
            composeRule.onNodeWithText(component.name).assertIsDisplayed()
        }
    }

    @Test
    fun theFullTextsAreShown_loadedFromTheAssets() {
        launch()

        // Apache: einer der Saetze, die in JEDER Fassung des Textes stehen.
        composeRule.onNodeWithTag(LICENSES_LIST_TEST_TAG).performScrollToNode(hasText(LicenseKind.APACHE_2.label))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNode(hasText("Version 2.0, January 2004", substring = true)).assertExists() }.isSuccess
        }
        // MIT: der zweite Urheber des Originaltextes muss mitgeliefert sein.
        composeRule.onNodeWithTag(LICENSES_LIST_TEST_TAG).performScrollToNode(hasText(LicenseKind.MIT_SENTRY.label))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNode(hasText("Salomon BRYS", substring = true)).assertExists() }.isSuccess
        }
    }

    @Test
    fun theBackArrowReturns() {
        composeRule.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "first") {
                composable("first") { androidx.compose.material3.Text("first-destination") }
                composable("licenses") { LicensesScreen(nav) }
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { nav.navigate("licenses") }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.licenses_title)).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.nav_back_desc)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("first-destination").assertIsDisplayed()
    }
}
