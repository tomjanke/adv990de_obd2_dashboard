package de.adv990de.obd2.ui.settings

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppLogExporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.AppLogEntity
import de.adv990de.obd2.idleObdRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [AppLogsScreen] — previously untested (documented coverage gap:
 * `parseLogLevelOrDefault()` is covered by the JVM `AppLogsScreenTest`, but the Composable itself,
 * including the empty-state/populated-list branch and `LogEntryRow`'s chip color `when`-mapping,
 * had zero instrumentation coverage).
 *
 * Strategy, mirroring [de.adv990de.obd2.ui.history.HistoryScreenTest]: [AppLogRepository] is a concrete
 * `@Inject constructor` class (not an interface), so instead of a bare relaxed mock — which would
 * make `logs` emit nothing and leave every test stuck on the empty state — a *second*, test-local
 * in-memory [AppDatabase] is wired into a real `AppLogRepository(dao)` instance, then wrapped with
 * [spyk] via `@BindValue`. Real Room query/Flow behavior stays intact; rows are inserted directly
 * through the test database's own `AppLogDao` before each test that needs a populated list.
 * Rows use `System.currentTimeMillis()` rather than a fixed past timestamp. (Until v1.23.2 that
 * was required because `AppLogsViewModel.init` pruned 30-day-old rows; retention now runs once at
 * app start via `DataRetention`, but fresh timestamps keep the rows well inside the window anyway.)
 *
 * `ObdRepository` is still replaced via `@BindValue` even though `AppLogsScreen` never touches it:
 * it is reached through `ObdForegroundService`'s Hilt entry point, and Hilt validates the whole app
 * graph per test class, not just what the test touches under test — same requirement as every other
 * Compose UI test in this module (`DtcScreenTest`, `HistoryScreenTest`, `SectionLabelTest`, etc.).
 *
 * Room's Flow-returning queries dispatch to Room's query executor on a background thread regardless
 * of `allowMainThreadQueries()` (that flag only covers direct suspend/blocking DAO calls), so
 * `logs` updates asynchronously after `setContent` — hence `waitUntil` polling for the expected text
 * rather than a single `waitForIdle()`, matching `HistoryScreenTest`'s paging-load wait.
 *
 * ## Color-assertion ceiling
 * `LogEntryRow`'s `chipColor`/`chipOnColor` `when`-mapping (3 levels × background/content color
 * pairs) has no precedent for pixel/color assertions anywhere in this codebase (`SectionLabelTest`
 * and `LiveValueTextTest` both document the same check and the same absence — no `captureToImage`
 * anywhere). Matching that established ceiling, the level-distinguishability tests below assert the
 * chip's visible label text (`level.name`, e.g. "INFO"/"WARN"/"ERROR" — rendered literally, not via
 * a string resource) rather than the resolved `Color` value: the label only renders correctly if
 * `parseLogLevelOrDefault` resolved the intended enum constant and the corresponding `when` branch
 * was hit, so this still exercises the real per-level branch, just not the specific color value.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppLogsScreenDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    /** Test-local in-memory database, separate from TestAppModule's — only AppLogRepository uses it. */
    private val testDb = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @BindValue
    @JvmField
    val appLogRepository: AppLogRepository = spyk(AppLogRepository(testDb.appLogDao()))

    /** The export itself is checked in `AppLogExporterDeviceTest`; here a mock shows what the screen asks of it (and when). */
    @BindValue
    @JvmField
    val appLogExporter: AppLogExporter = mockk()

    /**
     * AppLogsScreen never touches ObdRepository, but it is still part of the Hilt component graph
     * (ObdForegroundService's entry point, other HiltViewModels' constructors) — real ObdRepository
     * has hardware-coupled deps (ConnectionFactory/PidParser/DtcParser) unavailable here, so it must
     * be replaced exactly like in DtcScreenTest/HistoryScreenTest/SectionLabelTest.
     */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @After
    fun tearDown() {
        testDb.close()
    }

    // --- helpers ---

    private fun insertLog(level: String, message: String) = runBlocking {
        testDb.appLogDao().insert(
            AppLogEntity(timestamp = System.currentTimeMillis(), level = level, message = message)
        )
    }

    private fun launchAppLogsScreen() {
        composeRule.setContent {
            val navController = rememberNavController()
            AppLogsScreen(navController = navController)
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // --- tests ---

    @Test
    fun emptyLogList_showsEmptyStateText() {
        // No rows inserted into testDb — logs stays emptyList() (the ViewModel's stateIn default).
        launchAppLogsScreen()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.app_logs_empty))
            .assertIsDisplayed()
    }

    @Test
    fun populatedLogList_showsLogMessageInLazyColumn() {
        insertLog(level = "INFO", message = "Bluetooth connected")

        launchAppLogsScreen()
        waitForText("Bluetooth connected")

        composeRule.onNodeWithText("Bluetooth connected").assertIsDisplayed()
    }

    @Test
    fun infoLevelEntry_rendersInfoChipLabel() {
        insertLog(level = "INFO", message = "Info entry message")

        launchAppLogsScreen()
        waitForText("INFO")

        composeRule.onNodeWithText("INFO").assertIsDisplayed()
    }

    @Test
    fun warnLevelEntry_rendersWarnChipLabel() {
        insertLog(level = "WARN", message = "Warn entry message")

        launchAppLogsScreen()
        waitForText("WARN")

        composeRule.onNodeWithText("WARN").assertIsDisplayed()
    }

    @Test
    fun errorLevelEntry_rendersErrorChipLabel() {
        insertLog(level = "ERROR", message = "Error entry message")

        launchAppLogsScreen()
        waitForText("ERROR")

        composeRule.onNodeWithText("ERROR").assertIsDisplayed()
    }

    @Test
    fun mixedLevelEntries_eachRendersItsOwnDistinctChipLabel() {
        // All three levels in one populated list: proves the when-mapping picks a different label
        // per row rather than one branch leaking into the others.
        insertLog(level = "INFO", message = "Info row")
        insertLog(level = "WARN", message = "Warn row")
        insertLog(level = "ERROR", message = "Error row")

        launchAppLogsScreen()
        waitForText("Info row")

        composeRule.onNodeWithText("INFO").assertIsDisplayed()
        composeRule.onNodeWithText("WARN").assertIsDisplayed()
        composeRule.onNodeWithText("ERROR").assertIsDisplayed()
    }

    // --- delete all: confirmation, undo (the local delete contract) ---

    private fun str(resId: Int) = composeRule.activity.getString(resId)

    private fun waitUntilGone(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun logsInDatabase() = runBlocking { testDb.appLogDao().getAllNewestFirst().first() }

    private fun pressTrash() = composeRule.onNodeWithContentDescription(str(R.string.app_logs_clear)).performClick()

    @Test
    fun trashIcon_isDisabled_whenThereAreNoLogs() {
        launchAppLogsScreen()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(str(R.string.app_logs_clear)).assertIsNotEnabled()
    }

    @Test
    fun trashIcon_opensTheConfirmationDialog_withoutDeletingAnything() {
        insertLog(level = "INFO", message = "keep me for now")
        launchAppLogsScreen()
        waitForText("keep me for now")

        pressTrash()

        composeRule.onNodeWithText(str(R.string.app_logs_clear_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.app_logs_clear_message)).assertIsDisplayed()
        assertEquals(1, logsInDatabase().size)
    }

    @Test
    fun cancel_closesTheDialog_andKeepsTheLogs() {
        insertLog(level = "INFO", message = "still here")
        launchAppLogsScreen()
        waitForText("still here")

        pressTrash()
        composeRule.onNodeWithText(str(R.string.cancel)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(str(R.string.app_logs_clear_title)).assertDoesNotExist()
        composeRule.onNodeWithText("still here").assertIsDisplayed()
    }

    @Test
    fun confirm_hidesTheLogsAtOnce_showsTheEmptyStateAndOffersUndo_butDeletesNothingYet() {
        insertLog(level = "INFO", message = "going away")
        launchAppLogsScreen()
        waitForText("going away")

        pressTrash()
        composeRule.onNodeWithText(str(R.string.delete)).performClick()
        waitUntilGone("going away")

        composeRule.onNodeWithText(str(R.string.app_logs_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.app_logs_deleted)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.undo)).assertIsDisplayed()
        assertEquals("the row must still be in the database inside the undo window", 1, logsInDatabase().size)
    }

    @Test
    fun undo_bringsTheLogsBack_andDismissesTheSnackbar() {
        insertLog(level = "WARN", message = "restore me")
        launchAppLogsScreen()
        waitForText("restore me")

        pressTrash()
        composeRule.onNodeWithText(str(R.string.delete)).performClick()
        waitUntilGone("restore me")
        composeRule.onNodeWithText(str(R.string.undo)).performClick()

        waitForText("restore me")
        waitUntilGone(str(R.string.app_logs_deleted))
        assertEquals(1, logsInDatabase().size)
    }

    // --- export (share or save) ---

    private fun exportDescription() = str(R.string.app_logs_export)

    private fun exportMessage(count: Int) =
        composeRule.activity.resources.getQuantityString(R.plurals.app_logs_export_message, count, count)

    @Test
    fun exportIcon_isDisabled_whenThereAreNoLogs() {
        launchAppLogsScreen()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(exportDescription()).assertIsNotEnabled()
    }

    @Test
    fun exportIcon_isEnabled_whenThereAreLogs() {
        insertLog(level = "INFO", message = "something to export")
        launchAppLogsScreen()
        waitForText("something to export")

        composeRule.onNodeWithContentDescription(exportDescription()).assertIsEnabled()
    }

    @Test
    fun exportDialog_announcesTheEntryCount_andOffersSaveShareAndCancel_eachAtLeast56dpHigh() {
        insertLog(level = "INFO", message = "first")
        insertLog(level = "WARN", message = "second")
        launchAppLogsScreen()
        waitForText("second")

        composeRule.onNodeWithContentDescription(exportDescription()).performClick()

        waitForText(exportMessage(2))
        composeRule.onNodeWithText(str(R.string.app_logs_export_title)).assertIsDisplayed()
        composeRule.onNodeWithTag(APP_LOG_EXPORT_SAVE_TEST_TAG).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithTag(APP_LOG_EXPORT_SHARE_TEST_TAG).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        composeRule.onNode(hasText(str(R.string.cancel)) and hasClickAction()).assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithText(str(R.string.history_export_save)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.history_export_share)).assertIsDisplayed()
    }

    @Test
    fun cancellingTheExportDialog_closesIt_andExportsNothing() {
        insertLog(level = "INFO", message = "one entry")
        launchAppLogsScreen()
        waitForText("one entry")
        composeRule.onNodeWithContentDescription(exportDescription()).performClick()
        waitForText(exportMessage(1))

        composeRule.onNode(hasText(str(R.string.cancel)) and hasClickAction()).performClick()

        waitUntilGone(exportMessage(1))
        coVerify(exactly = 0) { appLogExporter.exportToCsv(any()) }
        coVerify(exactly = 0) { appLogExporter.saveCsv(any(), any()) }
    }

    @Test
    fun share_asksTheExporterForTheWholeLog_closesTheDialog_andShowsAFailureAsASnackbar() {
        insertLog(level = "INFO", message = "to be shared")
        coEvery { appLogExporter.exportToCsv(any()) } throws RuntimeException("boom")
        launchAppLogsScreen()
        waitForText("to be shared")
        composeRule.onNodeWithContentDescription(exportDescription()).performClick()
        waitForText(exportMessage(1))

        composeRule.onNodeWithTag(APP_LOG_EXPORT_SHARE_TEST_TAG).performClick()

        waitForText(composeRule.activity.getString(R.string.history_export_error_prefix, "boom"))
        waitUntilGone(exportMessage(1))
        coVerify(exactly = 1) { appLogExporter.exportToCsv(0L) }
    }

    @Test
    fun exportIcon_isDisabled_whileADeleteAllCanStillBeUndone_becauseNothingIsVisible() {
        insertLog(level = "INFO", message = "going away")
        launchAppLogsScreen()
        waitForText("going away")

        pressTrash()
        composeRule.onNodeWithText(str(R.string.delete)).performClick()
        waitUntilGone("going away")

        composeRule.onNodeWithContentDescription(exportDescription()).assertIsNotEnabled()
    }
}
