package de.adv990de.obd2.ui.appinfo

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.runtime.remember
import androidx.compose.ui.test.onNodeWithText
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.settings.CrashReportingSettingsViewModel
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
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
import de.adv990de.obd2.ui.navigation.Screen
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Compose UI tests for [AppInfoScreen]'s CC-BY-4.0 attribution and license rows — Open-Meteo's
 * licence asks for credit WITH a link to the source AND a link to the licence text itself, so
 * both rows have to actually open their respective URL, not just show text naming them.
 *
 * The click tests intercept the browser start with a framework [Instrumentation.ActivityMonitor]
 * (no-arg constructor + [Instrumentation.ActivityMonitor.onStartActivity], both API 26 = this
 * app's minSdk): returning a canned result there makes `Instrumentation.execStartActivity` hand
 * that result back instead of starting anything. That is what lets these tests assert the fired
 * `ACTION_VIEW` intent without a new dependency (the project has neither espresso-intents nor
 * UiAutomator, s. `HistoryScreenTest`'s KDoc) and without a real browser popping up in front of
 * the instrumentation app on the test device, which would break every test after it.
 *
 * `@HiltAndroidTest` + the `@BindValue` [ObdRepository] mock are the same required boilerplate as
 * in `SectionLabelTest` (Hilt validates the whole app graph per test class).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppInfoScreenTest {

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

    private fun launchAppInfoScreen() {
        composeRule.setContent {
            AppInfoScreen(navController = rememberNavController())
        }
        composeRule.waitForIdle()
    }

    /** Shared body for both "is a clickable row" tests below. */
    private fun assertIsClickableRow(testTag: String, expectedTextRes: Int) {
        launchAppInfoScreen()

        // Der Inhalt scrollt (mit der Zeile "Absturzberichte" passt er im Querformat nicht mehr ganz auf den Schirm).
        composeRule.onNodeWithTag(testTag)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals(composeRule.activity.getString(expectedTextRes))
            .assertHasClickAction()
            .assertHeightIsAtLeast(64.dp)
    }

    /** Shared body for both "click opens X" tests below — see class KDoc for why the browser
     *  start is intercepted rather than actually launched. */
    private fun assertClickOpensUrl(testTag: String, expectedUrl: String) {
        // Written on the main thread (click handler), read on the test thread.
        val startedViewIntents = CopyOnWriteArrayList<Intent>()
        val browserInterceptor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_VIEW) {
                    startedViewIntents += intent
                    Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                } else {
                    null // anything else proceeds normally
                }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(browserInterceptor)
        try {
            launchAppInfoScreen()

            composeRule.onNodeWithTag(testTag).performScrollTo().performClick()
            composeRule.waitForIdle()

            assertEquals(1, startedViewIntents.size)
            assertEquals(Uri.parse(expectedUrl), startedViewIntents.single().data)
        } finally {
            instrumentation.removeMonitor(browserInterceptor)
        }
    }

    @Test
    fun weatherAttribution_isAClickableRow_withGloveFriendlyHeight() =
        assertIsClickableRow(WEATHER_ATTRIBUTION_TEST_TAG, R.string.app_info_weather_attribution)

    @Test
    fun weatherAttribution_click_opensOpenMeteoWebsite() =
        assertClickOpensUrl(WEATHER_ATTRIBUTION_TEST_TAG, OPEN_METEO_URL)

    @Test
    fun weatherLicense_isAClickableRow_withGloveFriendlyHeight() =
        assertIsClickableRow(WEATHER_LICENSE_TEST_TAG, R.string.app_info_weather_license)

    @Test
    fun weatherLicense_click_opensCcBy4LicenseText() =
        assertClickOpensUrl(WEATHER_LICENSE_TEST_TAG, CC_BY_4_0_URL)

    // ── QS B33/SEC-10: die App-Info sagt, ob Absturzberichte gesendet werden ──────────────────────────────────

    private fun launchWithCrashStatus(available: Boolean, enabled: Boolean) {
        val prefs: AppPreferences = mockk { every { crashReportsEnabled } returns flowOf(enabled) }
        composeRule.setContent {
            val vm = remember { CrashReportingSettingsViewModel(prefs, mockk { every { isAvailable } returns available }) }
            AppInfoScreen(navController = rememberNavController(), crashVm = vm)
        }
        composeRule.waitForIdle()
    }

    private fun assertCrashStatusText(res: Int) {
        val expected = composeRule.activity.getString(res)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithText(expected).assertIsDisplayed() }.isSuccess
        }
    }

    @Test
    fun crashReports_saysNothingIsSent_inABuildWithoutDsn() {
        launchWithCrashStatus(available = false, enabled = true)
        assertCrashStatusText(R.string.app_info_crash_reports_not_included)
    }

    @Test
    fun crashReports_saysItIsOn_inABuildWithDsnAndTheSwitchOn() {
        launchWithCrashStatus(available = true, enabled = true)
        assertCrashStatusText(R.string.app_info_crash_reports_on)
    }

    @Test
    fun crashReports_saysItIsOff_whenTheUserTurnedItOff() {
        launchWithCrashStatus(available = true, enabled = false)
        assertCrashStatusText(R.string.app_info_crash_reports_off)
    }

    // ── QS SEC-10: der Einstieg in die Lizenzseite ───────────────────────────────────────────────────────────

    @Test
    fun licencesRow_isAClickableRow_withGloveFriendlyHeight() {
        launchAppInfoScreen()

        composeRule.onNodeWithTag(LICENSES_ROW_TEST_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals(composeRule.activity.getString(R.string.app_info_licenses))
            .assertHasClickAction()
            .assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun licencesRow_opensTheLicencesPage_insideTheApp() {
        composeRule.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = Screen.AppInfo.route) {
                composable(Screen.AppInfo.route) { AppInfoScreen(navController = nav) }
                composable(Screen.Licenses.route) { Text("licences-destination") }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(LICENSES_ROW_TEST_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("licences-destination").assertIsDisplayed()
    }
}

