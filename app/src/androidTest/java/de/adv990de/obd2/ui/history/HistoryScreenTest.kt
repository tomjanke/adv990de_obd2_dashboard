package de.adv990de.obd2.ui.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
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
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.obd.dtcDescriptionRes
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.ui.formatDateTime
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
    val obdRepository: ObdRepository = idleObdRepository()

    /** Set by [setup] — the seeded row's generated id/startTime, reused by several tests below. */
    private var insertedSessionId: Long = 0
    private var insertedStartTime: Long = 0


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
        composeRule.onNodeWithText(formatDateTime(rawStartTime)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.history_vin_unknown)).assertIsDisplayed()
        composeRule.onNodeWithText("NO DATA").assertDoesNotExist()
        composeRule.onNodeWithText("WP0ZZZ99ZTS392124").assertIsDisplayed()
    }

    @Test
    fun sessionRow_showsFormattedDateAndVin_orUnknownVinFallback_whenVinMissing() {
        // Distinct from insertedStartTime by 5 minutes so the two rows never collide on the same
        // minute bucket (the format has no seconds).
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
        composeRule.onNodeWithText(formatDateTime(insertedStartTime)).assertIsDisplayed()
        composeRule.onNodeWithText("WP0ZZZ99ZTS392124").assertIsDisplayed()

        // Row without a VIN falls back to the "unknown VIN" string.
        composeRule.onNodeWithText(formatDateTime(secondStartTime)).assertIsDisplayed()
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

    // ── the fault codes of a trip: badge in the list, details on a tap ────────
    // Die Fahrt aus setup() hat dtcCodes = null ("nicht erfasst"): so sehen aeltere Fahrten aus.

    private fun insertTrip(minutesAgo: Long, vin: String, dtcCodes: String?) = runBlocking {
        val start = insertedStartTime - minutesAgo * 60_000L
        testDb.sessionDao().insert(SessionEntity(startTime = start, endTime = start + 60_000L, vehicleVin = vin, dtcCodes = dtcCodes))
    }

    private fun badgeText(count: Int) = composeRule.activity.resources.getQuantityString(R.plurals.history_dtc_badge, count, count)

    private fun waitForDetails() = composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodesWithTag(SESSION_DETAIL_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForDetailsToClose() = composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodesWithTag(SESSION_DETAIL_TEST_TAG).fetchSemanticsNodes().isEmpty()
    }

    private fun closeDetails() {
        composeRule.onNode(hasText(string(R.string.close)) and hasClickAction()).performClick()
        waitForDetailsToClose()
    }

    @Test
    fun aTripWithFaultCodes_showsABadgeWithTheirCount() {
        insertTrip(5, "1HGBH41JXMN109186", "P0301,P0420?")

        launchHistoryScreen()
        waitForText("1HGBH41JXMN109186")

        composeRule.onNodeWithText(badgeText(2)).assertIsDisplayed()
    }

    @Test
    fun tripsThatFoundNoCodes_orWereNotRecorded_haveNoBadge() {
        insertTrip(5, "1HGBH41JXMN109186", "") // gelesen, nichts gefunden; die Fahrt aus setup(): nicht erfasst

        launchHistoryScreen()
        waitForText("1HGBH41JXMN109186")
        waitForText("WP0ZZZ99ZTS392124")

        for (count in 1..3) composeRule.onAllNodesWithText(badgeText(count)).assertCountEquals(0)
    }

    @Test
    fun tappingATrip_opensItsDetails_withTheCodesAndTheirDescriptions() {
        insertTrip(5, "1HGBH41JXMN109186", "P0301,P0420?")
        launchHistoryScreen()
        waitForText("1HGBH41JXMN109186")

        composeRule.onNodeWithText("1HGBH41JXMN109186").performClick()

        waitForDetails()
        // Der Beginn der Fahrt ist der Titel des Dialogs: er steht zweimal da, in der Zeile und im Dialog.
        composeRule.onAllNodesWithText(formatDateTime(insertedStartTime - 5 * 60_000L)).assertCountEquals(2)
        // Ende und FIN stehen in einer Zeile (die Fahrt dauerte 60 s).
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.history_detail_meta, formatDateTime(insertedStartTime - 5 * 60_000L + 60_000L), "1HGBH41JXMN109186"),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.history_detail_dtcs_heading).uppercase()).assertIsDisplayed()
        composeRule.onNodeWithText("P0301").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(dtcDescriptionRes("P0301"))).assertIsDisplayed()
        composeRule.onNodeWithText("P0420").assertIsDisplayed()
        // Nur P0420 ist ausstehend (Modus 07): genau ein "Ausstehend".
        composeRule.onAllNodesWithText(string(R.string.dtc_badge_pending)).assertCountEquals(1)

        closeDetails()
    }

    @Test
    fun theDetails_sayWhetherTheReadFoundNothing_orTheCodesWereNotRecorded() {
        insertTrip(5, "1HGBH41JXMN109186", "")
        launchHistoryScreen()
        waitForText("1HGBH41JXMN109186")
        waitForText("WP0ZZZ99ZTS392124")

        composeRule.onNodeWithText("1HGBH41JXMN109186").performClick()
        waitForText(string(R.string.history_detail_dtcs_none))
        composeRule.onAllNodesWithText(string(R.string.history_detail_dtcs_unknown)).assertCountEquals(0)
        closeDetails()

        composeRule.onNodeWithText("WP0ZZZ99ZTS392124").performClick()
        waitForText(string(R.string.history_detail_dtcs_unknown))
        composeRule.onAllNodesWithText(string(R.string.history_detail_dtcs_none)).assertCountEquals(0)
    }

    @Test
    fun theDetailsOfTheTripThatIsBeingRecorded_showItAsRunning_andItsCodes() {
        runBlocking { sessionRepository.startSession("5YJSA1E26HF000337", listOf(DtcEntry("P0171", true))) }
        launchHistoryScreen()
        waitForText("5YJSA1E26HF000337")

        composeRule.onNodeWithText("5YJSA1E26HF000337").performClick()

        waitForDetails()
        // "Aufzeichnung läuft": das Zeichen der Zeile, und im Detail steht sie als Ende der Fahrt (Ende und FIN in einer Zeile).
        composeRule.onAllNodesWithText(string(R.string.history_recording_active)).assertCountEquals(1)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.history_detail_meta, string(R.string.history_recording_active), "5YJSA1E26HF000337"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("P0171").assertIsDisplayed()
    }

    @Test
    fun theDetailsOfATripWithoutAVin_sayItOnce_insteadOfRepeatingTheLabel() {
        // Ein Motorrad, das keine FIN meldet, ist der Normalfall: "FIN: FIN unbekannt" waere in jedem Dialog zu lesen.
        runBlocking {
            testDb.sessionDao().insert(SessionEntity(startTime = insertedStartTime - 10 * 60_000L, endTime = insertedStartTime - 9 * 60_000L, vehicleVin = null))
        }
        launchHistoryScreen()
        waitForText(string(R.string.history_vin_unknown))

        composeRule.onNodeWithText(string(R.string.history_vin_unknown)).performClick()
        waitForDetails()

        // Im Dialog steht der Platzhalter genau einmal in der Metazeile (dazu die Zeile in der Liste), nie hinter einer Beschriftung.
        composeRule.onAllNodesWithText(string(R.string.history_vin_unknown), substring = true).assertCountEquals(2)
        composeRule.onAllNodesWithText(": " + string(R.string.history_vin_unknown), substring = true).assertCountEquals(0)
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.history_detail_meta_no_vin, formatDateTime(insertedStartTime - 9 * 60_000L)),
        ).assertIsDisplayed()
    }

    @Test
    fun theCloseButtonOfTheDetails_isAtLeast56dpHigh() {
        launchHistoryScreen()
        waitForText("WP0ZZZ99ZTS392124")
        composeRule.onNodeWithText("WP0ZZZ99ZTS392124").performClick()
        waitForDetails()

        composeRule.onNode(hasText(string(R.string.close)) and hasClickAction()).assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun theDetails_areAsWideAsTheWindowAllows_inEveryOrientation() {
        launchHistoryScreen()
        waitForText("WP0ZZZ99ZTS392124")
        composeRule.onNodeWithText("WP0ZZZ99ZTS392124").performClick()
        waitForDetails()

        // Erwartung unabhaengig von der Rechnung im Produktivcode, aus der Konfiguration des Geraets: 400 dp, in einem schmalen Fenster (Hochformat)
        // der Platz abzueglich der Raender (96 dp). Das Plattformfenster eines Dialogs ist im Querformat nur rund 320 dp breit — ohne die Mindestbreite
        // bricht die Beschreibung eines Codes auf mehrere Zeilen.
        val windowWidthDp = composeRule.activity.resources.configuration.screenWidthDp
        val expected = minOf(400, windowWidthDp - 96).coerceAtLeast(0).dp
        composeRule.onNodeWithTag(SESSION_DETAIL_TEST_TAG).assertWidthIsAtLeast(expected)
    }

    @Test
    fun theOpenDetails_surviveARestoreOfTheSavedState() {
        // Eine Drehung baut die Oberflaeche neu auf; der geoeffnete Dialog gehoert zur Seite (rememberSaveable).
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { HistoryScreen(navController = rememberNavController()) }
        composeRule.waitForIdle()
        waitForText("WP0ZZZ99ZTS392124")
        composeRule.onNodeWithText("WP0ZZZ99ZTS392124").performClick()
        waitForDetails()

        restoration.emulateSavedInstanceStateRestore()

        waitForDetails()
    }

    // ── alle Fahrten in EINER CSV: Teilen oder Speichern ──────────────────────
    // Der Systemdialog zum Speichern und das Teilen-Menue lassen sich im Test nicht bedienen (sie erschienen vor der Test-App):
    // "Teilen" laeuft deshalb gegen ein Repository, dessen Export einen Fehler wirft; das Schreiben ins Ziel prueft SessionRepositoryDeviceTest.

    private fun exportAllDescription() = string(R.string.history_cd_export_all)

    private fun waitForExportAllEnabled() = composeRule.waitUntil(timeoutMillis = 5_000) {
        runCatching { composeRule.onNodeWithContentDescription(exportAllDescription()).assertIsEnabled() }.isSuccess
    }

    private fun exportAllMessage(count: Int) =
        composeRule.activity.resources.getQuantityString(R.plurals.history_export_all_message, count, count)

    @Test
    fun theExportAllButton_isEnabled_whenAFinishedTripExists() {
        launchHistoryScreen()

        waitForExportAllEnabled()
    }

    @Test
    fun theExportAllButton_isDisabled_whenThereIsNothingFinished_onlyTheRecording() {
        runBlocking {
            testDb.sessionDao().delete(SessionEntity(id = insertedSessionId, startTime = 0))
            sessionRepository.startSession("5YJSA1E26HF000337")
        }
        launchHistoryScreen()
        waitForText("5YJSA1E26HF000337")

        composeRule.onNodeWithContentDescription(exportAllDescription()).assertIsNotEnabled()
    }

    @Test
    fun theExportAllDialog_announcesTheTripCount_andOffersSaveShareAndCancel_eachAtLeast56dpHigh() {
        insertTrip(5, "1HGBH41JXMN109186", dtcCodes = null)
        runBlocking { sessionRepository.startSession("5YJSA1E26HF000337") } // die laufende Aufzeichnung zaehlt nicht mit
        launchHistoryScreen()
        waitForExportAllEnabled()
        waitForText("5YJSA1E26HF000337")

        composeRule.onNodeWithContentDescription(exportAllDescription()).performClick()

        waitForText(exportAllMessage(2))
        composeRule.onNodeWithText(string(R.string.history_export_all_title)).assertIsDisplayed()
        composeRule.onNodeWithTag(EXPORT_ALL_SAVE_TEST_TAG).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithTag(EXPORT_ALL_SHARE_TEST_TAG).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        composeRule.onNode(hasText(string(R.string.cancel)) and hasClickAction()).assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithText(string(R.string.history_export_save)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.history_export_share)).assertIsDisplayed()
    }

    @Test
    fun cancellingTheExportAllDialog_closesIt_andExportsNothing() {
        launchHistoryScreen()
        waitForExportAllEnabled()
        composeRule.onNodeWithContentDescription(exportAllDescription()).performClick()
        waitForText(exportAllMessage(1))

        composeRule.onNode(hasText(string(R.string.cancel)) and hasClickAction()).performClick()

        waitForTextToDisappear(exportAllMessage(1))
        coVerify(exactly = 0) { sessionRepository.exportSessionsToCsv(any()) }
    }

    @Test
    fun share_exportsTheFinishedTripsIntoOneFile_closesTheDialog_andShowsAFailureAsASnackbar() {
        insertTrip(5, "1HGBH41JXMN109186", dtcCodes = null)
        coEvery { sessionRepository.exportSessionsToCsv(any()) } throws RuntimeException("boom")
        launchHistoryScreen()
        waitForExportAllEnabled()
        composeRule.onNodeWithContentDescription(exportAllDescription()).performClick()
        waitForText(exportAllMessage(2))

        composeRule.onNodeWithTag(EXPORT_ALL_SHARE_TEST_TAG).performClick()

        waitForText(composeRule.activity.getString(R.string.history_export_error_prefix, "boom"))
        waitForTextToDisappear(exportAllMessage(2))
        // Beide Fahrten in einem Aufruf, die aeltere zuerst (die der Test selbst angelegt hat, ist die juengere).
        coVerify(exactly = 1) { sessionRepository.exportSessionsToCsv(match { it.size == 2 && it == it.sorted() }) }
    }
}
