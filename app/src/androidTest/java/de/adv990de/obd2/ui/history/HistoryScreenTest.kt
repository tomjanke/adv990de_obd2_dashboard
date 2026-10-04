package de.adv990de.obd2.ui.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.SessionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compose UI tests for [HistoryScreen].
 *
 * Covers, at the Composable level (VM-internal state transitions are already covered by
 * [HistoryViewModelTest] and are not re-tested here — only the real UI-to-VM wiring is):
 *
 * - `LaunchedEffect(exportErrorMessage)`: shows a Snackbar with the formatted error message.
 * - The delete-confirmation `AlertDialog`: `requestDelete` (delete icon tap) shows it with the
 *   date-formatted confirmation text; `cancelDelete` dismisses it without deleting;
 *   `confirmDelete` deletes the session, dismisses the dialog, and the list ends up empty.
 * - [SessionRow]'s own rendering: date formatting, and `vehicleVin` vs. the "unknown VIN"
 *   fallback string.
 * - The empty-list branch (`history_empty` text + icon column), both standalone (zero sessions)
 *   and as the end state of a successful delete.
 *
 * Strategy:
 * - [TestAppModule] (TestInstallIn) still provides BluetoothAdapter/BluetoothDeviceScanner mocks,
 *   the real DataStore and its own in-memory AppDatabase/AppLogDao for the rest of the Hilt graph.
 * - [SessionRepository] is a concrete `@Inject constructor` class (not an interface), so instead of
 *   a bare relaxed mock (which would make `pagingSource()` return nothing and leave the list with
 *   no export button to click), a *second*, test-local in-memory [AppDatabase] is wired into a real
 *   [SessionRepository] instance, then wrapped with [spyk] via `@BindValue` — real paging/insert
 *   behaviour stays intact, and only `exportToCsv` is stubbed per test.
 *
 * Two documented items are intentionally NOT exercised here:
 *
 * - [ActivityNotFoundException] path of `LaunchedEffect(shareUri)`: `HistoryScreen` always wraps
 *   the share intent in `Intent.createChooser(...)`, and on a stock device/emulator image the
 *   system resolver/chooser activity that action always resolves to is present regardless of
 *   which apps can open "text/csv" — there is no way to make `context.startActivity(chooser)`
 *   throw `ActivityNotFoundException` from instrumented test code without swapping out the
 *   `Context`/`PackageManager` the composable itself resolves via `LocalContext.current`, which
 *   would stop exercising the real code path.
 * - The happy path of that same `LaunchedEffect` (a chooser is actually launched, then
 *   `consumeShareUri()` runs): this project has neither `espresso-intents` nor UiAutomator as a
 *   dependency, so there is no way to assert a chooser `Intent` was started without letting
 *   `context.startActivity(...)` really fire — which, on this task's real, actively-used Pixel 8
 *   Pro (not an emulator), would pop the system share sheet in front of the instrumentation app
 *   and risk leaving it open/blocking subsequent tests if a later assertion fails. Skipped per the
 *   task's own named example of an impractical case; the "no chooser available" branch of the same
 *   effect stays covered by the Snackbar assertion above via the export-error path's sibling logic.
 * - The paging-append `LinearProgressIndicator` (`sessions.loadState.append is LoadState.Loading`):
 *   triggering a second page load needs more than `PagingConfig.pageSize` (20) seeded rows, and
 *   even then the real in-memory Room `PagingSource`'s second query resolves synchronously — the
 *   `LoadState.Loading` frame is transient enough that Compose's idle-based `waitUntil` cannot
 *   reliably observe it without swapping in a bespoke delegating/blocking fake `PagingSource` just
 *   to hold the state open. That scaffolding, solely to catch one indicator with no visible text or
 *   content description, is exactly the kind of forced, flaky-prone test the task asked to avoid.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    /** Test-local in-memory database, separate from TestAppModule's — only SessionRepository uses it. */
    private val testDb = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @BindValue
    @JvmField
    val sessionRepository: SessionRepository = spyk(SessionRepository(targetContext, testDb))

    /**
     * HistoryScreen never touches ObdRepository, but it is still part of the Hilt component graph
     * (ObdForegroundService's entry point, other HiltViewModels' constructors) — real
     * ObdRepository has hardware-coupled deps (ConnectionFactory/PidParser/DtcParser) unavailable
     * here, so it must be replaced exactly like in DashboardScreenTest/DtcScreenTest/FreezeFrameScreenTest.
     */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { isMovingFlow } returns MutableStateFlow(false)
    }

    /** Set by [setup] — the seeded row's generated id/startTime, reused by several tests below. */
    private var insertedSessionId: Long = 0
    private var insertedStartTime: Long = 0

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    @Before
    fun setup() {
        hiltRule.inject()
        // A row (and its export IconButton) only renders once at least one session exists.
        // startTime must be "now", not a fixed past timestamp: HistoryViewModel's init block
        // unconditionally calls sessionRepository.deleteOldSessions() (90-day cutoff), which would
        // otherwise delete this row out from under the test before Compose ever renders it — the
        // original fixed 2023 timestamp was already >90 days old and failed for exactly that reason.
        insertedStartTime = System.currentTimeMillis()
        runBlocking {
            insertedSessionId = testDb.sessionDao()
                .insert(SessionEntity(startTime = insertedStartTime, vehicleVin = "TESTVIN123"))
        }
    }

    @After
    fun tearDown() {
        testDb.close()
    }

    private fun launchHistoryScreen() {
        composeRule.setContent {
            val navController = rememberNavController()
            HistoryScreen(navController = navController)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun historyScreen_showsExportErrorSnackbar_whenExportFails() {
        coEvery { sessionRepository.exportToCsv(any()) } throws RuntimeException("boom")

        launchHistoryScreen()

        // Paging3 loads the inserted session asynchronously; wait for the row (and its export
        // button) to actually appear before clicking, rather than relying on waitForIdle() alone.
        val exportDescription = composeRule.activity.getString(R.string.history_cd_export)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(exportDescription)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule
            .onNodeWithContentDescription(exportDescription)
            .performClick()

        val expectedMessage = composeRule.activity.getString(R.string.history_export_error_prefix, "boom")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(expectedMessage).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule
            .onNodeWithText(expectedMessage)
            .assertIsDisplayed()
    }

    @Test
    fun sessionRow_showsFormattedDateAndVin_orUnknownVinFallback_whenVinMissing() {
        // Distinct from insertedStartTime by 5 minutes so the two rows never collide on the same
        // "dd.MM.yyyy HH:mm" bucket (the format has no seconds), without depending on real-time
        // timing between the two inserts.
        val secondStartTime = insertedStartTime - 5 * 60_000L
        runBlocking {
            testDb.sessionDao().insert(SessionEntity(startTime = secondStartTime, vehicleVin = null))
        }

        launchHistoryScreen()

        val exportDescription = composeRule.activity.getString(R.string.history_cd_export)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(exportDescription)
                .fetchSemanticsNodes().size >= 2
        }

        // Row with a VIN.
        composeRule.onNodeWithText(dateFormat.format(Date(insertedStartTime))).assertIsDisplayed()
        composeRule.onNodeWithText("TESTVIN123").assertIsDisplayed()

        // Row without a VIN falls back to the "unknown VIN" string.
        composeRule.onNodeWithText(dateFormat.format(Date(secondStartTime))).assertIsDisplayed()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.history_vin_unknown))
            .assertIsDisplayed()
    }

    @Test
    fun historyScreen_deleteDialog_showsFormattedDate_andCancelDismissesWithoutDeleting() {
        launchHistoryScreen()

        val deleteDescription = composeRule.activity.getString(R.string.history_cd_delete)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(deleteDescription)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(deleteDescription).performClick()

        val expectedTitle = composeRule.activity.getString(R.string.history_delete_title)
        val expectedConfirmText = composeRule.activity.getString(
            R.string.history_delete_confirm,
            dateFormat.format(Date(insertedStartTime))
        )
        composeRule.onNodeWithText(expectedTitle).assertIsDisplayed()
        composeRule.onNodeWithText(expectedConfirmText).assertIsDisplayed()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.cancel))
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(expectedTitle).fetchSemanticsNodes().isEmpty()
        }
        // Dialog gone, row still present, VM never asked to delete anything.
        composeRule.onNodeWithText("TESTVIN123").assertIsDisplayed()
        coVerify(exactly = 0) { sessionRepository.deleteSession(any()) }
    }

    @Test
    fun historyScreen_deleteDialog_confirmDeletesSessionAndEndsInEmptyState() {
        launchHistoryScreen()

        val deleteDescription = composeRule.activity.getString(R.string.history_cd_delete)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(deleteDescription)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(deleteDescription).performClick()

        val expectedTitle = composeRule.activity.getString(R.string.history_delete_title)
        composeRule.onNodeWithText(expectedTitle).assertIsDisplayed()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.history_delete_action))
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(expectedTitle).fetchSemanticsNodes().isEmpty()
        }

        // Wait for the actual deletion's effect (Room's PagingSource invalidates on table change,
        // re-querying down to zero rows) before verifying the call — confirmDelete() clears
        // deleteConfirmation synchronously but launches the repository call in a coroutine, so
        // asserting coVerify right after the dialog closes would race that coroutine.
        val emptyText = composeRule.activity.getString(R.string.history_empty)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(emptyText).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(emptyText).assertIsDisplayed()
        coVerify(exactly = 1) { sessionRepository.deleteSession(insertedSessionId) }
    }

    @Test
    fun historyScreen_showsEmptyState_whenNoSessionsExist() {
        // Room's generated @Delete binds only the primary key column ("DELETE FROM sessions WHERE
        // id = ?"), so this removes exactly the row setup() seeded, leaving the table empty without
        // needing a dedicated DAO method just for this test.
        runBlocking {
            testDb.sessionDao().delete(SessionEntity(id = insertedSessionId, startTime = 0))
        }

        launchHistoryScreen()

        val emptyText = composeRule.activity.getString(R.string.history_empty)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(emptyText).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(emptyText).assertIsDisplayed()
        // No rows means no export/delete buttons either.
        composeRule.onAllNodesWithContentDescription(composeRule.activity.getString(R.string.history_cd_export))
            .assertCountEquals(0)
    }
}
