package de.adv990de.obd2.ui.components

import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.AppPreferences.Companion.THEME_SYSTEM
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.DriveStateViewModel
import de.adv990de.obd2.ui.ExitAppViewModel
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.settings.DisplaySettingsViewModel
import de.adv990de.obd2.ui.weather.WeatherViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [BottomNav] — previously untested (no JVM or androidTest file existed for
 * it anywhere in the repo before this class; documented coverage gap).
 *
 * Covers four pieces of real logic in [BottomNav]:
 * - Selected-item matching (`currentRoute == screen.route`), asserted via
 *   [androidx.compose.ui.test.assertIsSelected]/[androidx.compose.ui.test.assertIsNotSelected] —
 *   `NavigationBarItem`'s `selectable()` modifier merges its icon+label semantics upward onto one
 *   node (the same "clickable/selectable merges descendants" convention documented in
 *   [LiveValueTileDeviceTest]), so `onNodeWithText(label)` resolves to the item's own selectable node
 *   and carries its `selected` semantics property.
 * - Visibility gating via `vm.isNavHidden` (`AnimatedContent(targetState = isNavHidden)`, formerly
 *   `AnimatedVisibility` before [AmbientStatusRow] existed as the alternate content).
 * - The `onClick` navigation call (`navigate(route) { popUpTo/launchSingleTop/restoreState }`).
 * - The content swap itself and its `weatherVm`-driven temperature reading — see the fourth
 *   paragraph below.
 *
 * [DriveStateViewModel] is constructed directly with mocked [ObdRepository]/[AppPreferences]
 * collaborators — the same real-class-plus-mocked-deps pattern
 * [de.adv990de.obd2.ui.DriveStateViewModelTest] already uses on the JVM side — rather than resolved via
 * Hilt's `hiltViewModel()` default. This is preferable here to the `@BindValue ObdRepository` +
 * real-`AppPreferences`-via-`TestAppModule` pattern [de.adv990de.obd2.ui.dashboard.DashboardScreenTest]
 * uses for other screens: `AppPreferences.hideNavOnDrive` there is backed by a real on-device
 * DataStore with no existing precedent/plumbing to flip it deterministically per test before
 * composition, and that DataStore file is shared across this whole instrumented test run, so
 * asserting a specific `isNavHidden` value off of it risks bleed from whatever another test class
 * left behind. Constructing the ViewModel directly sidesteps both problems: each test supplies
 * fixed, non-changing `isMovingFlow`/`hideNavOnDrive` flows and gets a deterministic result.
 * `vm` is a plain [BottomNav] parameter specifically so this is possible — `hiltViewModel()` is
 * only its *default*, used when a caller omits the argument.
 *
 * The ViewModel is built inside the composable via `remember { }` (not earlier, in the test method
 * body) so its constructor — which eagerly launches `combine(...).stateIn(Eagerly)` on
 * `viewModelScope` (`Dispatchers.Main.immediate`) — runs already on the main thread.
 * `Dispatchers.Main.immediate` executes inline (no re-post) when already on its own thread, and
 * both source flows here (`MutableStateFlow`, `flowOf`) emit their single value synchronously with
 * no real suspension, so `isNavHidden` already holds its final combined value before the first
 * composition reads it — no `waitForIdle()` race to worry about.
 *
 * `@HiltAndroidTest` + `createAndroidComposeRule<HiltTestActivity>` + the `@BindValue`
 * `ObdRepository` mock are still required boilerplate per this project's established convention
 * ([LiveValueTileDeviceTest], [SectionLabelTest]): Hilt validates the whole app graph per test class
 * (reached through `ObdForegroundService`'s entry point) regardless of what the composable under
 * test itself resolves via Hilt — `BottomNav` here resolves nothing via Hilt at all once `vm` is
 * supplied explicitly.
 *
 * Navigation assertions need a real [androidx.navigation.NavController] with an attached graph —
 * `navController.navigate(route) { popUpTo(...) }` throws against a graph-less
 * `rememberNavController()` — so [launchBottomNav] wraps [BottomNav] in a minimal real `NavHost`
 * with one marker-`Text` destination per [Screen] item, mirroring
 * [de.adv990de.obd2.ui.dtc.DtcScreenTest]'s `dtcScreen_navigatesToFreezeFrame_whenLinkClicked` test.
 *
 * A fourth piece of logic, added alongside [AmbientStatusRow]: the `NavigationBar` / [AmbientStatusRow]
 * content swap (`AnimatedContent(targetState = isNavHidden)`), covered by
 * [navHidden_true_showsAmbientStatusRowWithTemperature] and
 * [navHidden_true_withNullTemperature_stillHidesNavItems] below. [BottomNav] resolves [WeatherViewModel]
 * the same way it resolves [DriveStateViewModel] — a parameter defaulting to `hiltViewModel()` — so
 * [launchBottomNav] mocks it directly with MockK instead of letting Hilt construct the real one. This
 * matters for more than test isolation: collecting the real [WeatherViewModel.outsideTempC] (a cold
 * `WhileSubscribed` flow) starts a 60-second polling loop on `viewModelScope` with real network
 * calls — the loop shape that already bit `WeatherViewModelTest` once (s. this feature's
 * task-4-report.md). `mockk<WeatherViewModel>(relaxed = true)` never invokes the real constructor at
 * all (proxy construction, same as this file's own existing `mockk<ObdRepository>`/
 * `mockk<AppPreferences>` — both concrete classes, not interfaces, proving this project's MockK setup
 * already mocks final Kotlin classes without running their real init logic), so no coroutine ever
 * starts and there is nothing to cancel in an `@After`. The mock also makes the gating itself
 * testable: [navHidden_false_neverTouchesTheWeatherFlow] verifies `outsideTempC` is never even read
 * while the nav bar is shown.
 *
 * [DisplaySettingsViewModel] (the imperial-units setting for the temperature) follows the
 * [DriveStateViewModel] pattern instead — real class, mocked [AppPreferences] flows, exactly like
 * `DisplaySettingsViewModelTest.makePrefs()` on the JVM side: its constructor starts nothing
 * dangerous, so there is no reason to mock the ViewModel itself. Its `useImperial` is a
 * `WhileSubscribed` StateFlow starting at `false`, so the imperial test waits for the real value
 * to arrive instead of relying on a single `waitForIdle()`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class BottomNavTest {

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

    // --- helpers ---

    private fun makeVm(isMoving: Boolean, hideNavOnDrive: Boolean): DriveStateViewModel =
        makeVm(MutableStateFlow(isMoving), hideNavOnDrive)

    /** Wie oben, aber der Test schaltet "faehrt" selbst um ([moving]) — fuer den Wechsel Hauptmenue <-> Statuszeile in einer Komposition. */
    private fun makeVm(moving: MutableStateFlow<Boolean>, hideNavOnDrive: Boolean): DriveStateViewModel {
        val obdRepo = mockk<ObdRepository> {
            every { isMovingFlow } returns moving
        }
        val prefs = mockk<AppPreferences> {
            every { this@mockk.hideNavOnDrive } returns flowOf(hideNavOnDrive)
        }
        return DriveStateViewModel(obdRepo, prefs)
    }

    /** Mirrors `DisplaySettingsViewModelTest.makePrefs()`: every flow the constructor touches is
     *  stubbed, the non-relaxed mock fails loudly on anything else. */
    private fun makeDisplayVm(useImperial: Boolean): DisplaySettingsViewModel {
        val prefs = mockk<AppPreferences> {
            every { this@mockk.useImperial } returns flowOf(useImperial)
            every { theme } returns flowOf(THEME_SYSTEM)
            every { useGpsSpeed } returns flowOf(false)
            every { backgroundLocationPrompted } returns flowOf(false)
            every { weatherLocationPrompted } returns flowOf(false)
            every { rpmBlinkThreshold } returns flowOf(0)
            every { rpmDynamicThreshold } returns flowOf(false)
        }
        return DisplaySettingsViewModel(prefs)
    }

    /** See class KDoc: mocked, never the real Hilt-constructed [WeatherViewModel]. */
    private fun makeWeatherVm(outsideTempC: Float?): WeatherViewModel =
        mockk(relaxed = true) {
            every { this@mockk.outsideTempC } returns MutableStateFlow(outsideTempC)
        }

    /** Real [ExitAppViewModel], mocked [ObdRepository]/[Context] — same pattern as [makeVm]:
     *  its constructor does nothing dangerous, so there is no reason to mock the class itself. */
    private fun makeExitAppVm(obdRepo: ObdRepository = mockk(relaxed = true)): ExitAppViewModel =
        ExitAppViewModel(
            de.adv990de.obd2.ObdServiceController(
                obdRepo, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            )
        )

    private lateinit var navController: NavHostController
    private lateinit var weatherVm: WeatherViewModel
    private lateinit var exitAppObdRepo: ObdRepository

    private fun launchBottomNav(
        isMoving: Boolean,
        hideNavOnDrive: Boolean,
        startDestination: String = Screen.Dashboard.route,
        outsideTempC: Float? = 20f,
        useImperial: Boolean = false,
        restoration: StateRestorationTester? = null,
        hostIsNoActivity: Boolean = false,
    ) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            val vm = remember { makeVm(isMoving = isMoving, hideNavOnDrive = hideNavOnDrive) }
            weatherVm = remember { makeWeatherVm(outsideTempC = outsideTempC) }
            val displayVm = remember { makeDisplayVm(useImperial = useImperial) }
            exitAppObdRepo = remember { mockk(relaxed = true) }
            val exitAppVm = remember { makeExitAppVm(obdRepo = exitAppObdRepo) }
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = startDestination) {
                composable(Screen.Dashboard.route) { Text(DASHBOARD_MARKER) }
                composable(Screen.TripsAndFaults.route) { Text(TRIPS_MARKER) }
                composable(Screen.Settings.route) { Text(SETTINGS_MARKER) }
                composable(Screen.AppLogs.route) { Text(APP_LOGS_MARKER) }
                composable(Screen.FreezeFrame.route) { Text(FREEZE_FRAME_MARKER) }
            }
            BottomNav(
                navController = navController,
                vm = vm,
                weatherVm = weatherVm,
                displayVm = displayVm,
                exitAppVm = exitAppVm,
            )
        }
        // Bestaetigt der Test das Beenden, darf `finishAffinity()` nicht die Activity der Test-Regel treffen: mit einem Kontext, der KEINE Activity ist
        // (`context as? Activity` ist dann null), laeuft alles bis dorthin (Dialog zu, Sitzung beenden) und die Test-Activity bleibt stehen.
        val hosted: @androidx.compose.runtime.Composable () -> Unit = if (hostIsNoActivity) {
            { CompositionLocalProvider(LocalContext provides ContextWrapper(composeRule.activity.applicationContext)) { content() } }
        } else content
        if (restoration != null) restoration.setContent(hosted) else composeRule.setContent(hosted)
        composeRule.waitForIdle()
    }

    private fun label(resId: Int): String = composeRule.activity.getString(resId)

    companion object {
        private const val BOTTOM_AREA_TAG = "BOTTOM_AREA"
        private const val DASHBOARD_MARKER = "DASHBOARD_DESTINATION_MARKER"
        private const val TRIPS_MARKER = "TRIPS_DESTINATION_MARKER"
        private const val SETTINGS_MARKER = "SETTINGS_DESTINATION_MARKER"
        private const val APP_LOGS_MARKER = "APP_LOGS_DESTINATION_MARKER"
        private const val FREEZE_FRAME_MARKER = "FREEZE_FRAME_DESTINATION_MARKER"
    }

    // --- tests ---

    @Test
    fun itemMatchingCurrentRoute_isSelected() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false, startDestination = Screen.TripsAndFaults.route)

        composeRule.onNodeWithText(label(R.string.nav_trips)).assertIsSelected()
    }

    @Test
    fun itemNotMatchingCurrentRoute_isNotSelected() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false, startDestination = Screen.TripsAndFaults.route)

        composeRule.onNodeWithText(label(R.string.nav_live)).assertIsNotSelected()
    }

    @Test
    fun onASettingsSubPage_theSettingsItemStaysSelected() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false, startDestination = Screen.AppLogs.route)

        composeRule.onNodeWithText(label(R.string.settings_title)).assertIsSelected()
        composeRule.onNodeWithText(label(R.string.nav_trips)).assertIsNotSelected()
        composeRule.onNodeWithText(label(R.string.nav_live)).assertIsNotSelected()
    }

    @Test
    fun onTheFreezeFramePage_theTripsItemStaysSelected() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false, startDestination = Screen.FreezeFrame.route)

        composeRule.onNodeWithText(label(R.string.nav_trips)).assertIsSelected()
        composeRule.onNodeWithText(label(R.string.settings_title)).assertIsNotSelected()
    }

    @Test
    fun navHidden_true_bottomNavIsNotDisplayed() {
        launchBottomNav(isMoving = true, hideNavOnDrive = true)

        composeRule.onNodeWithText(label(R.string.nav_live)).assertDoesNotExist()
    }

    @Test
    fun navHidden_false_bottomNavIsDisplayed() {
        // Preference on but vehicle stationary -> combine(moving, pref) still yields false.
        launchBottomNav(isMoving = false, hideNavOnDrive = true)

        composeRule.onNodeWithText(label(R.string.nav_live)).assertIsDisplayed()
    }

    @Test
    fun reTappingTheSettingsItem_onASubPage_returnsToTheSettingsStartPage() {
        // Gefunden am Pixel 8 Pro (2026-10-12): auf der App-Info tat der Tipp auf den hervorgehobenen Reiter nichts,
        // weil restoreState genau den eben gesicherten Stapel wiederherstellte.
        launchBottomNav(isMoving = false, hideNavOnDrive = false, startDestination = Screen.Settings.route)
        composeRule.runOnUiThread { navController.navigate(Screen.AppLogs.route) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(APP_LOGS_MARKER).assertIsDisplayed()

        composeRule.onNodeWithText(label(R.string.settings_title)).performClick()
        composeRule.waitForIdle()

        assertTrue(
            "expected ${Screen.Settings.route}, was ${navController.currentDestination?.route}",
            navController.currentDestination?.route == Screen.Settings.route,
        )
        composeRule.onNodeWithText(SETTINGS_MARKER).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.settings_title)).assertIsSelected()
    }

    @Test
    fun reTappingTheTripsItem_onTheFreezeFramePage_returnsToTheTripsStartPage() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false, startDestination = Screen.TripsAndFaults.route)
        composeRule.runOnUiThread { navController.navigate(Screen.FreezeFrame.route) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(FREEZE_FRAME_MARKER).assertIsDisplayed()

        composeRule.onNodeWithText(label(R.string.nav_trips)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(TRIPS_MARKER).assertIsDisplayed()
    }

    @Test
    fun reTappingTheSelectedItem_atItsStartPage_staysThere() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false, startDestination = Screen.TripsAndFaults.route)

        composeRule.onNodeWithText(label(R.string.nav_trips)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(TRIPS_MARKER).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.nav_trips)).assertIsSelected()
    }

    @Test
    fun theMenuHasFourItems_andDtcsAndHistoryAreNoTabsAnyMore() {
        // Seit 1.33.0 sind "DTCs" und "Verlauf" der eine Punkt "Fahrten".
        launchBottomNav(isMoving = false, hideNavOnDrive = false)

        for (item in listOf(R.string.nav_live, R.string.nav_trips, R.string.settings_title, R.string.nav_exit)) {
            composeRule.onNodeWithText(label(item)).assertIsDisplayed()
        }
        for (gone in listOf("DTCs", "Verlauf", "History")) composeRule.onAllNodesWithText(gone).assertCountEquals(0)
    }

    @Test
    fun clickingNavItem_navigatesToItsRoute() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false, startDestination = Screen.Dashboard.route)

        composeRule.onNodeWithText(label(R.string.nav_trips)).performClick()
        composeRule.waitForIdle()

        assertTrue(
            "expected current destination ${Screen.TripsAndFaults.route}, was ${navController.currentDestination?.route}",
            navController.currentDestination?.route == Screen.TripsAndFaults.route,
        )
        composeRule.onNodeWithText(TRIPS_MARKER).assertIsDisplayed()
    }

    @Test
    fun navHidden_true_showsAmbientStatusRowWithTemperature() {
        launchBottomNav(isMoving = true, hideNavOnDrive = true, outsideTempC = 20f)

        composeRule.onNodeWithText("20 °C").assertIsDisplayed()
    }

    @Test
    fun navHidden_true_withImperialUnits_showsTemperatureInFahrenheit() {
        // 20 °C * 9/5 + 32 = 68 °F — same convertUnit convention as the dashboard tiles.
        launchBottomNav(isMoving = true, hideNavOnDrive = true, outsideTempC = 20f, useImperial = true)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("68 °F").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("68 °F").assertIsDisplayed()
        composeRule.onNodeWithText("20 °C").assertDoesNotExist()
    }

    @Test
    fun navHidden_false_neverTouchesTheWeatherFlow() {
        // The weather flow is cold (WhileSubscribed): whoever collects it starts the 60-second
        // polling — network calls with the device's coordinates. BottomNav must therefore only
        // collect it in the branch that actually shows AmbientStatusRow, never for the nav bar.
        launchBottomNav(isMoving = false, hideNavOnDrive = true)

        composeRule.onNodeWithText(label(R.string.nav_live)).assertIsDisplayed()
        verify(exactly = 0) { weatherVm.outsideTempC }
    }

    @Test
    fun navHidden_true_withNullTemperature_stillHidesNavItems() {
        // WeatherRepository returns null on any failure (no fix, no permission, network error) —
        // AmbientStatusRow must degrade gracefully (no crash, just no temperature shown) rather than
        // assume a value is always present.
        launchBottomNav(isMoving = true, hideNavOnDrive = true, outsideTempC = null)

        composeRule.onNodeWithText(label(R.string.nav_live)).assertDoesNotExist()
    }

    @Test
    fun theMainMenuAndTheAmbientStatusRowAreExactlyAsTall() {
        // Nutzerfrage 2026-10-15: "Ist das Hauptmenue im Stand und die Statuszeile bei Fahrt mit Datum und Temperatur gleich hoch?"
        // Beide sind im Quelltext 64 dp + Trennlinie + Inset; hier wird die gemessene Hoehe verglichen (ein Hoehensprung beim AnimatedContent-Wechsel
        // waere ein sichtbarer Sprung des ganzen Bildschirms darueber). Eine Komposition, "faehrt" wird umgeschaltet.
        val moving = MutableStateFlow(false)
        composeRule.setContent {
            val vm = remember { makeVm(moving, hideNavOnDrive = true) }
            val weatherVm = remember { makeWeatherVm(outsideTempC = 20f) }
            val displayVm = remember { makeDisplayVm(useImperial = false) }
            val exitAppVm = remember { makeExitAppVm() }
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = Screen.Dashboard.route) {
                composable(Screen.Dashboard.route) { Text(DASHBOARD_MARKER) }
            }
            Box(Modifier.testTag(BOTTOM_AREA_TAG)) {
                BottomNav(navController = navController, vm = vm, weatherVm = weatherVm, displayVm = displayVm, exitAppVm = exitAppVm)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label(R.string.nav_live)).assertIsDisplayed()
        val mainMenuHeight = composeRule.onNodeWithTag(BOTTOM_AREA_TAG).fetchSemanticsNode().size.height

        moving.value = true
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("20 °C").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(label(R.string.nav_live)).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("20 °C").assertIsDisplayed()
        val ambientRowHeight = composeRule.onNodeWithTag(BOTTOM_AREA_TAG).fetchSemanticsNode().size.height

        val minimumPx = (65 * composeRule.activity.resources.displayMetrics.density).toInt() // Trennlinie 1 dp + 64 dp
        assertTrue("Hauptmenue nicht gemessen: $mainMenuHeight px", mainMenuHeight >= minimumPx)
        assertTrue(
            "Hauptmenue $mainMenuHeight px, Statuszeile $ambientRowHeight px — die Hoehe darf beim Wechsel nicht springen",
            mainMenuHeight == ambientRowHeight,
        )
    }

    // --- exit button + confirmation dialog ---
    //
    // The confirm path ends with Activity.finishAffinity() on whatever hosts the compose tree — in this class the real HiltTestActivity backing composeRule, and
    // finishing it mid-test would leave the rule operating on a destroyed Activity. So the confirm test hosts BottomNav in a context that is no Activity
    // (`hostIsNoActivity`): the dialog closes and the session ends exactly as in the app, only the final finishAffinity() has nobody to finish.
    // ExitAppViewModelTest covers exitApp()'s own logic (disconnect + stop service) on the JVM.

    @Test
    fun exitButton_isDisplayed() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false)

        composeRule.onNodeWithText(label(R.string.nav_exit)).assertIsDisplayed()
    }

    @Test
    fun clickingExitButton_showsConfirmationDialog() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false)

        composeRule.onNodeWithText(label(R.string.nav_exit)).performClick()

        composeRule.onNodeWithText(label(R.string.exit_app_dialog_title)).assertIsDisplayed()
    }

    @Test
    fun dialogCancel_dismissesWithoutDisconnecting() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false)
        composeRule.onNodeWithText(label(R.string.nav_exit)).performClick()

        composeRule.onNodeWithText(label(R.string.cancel)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(label(R.string.exit_app_dialog_title)).assertDoesNotExist()
        coVerify(exactly = 0) { exitAppObdRepo.disconnectByUser() }
    }

    @Test
    fun dialogConfirm_endsTheSessionByTheUserAndClosesTheDialog() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false, hostIsNoActivity = true)
        composeRule.onNodeWithText(label(R.string.nav_exit)).performClick()

        composeRule.onNodeWithText(label(R.string.exit_app_confirm)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(label(R.string.exit_app_dialog_title)).assertDoesNotExist()
        // Als Nutzer-Trennen (disconnectByUser): sonst kaeme die Verbindung von selbst zurueck
        coVerify(exactly = 1) { exitAppObdRepo.disconnectByUser() }
    }

    @Test
    fun theOpenExitDialog_survivesRecreation() {
        // UID-23: `remember` schloss den Dialog bei einer Drehung. Die Neuerstellung wird hier ueber den StateRestorationTester
        // nachgestellt, nicht ueber eine echte Rotation des Geraets.
        val restoration = StateRestorationTester(composeRule)
        launchBottomNav(isMoving = false, hideNavOnDrive = false, restoration = restoration)
        composeRule.onNodeWithText(label(R.string.nav_exit)).performClick()
        composeRule.onNodeWithText(label(R.string.exit_app_dialog_title)).assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(label(R.string.exit_app_dialog_title)).assertIsDisplayed()
    }

    @Test
    fun aClosedExitDialog_staysClosedAfterRecreation() {
        val restoration = StateRestorationTester(composeRule)
        launchBottomNav(isMoving = false, hideNavOnDrive = false, restoration = restoration)

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(label(R.string.exit_app_dialog_title)).assertDoesNotExist()
    }
}
