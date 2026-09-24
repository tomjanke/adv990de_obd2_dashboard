package de.adv990de.obd2.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.ui.DriveStateViewModel
import de.adv990de.obd2.ui.navigation.Screen
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [BottomNav] — previously untested (no JVM or androidTest file existed for
 * it anywhere in the repo before this class; documented coverage gap).
 *
 * Covers the three pieces of real logic in [BottomNav]:
 * - Selected-item matching (`currentRoute == screen.route`), asserted via
 *   [androidx.compose.ui.test.assertIsSelected]/[androidx.compose.ui.test.assertIsNotSelected] —
 *   `NavigationBarItem`'s `selectable()` modifier merges its icon+label semantics upward onto one
 *   node (the same "clickable/selectable merges descendants" convention documented in
 *   [LiveValueTileTest]), so `onNodeWithText(label)` resolves to the item's own selectable node
 *   and carries its `selected` semantics property.
 * - Visibility gating via `vm.isNavHidden` (`AnimatedVisibility(visible = !isNavHidden)`).
 * - The `onClick` navigation call (`navigate(route) { popUpTo/launchSingleTop/restoreState }`).
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
 * ([LiveValueTileTest], [SectionLabelTest]): Hilt validates the whole app graph per test class
 * (reached through `ObdForegroundService`'s entry point) regardless of what the composable under
 * test itself resolves via Hilt — `BottomNav` here resolves nothing via Hilt at all once `vm` is
 * supplied explicitly.
 *
 * Navigation assertions need a real [androidx.navigation.NavController] with an attached graph —
 * `navController.navigate(route) { popUpTo(...) }` throws against a graph-less
 * `rememberNavController()` — so [launchBottomNav] wraps [BottomNav] in a minimal real `NavHost`
 * with one marker-`Text` destination per [Screen] item, mirroring
 * [de.adv990de.obd2.ui.dtc.DtcScreenTest]'s `dtcScreen_navigatesToFreezeFrame_whenLinkClicked` test.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BottomNavTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { isMovingFlow } returns MutableStateFlow(false)
    }

    @Before
    fun setup() {
        hiltRule.inject()
    }

    // --- helpers ---

    private fun makeVm(isMoving: Boolean, hideNavOnDrive: Boolean): DriveStateViewModel {
        val obdRepo = mockk<ObdRepository> {
            every { isMovingFlow } returns MutableStateFlow(isMoving)
        }
        val prefs = mockk<AppPreferences> {
            every { this@mockk.hideNavOnDrive } returns flowOf(hideNavOnDrive)
        }
        return DriveStateViewModel(obdRepo, prefs)
    }

    private lateinit var navController: NavHostController

    private fun launchBottomNav(
        isMoving: Boolean,
        hideNavOnDrive: Boolean,
        startDestination: String = Screen.Dashboard.route
    ) {
        composeRule.setContent {
            val vm = remember { makeVm(isMoving = isMoving, hideNavOnDrive = hideNavOnDrive) }
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = startDestination) {
                composable(Screen.Dashboard.route) { Text(DASHBOARD_MARKER) }
                composable(Screen.Dtc.route) { Text(DTC_MARKER) }
                composable(Screen.History.route) { Text(HISTORY_MARKER) }
                composable(Screen.Settings.route) { Text(SETTINGS_MARKER) }
            }
            BottomNav(navController = navController, vm = vm)
        }
        composeRule.waitForIdle()
    }

    private fun label(resId: Int): String = composeRule.activity.getString(resId)

    companion object {
        private const val DASHBOARD_MARKER = "DASHBOARD_DESTINATION_MARKER"
        private const val DTC_MARKER = "DTC_DESTINATION_MARKER"
        private const val HISTORY_MARKER = "HISTORY_DESTINATION_MARKER"
        private const val SETTINGS_MARKER = "SETTINGS_DESTINATION_MARKER"
    }

    // --- tests ---

    @Test
    fun itemMatchingCurrentRoute_isSelected() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false, startDestination = Screen.Dtc.route)

        composeRule.onNodeWithText(label(R.string.nav_dtcs)).assertIsSelected()
    }

    @Test
    fun itemNotMatchingCurrentRoute_isNotSelected() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false, startDestination = Screen.Dtc.route)

        composeRule.onNodeWithText(label(R.string.nav_live)).assertIsNotSelected()
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
    fun clickingNavItem_navigatesToItsRoute() {
        launchBottomNav(isMoving = false, hideNavOnDrive = false, startDestination = Screen.Dashboard.route)

        composeRule.onNodeWithText(label(R.string.nav_history)).performClick()
        composeRule.waitForIdle()

        assert(navController.currentDestination?.route == Screen.History.route) {
            "expected current destination ${Screen.History.route}, was ${navController.currentDestination?.route}"
        }
        composeRule.onNodeWithText(HISTORY_MARKER).assertIsDisplayed()
    }
}
