package de.adv990de.obd2.ui.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
 * - [SessionRow]'s own rendering: date formatting, and `vehicleVin` vs. the "unknown VIN" fallback.
 * - The shared local-delete contract: the row trash button and the swipe delete a trip WITHOUT a
 *   dialog (the trip vanishes at once, a "Rückgängig" snackbar appears, nothing is deleted yet, undo
 *   brings it back); the top-bar trash asks first, cancel keeps everything, confirm hides every
 *   finished trip. The 10 s undo window itself is a ViewModel matter (virtual time in
 *   [HistoryViewModelTest]); here only "not deleted yet" and "undo restores" are asserted.
 * - The trip that is being recorded: badge, no trash button, no swipe, spared by "delete all".
 * - The empty-list branch (`history_empty`), and the disabled top-bar trash when nothing can be deleted.
 *
 * Strategy:
 * - [TestAppModule] (TestInstallIn) still provides BluetoothAdapter/BluetoothDeviceScanner mocks,
 *   the real DataStore, the app-wide scope and its own in-memory AppDatabase/AppLogDao for the rest
 *   of the Hilt graph.
 * - [SessionRepository] is a concrete `@Inject constructor` class (not an interface), so a
 *   *second*, test-local in-memory [AppDatabase] is wired into a real [SessionRepository], wrapped
 *   with [spyk] via `@BindValue` — real paging/insert/active-trip behaviour stays intact, and only
 *   `exportToCsv` is stubbed per test (`startSession` marks a trip as the one being recorded).
 *
 * Two documented items are intentionally NOT exercised here:
 *
 * - [ActivityNotFoundException] path and the happy path of `LaunchedEffect(shareUri)`: the share
 *   chooser would pop up in front of the instrumentation app on a real device (no
 *   espresso-intents / UiAutomator in this project).
 * - The paging-append `LinearProgressIndicator`: its `LoadState.Loading` frame is too transient to
 *   observe reliably without a bespoke blocking `PagingSource`.
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
        // startTime is "now" rather than a fixed past timestamp. (Until v1.23.2 this was required:
        // HistoryViewModel's init block called deleteOldSessions() with a 90-day cutoff. Retention now
        // runs once at app start via DataRetention; "now" keeps the row inside the window regardless.)
        insertedStartTime = System.currentTimeMillis()
        runBlocking {
            insertedSessionId = testDb.sessionDao()
                .insert(SessionEntity(startTime = insertedStartTime, endTime = insertedStartTime + 60_000L, vehicleVin = "WP0ZZZ99ZTS392124"))
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

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun waitForText(text: String) = composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTextToDisappear(text: String) = composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    private fun waitForDescription(description: String) = composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
    }

    /** "1 Fahrt gelöscht" / "n Fahrten gelöscht" — the snackbar text of the undoable batch. */
    private fun deletedMessage(count: Int) =
        composeRule.activity.resources.getQuantityString(R.plurals.history_deleted, count, count)

    // ── export and rendering (unchanged behaviour) ────────────────────────────

    @Test
    fun historyScreen_showsExportErrorSnackbar_whenExportFails() {
        coEvery { sessionRepository.exportToCsv(any()) } throws RuntimeException("boom")

        launchHistoryScreen()

        // Paging3 loads the inserted session asynchronously; wait for the row (and its export
        // button) to actually appear before clicking, rather than relying on waitForIdle() alone.
        val exportDescription = string(R.string.history_cd_export)
        waitForDescription(exportDescription)
        composeRule.onNodeWithContentDescription(exportDescription).performClick()

        val expectedMessage = composeRule.activity.getString(R.string.history_export_error_prefix, "boom")
        waitForText(expectedMessage)
        composeRule.onNodeWithText(expectedMessage).assertIsDisplayed()
    }

    @Test
    fun sessionRow_showsUnknownVin_forTripsStoredWithTheRawAdapterAnswer() {
        // Bis v1.23.1 landete die rohe Antwort auf Modus 09 als "FIN" in der Fahrt ("NO DATA" oder ein
        // Hexdump). Diese alten Zeilen bleiben in der Datenbank; angezeigt wird nur, was eine FIN ist.
        val rawStartTime = insertedStartTime - 5 * 60_000L
        runBlocking {
            testDb.sessionDao().insert(SessionEntity(startTime = rawStartTime, vehicleVin = "NO DATA"))
        }

        launchHistoryScreen()

        val exportDescription = string(R.string.history_cd_export)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(exportDescription).fetchSemanticsNodes().size >= 2
        }
        composeRule.onNodeWithText(dateFormat.format(Date(rawStartTime))).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.history_vin_unknown)).assertIsDisplayed()
        composeRule.onNodeWithText("NO DATA").assertDoesNotExist()
        composeRule.onNodeWithText("WP0ZZZ99ZTS392124").assertIsDisplayed()
    }

    @Test
    fun sessionRow_showsFormattedDateAndVin_orUnknownVinFallback_whenVinMissing() {
        // Distinct from insertedStartTime by 5 minutes so the two rows never collide on the same
        // "dd.MM.yyyy HH:mm" bucket (the format has no seconds).
        val secondStartTime = insertedStartTime - 5 * 60_000L
        runBlocking {
            testDb.sessionDao().insert(SessionEntity(startTime = secondStartTime, vehicleVin = null))
        }

        launchHistoryScreen()

        val exportDescription = string(R.string.history_cd_export)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(exportDescription).fetchSemanticsNodes().size >= 2
        }

        // Row with a VIN.
        composeRule.onNodeWithText(dateFormat.format(Date(insertedStartTime))).assertIsDisplayed()
        composeRule.onNodeWithText("WP0ZZZ99ZTS392124").assertIsDisplayed()

        // Row without a VIN falls back to the "unknown VIN" string.
        composeRule.onNodeWithText(dateFormat.format(Date(secondStartTime))).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.history_vin_unknown)).assertIsDisplayed()
    }

    @Test
    fun historyScreen_showsEmptyState_whenNoSessionsExist() {
        runBlocking {
            testDb.sessionDao().delete(SessionEntity(id = insertedSessionId, startTime = 0))
        }

        launchHistoryScreen()

        val emptyText = string(R.string.history_empty)
        waitForText(emptyText)
        composeRule.onNodeWithText(emptyText).assertIsDisplayed()
        // No rows means no export/delete buttons either.
        composeRule.onAllNodesWithContentDescription(string(R.string.history_cd_export)).assertCountEquals(0)
    }

    // ── one trip: trash button or swipe, no dialog, undo only ─────────────────

    @Test
    fun rowTrash_hidesTheTripAtOnce_andOffersUndo_withoutAskingFirst() {
        launchHistoryScreen()
        waitForText("WP0ZZZ99ZTS392124")

        composeRule.onNodeWithContentDescription(string(R.string.history_cd_delete)).performClick()

        waitForTextToDisappear("WP0ZZZ99ZTS392124")
        waitForText(deletedMessage(1))
        composeRule.onNodeWithText(string(R.string.undo)).assertIsDisplayed()
        // No dialog for a single trip, and nothing is deleted before the undo window ends.
        composeRule.onAllNodesWithText(string(R.string.history_delete_all_title)).assertCountEquals(0)
        coVerify(exactly = 0) { sessionRepository.deleteSessions(any()) }
    }

    @Test
    fun undo_bringsTheTripBack_andNothingIsDeleted() {
        launchHistoryScreen()
        waitForText("WP0ZZZ99ZTS392124")
        composeRule.onNodeWithContentDescription(string(R.string.history_cd_delete)).performClick()
        waitForText(deletedMessage(1))

        composeRule.onNodeWithText(string(R.string.undo)).performClick()

        waitForText("WP0ZZZ99ZTS392124")
        composeRule.onNodeWithText("WP0ZZZ99ZTS392124").assertIsDisplayed()
        composeRule.onAllNodesWithText(deletedMessage(1)).assertCountEquals(0)
        coVerify(exactly = 0) { sessionRepository.deleteSessions(any()) }
    }

    @Test
    fun swipeLeft_hidesTheTrip_andOffersUndo_likeTheTrashButton() {
        launchHistoryScreen()
        waitForText("WP0ZZZ99ZTS392124")

        composeRule.onNodeWithTag(historyRowTestTag(insertedSessionId)).performTouchInput { swipeLeft() }

        waitForTextToDisappear("WP0ZZZ99ZTS392124")
        waitForText(deletedMessage(1))
        coVerify(exactly = 0) { sessionRepository.deleteSessions(any()) }
    }

    // ── all trips: top-bar trash with a dialog ────────────────────────────────

    @Test
    fun topBarTrash_opensTheDialog_andCancelKeepsEverything() {
        launchHistoryScreen()
        waitForText("WP0ZZZ99ZTS392124")

        composeRule.onNodeWithContentDescription(string(R.string.history_cd_delete_all)).performClick()

        composeRule.onNodeWithText(string(R.string.history_delete_all_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.history_delete_all_message)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()

        waitForTextToDisappear(string(R.string.history_delete_all_title))
        composeRule.onNodeWithText("WP0ZZZ99ZTS392124").assertIsDisplayed()
        coVerify(exactly = 0) { sessionRepository.deleteSessions(any()) }
    }

    @Test
    fun topBarTrash_confirmHidesEveryFinishedTrip_andOffersUndo() {
        runBlocking {
            testDb.sessionDao().insert(SessionEntity(startTime = insertedStartTime - 5 * 60_000L, endTime = insertedStartTime, vehicleVin = "1HGBH41JXMN109186"))
        }
        launchHistoryScreen()
        waitForText("1HGBH41JXMN109186")

        composeRule.onNodeWithContentDescription(string(R.string.history_cd_delete_all)).performClick()
        composeRule.onNodeWithText(string(R.string.delete)).performClick()

        waitForTextToDisappear("WP0ZZZ99ZTS392124")
        waitForTextToDisappear("1HGBH41JXMN109186")
        waitForText(deletedMessage(2))
        // Everything is hidden: the empty state shows, the top-bar trash is disabled.
        composeRule.onNodeWithText(string(R.string.history_empty)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.history_cd_delete_all)).assertIsNotEnabled()
        coVerify(exactly = 0) { sessionRepository.deleteSessions(any()) }

        composeRule.onNodeWithText(string(R.string.undo)).performClick()
        waitForText("WP0ZZZ99ZTS392124")
        waitForText("1HGBH41JXMN109186")
    }

    @Test
    fun topBarTrash_isDisabled_whenThereIsNothingToDelete() {
        runBlocking { testDb.sessionDao().delete(SessionEntity(id = insertedSessionId, startTime = 0)) }

        launchHistoryScreen()
        waitForText(string(R.string.history_empty))

        composeRule.onNodeWithContentDescription(string(R.string.history_cd_delete_all)).assertIsNotEnabled()
    }

    @Test
    fun topBarTrash_isEnabled_whenAFinishedTripExists() {
        launchHistoryScreen()
        waitForText("WP0ZZZ99ZTS392124")

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithContentDescription(string(R.string.history_cd_delete_all)).assertIsEnabled()
            }.isSuccess
        }
    }

    // ── the trip that is being recorded ───────────────────────────────────────

    @Test
    fun theTripThatIsBeingRecorded_hasABadge_noTrashButton_cannotBeSwiped_andSurvivesDeleteAll() {
        val recordingId = runBlocking { sessionRepository.startSession("5YJSA1E26HF000337") }
        launchHistoryScreen()
        waitForText("5YJSA1E26HF000337")

        // Badge instead of the trash button: only the finished trip has one.
        composeRule.onNodeWithText(string(R.string.history_recording_active)).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(string(R.string.history_cd_delete)).assertCountEquals(1)

        // A swipe on the recording does nothing.
        composeRule.onNodeWithTag(historyRowTestTag(recordingId)).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("5YJSA1E26HF000337").assertIsDisplayed()

        // "Delete all" takes the finished trip and leaves the recording alone.
        composeRule.onNodeWithContentDescription(string(R.string.history_cd_delete_all)).performClick()
        composeRule.onNodeWithText(string(R.string.delete)).performClick()
        waitForTextToDisappear("WP0ZZZ99ZTS392124")
        composeRule.onNodeWithText("5YJSA1E26HF000337").assertIsDisplayed()
        waitForText(deletedMessage(1))
    }

    @Test
    fun topBarTrash_isDisabled_whenOnlyTheRecordingExists() {
        runBlocking {
            testDb.sessionDao().delete(SessionEntity(id = insertedSessionId, startTime = 0))
            sessionRepository.startSession("5YJSA1E26HF000337")
        }

        launchHistoryScreen()
        waitForText("5YJSA1E26HF000337")

        composeRule.onNodeWithContentDescription(string(R.string.history_cd_delete_all)).assertIsNotEnabled()
    }
}
