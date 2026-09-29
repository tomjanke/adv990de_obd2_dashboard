package de.adv990de.obd2.ui.loggingconfig

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Compose UI tests for [LoggingConfigScreen] — previously untested (documented coverage gap): the
 * JVM `LoggingConfigScreenTest` only covers the extracted pure helper `formatLoggingRowValue`. The
 * Composable itself — the "Select All"/"Deselect All" buttons' wiring to
 * `vm.selectAll()`/`vm.deselectAll()`, each row's click wiring to `vm.togglePid(pid.id)`, and a
 * row's label+value text assembly (`stringResource(pid.labelRes)` + `formatLoggingRowValue`) — had
 * zero instrumentation coverage before this file.
 *
 * Strategy, mirroring `SettingsScreenTest`/`SimulatedDashboardTest`: [AppPreferences] is a concrete
 * `@Singleton @Inject constructor` class backed by the real, on-device DataStore that
 * [de.adv990de.obd2.di.TestAppModule] provides — it is therefore never mocked, but injected directly
 * (`@Inject lateinit var`) so tests can pre-seed `loggedPidIds` before rendering and read the real
 * persisted value back afterwards, rather than verifying a call was made on a mock. The DataStore
 * file persists across test runs on the real device, so every test explicitly seeds the exact
 * starting state it needs instead of assuming a fresh default.
 *
 * [ObdRepository] is replaced via `@BindValue` for the same reason as every other Compose UI test
 * in this module (Hilt validates the whole app graph per test class; the real ObdRepository has
 * hardware-coupled deps unavailable here). `latestReadings` defaults to `emptyList()` so rows fall
 * back to `formatLoggingRowValue`'s NaN branch ("-- (unit)"), matching the ViewModel's own
 * `firstOrNull` miss case — one test overrides it with a real [SensorValue] to exercise the
 * non-fallback formatting branch together with the label assembly.
 *
 * Row clicks target the row's exact assembled label text (e.g. "RPM: -- (rpm)") rather than a
 * substring or test tag: `ListItem`'s `Modifier.clickable` sits on the row root and merges its
 * descendants' semantics (including the headline `Text` and the `Checkbox`), so the merged node
 * `onNodeWithText` finds already carries the click action. The label text itself never depends on
 * `loggedPidIds`/checked state (only `latestReadings` affects it), so it is a stable, deterministic
 * click target independent of any StateFlow-arrival race — unlike the checked state itself, which
 * is not asserted here for that reason (see `DashboardConfigScreenTest`'s KDoc for the identical
 * `stateIn` default-vs-DataStore race).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LoggingConfigScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { isMovingFlow } returns MutableStateFlow(false)
        every { latestReadings } returns MutableStateFlow(emptyList())
    }

    /**
     * The real DataStore-backed instance (see `TestAppModule`) — same direct-injection precedent
     * as `SettingsScreenTest.appPreferences`/`SimulatedDashboardTest.appPreferences`. Used to
     * pre-seed `loggedPidIds`/`useImperial` to a known state before each test and to read back
     * `loggedPidIds` afterwards, since the DataStore file persists across test runs on a real
     * device and must not be trusted to already be at its default.
     */
    @Inject
    lateinit var appPreferences: AppPreferences

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private fun launchLoggingConfigScreen() {
        composeRule.setContent {
            val navController = rememberNavController()
            LoggingConfigScreen(navController = navController)
        }
        composeRule.waitForIdle()
    }

    /** Polls the real DataStore-backed flow, like `SettingsScreenTest.waitUntilGpsSpeedSwitchIsOn`,
     *  instead of a single `waitForIdle()`: the write goes through `vm`'s `viewModelScope.launch`,
     *  which is not guaranteed to have completed by the time the click's own frame settles. */
    private fun waitUntilLoggedPidIds(predicate: (Set<String>) -> Boolean) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { predicate(appPreferences.loggedPidIds.first()) }
        }
    }

    private fun selectAllLabel() = composeRule.activity.getString(R.string.logging_config_select_all)
    private fun deselectAllLabel() = composeRule.activity.getString(R.string.logging_config_deselect_all)

    // --- Select All / Deselect All ---

    @Test
    fun selectAllButton_click_setsLoggedPidIdsToAllPids() {
        runBlocking { appPreferences.setLoggedPids(setOf(PidRegistry.RPM.id)) } // anything short of "all"

        launchLoggingConfigScreen()
        composeRule.onNodeWithText(selectAllLabel()).performClick()

        waitUntilLoggedPidIds { it == AppPreferences.ALL_PID_IDS }
        assertEquals(AppPreferences.ALL_PID_IDS, runBlocking { appPreferences.loggedPidIds.first() })
    }

    @Test
    fun deselectAllButton_click_setsLoggedPidIdsToEmpty() {
        runBlocking { appPreferences.setLoggedPids(AppPreferences.ALL_PID_IDS) }

        launchLoggingConfigScreen()
        composeRule.onNodeWithText(deselectAllLabel()).performClick()

        waitUntilLoggedPidIds { it.isEmpty() }
        assertTrue(runBlocking { appPreferences.loggedPidIds.first() }.isEmpty())
    }

    // --- individual row toggle ---
    // Uses ACCELERATOR_PEDAL_POS rather than RPM: `allPids` is sorted alphabetically by
    // PidDefinition.name (`sortedBy { it.name }`), and "Accelerator Pedal Pos" sorts first among
    // the 19 registered PIDs, guaranteeing the row is composed inside the LazyColumn's initial
    // viewport without needing a scroll-to-node gesture. "RPM" sorts ~15th and is off-screen at
    // first composition, which a first attempt at this test with RPM confirmed empirically
    // (`onNodeWithText` found no node at all for its row until scrolled into view).

    @Test
    fun clickingPidRow_whenPidIsLogged_removesItFromLoggedPidIds() {
        runBlocking { appPreferences.setLoggedPids(AppPreferences.ALL_PID_IDS) } // pedal pos starts logged (on)

        launchLoggingConfigScreen()
        val rowLabel = "${composeRule.activity.getString(PidRegistry.ACCELERATOR_PEDAL_POS.labelRes)}: -- (${PidRegistry.ACCELERATOR_PEDAL_POS.unit})"
        composeRule.onNodeWithText(rowLabel).performClick()

        waitUntilLoggedPidIds { PidRegistry.ACCELERATOR_PEDAL_POS.id !in it }
        assertTrue(PidRegistry.ACCELERATOR_PEDAL_POS.id !in runBlocking { appPreferences.loggedPidIds.first() })
    }

    @Test
    fun clickingPidRow_whenPidIsNotLogged_addsItToLoggedPidIds() {
        // pedal pos starts off
        runBlocking { appPreferences.setLoggedPids(AppPreferences.ALL_PID_IDS - PidRegistry.ACCELERATOR_PEDAL_POS.id) }

        launchLoggingConfigScreen()
        val rowLabel = "${composeRule.activity.getString(PidRegistry.ACCELERATOR_PEDAL_POS.labelRes)}: -- (${PidRegistry.ACCELERATOR_PEDAL_POS.unit})"
        composeRule.onNodeWithText(rowLabel).performClick()

        waitUntilLoggedPidIds { PidRegistry.ACCELERATOR_PEDAL_POS.id in it }
        assertTrue(PidRegistry.ACCELERATOR_PEDAL_POS.id in runBlocking { appPreferences.loggedPidIds.first() })
    }

    // --- row label+value assembly ---

    @Test
    fun pidRow_displaysLabelAndFormattedValue_fromLatestReadings() {
        runBlocking { appPreferences.setUseImperial(false) }
        every { obdRepository.latestReadings } returns MutableStateFlow(
            listOf(SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 87f))
        )

        launchLoggingConfigScreen()

        // formatLoggingRowValue(87f, "°C", useImperial = false) -> "87 (°C)" (JVM-verified in the
        // existing LoggingConfigScreenTest); this pins down the Composable actually combines that
        // with stringResource(pid.labelRes), which the JVM test cannot exercise on its own.
        //
        // Root cause found 2026-09-29 (unrelated to any app-code change — confirmed via `git stash`
        // against unmodified HEAD, and via a onRoot().printToLog() dump): the LazyColumn only
        // composes ~5 of 19 rows before the first frame settles on a Pixel 8 Pro, so "Coolant Temp"
        // at position 6 genuinely doesn't exist in the tree yet — a plain onNodeWithText(...)
        // (with or without performScrollTo()/waitUntil retries) can never find it, since
        // performScrollTo() itself requires the node to already be composed. performScrollToNode()
        // on the LazyColumn's own node is the only API that can bring an uncomposed item into view.
        val coolantLabel = composeRule.activity.getString(PidRegistry.COOLANT_TEMP.labelRes)
        composeRule.onNodeWithTag(LOGGING_CONFIG_LIST_TEST_TAG)
            .performScrollToNode(hasText("$coolantLabel: 87 (°C)"))
        composeRule.onNodeWithText("$coolantLabel: 87 (°C)").assertIsDisplayed()
    }
}
