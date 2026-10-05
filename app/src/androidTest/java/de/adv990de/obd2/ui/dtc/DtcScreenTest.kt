package de.adv990de.obd2.ui.dtc

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.db.DtcHistoryDao
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.ui.navigation.Screen
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Compose UI tests for [DtcScreen].
 *
 * Strategy:
 * - [TestAppModule] (TestInstallIn, replaces AppModule) provides the infrastructure bindings
 *   (BluetoothAdapter, BluetoothDeviceScanner, DataStore, the app-wide scope, in-memory
 *   AppDatabase and its DAOs). The REAL `DtcHistoryRepository` therefore runs against that in-memory
 *   database, and the tests seed and read it through the injected [DtcHistoryDao].
 * - [ObdRepository] is supplied via @BindValue so that its suspend fun [readDtcs] can be
 *   stubbed per test with [coEvery] before [setContent] is called. [connected] drives
 *   `isConnectedFlow`: [DtcViewModel] reads nothing without a connection, so the live-list tests run
 *   connected, the "Nicht verbunden" tests flip it.
 * - [DtcViewModel] calls [refresh] via LaunchedEffect immediately after composition;
 *   the mock must therefore be configured before [setContent].
 * - `isMovingFlow` is stubbed because other @HiltViewModels in the component graph
 *   (DriveStateViewModel) subscribe to it eagerly.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DtcScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    private val connected = MutableStateFlow(true)

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns connected
        every { userDisconnectCount } returns MutableStateFlow(0)
        every { isMovingFlow } returns MutableStateFlow(false)
        coEvery { readDtcs() } returns emptyList()
    }

    @Inject
    lateinit var dtcHistoryDao: DtcHistoryDao

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    @Before
    fun setup() {
        hiltRule.inject()
    }

    // --- helpers ---

    private fun launchDtcScreen() {
        composeRule.setContent {
            val navController = rememberNavController()
            DtcScreen(navController = navController)
        }
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun waitForText(text: String) = composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTextToDisappear(text: String) = composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    private fun showHistory() {
        composeRule.onNodeWithText(string(R.string.dtc_tab_history)).performClick()
    }

    private fun record(code: String, now: Long, pending: Boolean = false) =
        runBlocking { dtcHistoryDao.recordSeen(code, now, pending) }

    private fun deletedMessage(count: Int) =
        composeRule.activity.resources.getQuantityString(R.plurals.dtc_history_deleted, count, count)

    // --- the live list (as before) ---

    @Test
    fun dtcScreen_showsTitle() {
        launchDtcScreen()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dtc_title))
            .assertIsDisplayed()
    }

    @Test
    fun dtcScreen_rendersWithoutCrash_whenNoDtcs() {
        // readDtcs() already stubbed to return emptyList() in @BindValue initialiser.
        launchDtcScreen()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dtc_title))
            .assertIsDisplayed()
    }

    @Test
    fun dtcScreen_showsAllClear_whenConnectedWithoutDtcs() {
        launchDtcScreen()
        waitForText(string(R.string.dtc_empty))
        composeRule.onNodeWithText(string(R.string.dtc_empty)).assertIsDisplayed()
    }

    @Test
    fun dtcScreen_showsFaultCode_whenDtcListNonEmpty() {
        coEvery { obdRepository.readDtcs() } returns listOf(
            DtcEntry(code = "P0300", description = "Random Misfire", isPending = false)
        )
        launchDtcScreen()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText("P0300")
            .assertIsDisplayed()
    }

    @Test
    fun dtcScreen_showsErrorMessage_whenReadDtcsFails() {
        // DtcViewModel.refresh() catches any non-cancellation Exception and maps it through
        // ObdErrorMapper; an unknown exception keeps its technical message, so the mock's message
        // is exactly what must appear.
        coEvery { obdRepository.readDtcs() } throws RuntimeException("Connection lost")
        launchDtcScreen()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText("Connection lost")
            .assertIsDisplayed()
    }

    @Test
    fun dtcScreen_showsALocalizedReason_forAKnownAdapterFailure() {
        coEvery { obdRepository.readDtcs() } throws ObdException.Timeout("03")
        launchDtcScreen()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.obd_err_timeout))
            .assertIsDisplayed()
    }

    // --- B20: the error state offers a retry, and the "no fault codes" state can be pulled to refresh ---

    @Test
    fun dtcScreen_errorState_offersARetryButton_thatReadsAgain() {
        coEvery { obdRepository.readDtcs() } throws RuntimeException("boom") andThen
            listOf(DtcEntry(code = "P0300", description = "Random Misfire", isPending = false))
        launchDtcScreen()
        waitForText(string(R.string.retry))

        composeRule.onNodeWithText(string(R.string.retry)).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithText(string(R.string.retry)).performClick()

        waitForText("P0300")
        composeRule.onAllNodesWithText(string(R.string.retry)).assertCountEquals(0)
    }

    @Test
    fun dtcScreen_noFaultCodesState_canBePulledToRefresh() {
        // Default stub: readDtcs() returns an empty list -> the "no fault codes" state.
        launchDtcScreen()
        waitForText(string(R.string.dtc_empty))
        coVerify(timeout = 5_000, exactly = 1) { obdRepository.readDtcs() }

        composeRule.onNodeWithTag(DTC_PULL_AREA_TEST_TAG).performTouchInput { swipeDown() }

        coVerify(timeout = 5_000, atLeast = 2) { obdRepository.readDtcs() }
    }

    @Test
    fun dtcScreen_neverClaimsNoFaultCodes_whenTheFailureHasNoMessage() {
        // Before B18 the error was `e.message` = null, which rendered as the green "no fault codes".
        coEvery { obdRepository.readDtcs() } throws RuntimeException()
        launchDtcScreen()
        composeRule.waitForIdle()
        composeRule
            .onAllNodesWithText(composeRule.activity.getString(R.string.dtc_empty))
            .assertCountEquals(0)
        composeRule.onNodeWithText("RuntimeException").assertIsDisplayed()
    }

    @Test
    fun dtcScreen_showsPendingBadge_forPendingDtc() {
        coEvery { obdRepository.readDtcs() } returns listOf(
            DtcEntry(code = "P0301", description = "Cylinder 1 Misfire", isPending = true)
        )
        launchDtcScreen()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dtc_badge_pending))
            .assertIsDisplayed()
    }

    @Test
    fun dtcScreen_navigatesToFreezeFrame_whenLinkClicked() {
        // The freeze-frame link only renders once the DTC list is non-empty.
        coEvery { obdRepository.readDtcs() } returns listOf(
            DtcEntry(code = "P0300", description = "Random Misfire", isPending = false)
        )
        // DtcScreen navigates via the real NavController it is given (navController.navigate(...)),
        // not a callback lambda, so a real NavHost with both routes is needed here: navigating
        // requires a graph to have been set (rememberNavController() alone has none), and the
        // target route must resolve to a registered destination. The freeze_frame destination is
        // a bare marker Text rather than the real FreezeFrameScreen, keeping this test focused on
        // DtcScreen's own navigation call instead of FreezeFrameScreen's unrelated dependencies.
        val freezeFrameMarker = "FREEZE_FRAME_DESTINATION_MARKER"
        composeRule.setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = Screen.Dtc.route) {
                composable(Screen.Dtc.route) { DtcScreen(navController = navController) }
                composable(Screen.FreezeFrame.route) { Text(freezeFrameMarker) }
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dtc_freeze_frame_link))
            .performClick()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(freezeFrameMarker)
            .assertIsDisplayed()
    }

    // --- not connected ---

    @Test
    fun currentView_saysNotConnected_insteadOfAWrongAllClear() {
        connected.value = false
        launchDtcScreen()

        waitForText(string(R.string.not_connected))

        composeRule.onNodeWithText(string(R.string.not_connected)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.dtc_empty)).assertCountEquals(0)
    }

    // --- the switch Aktuell | Historie ---

    @Test
    fun theSwitchShowsBothViews_withGloveFriendlySegments() {
        launchDtcScreen()

        composeRule.onNodeWithText(string(R.string.dtc_tab_current)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.dtc_tab_history)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.dtc_tab_current)).assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithText(string(R.string.dtc_tab_history)).assertHeightIsAtLeast(56.dp)
    }

    // --- recording ---

    @Test
    fun theLiveCodesAreRecordedInTheHistory() {
        coEvery { obdRepository.readDtcs() } returns listOf(
            DtcEntry(code = "P0300", description = "Random Misfire", isPending = true)
        )
        launchDtcScreen()
        waitForText("P0300")

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { dtcHistoryDao.observeAll().first() }.isNotEmpty()
        }
        val row = runBlocking { dtcHistoryDao.observeAll().first() }.single()
        assertEquals("P0300", row.code)
        assertEquals(true, row.lastWasPending)
    }

    // --- the history view ---

    @Test
    fun historyView_listsRecordedCodes_withDescriptionDatesAndBadges() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val firstSeenP0301 = now - 3 * day
        val seenP0420 = now - 2 * day
        record("P0301", firstSeenP0301)
        record("P0420", seenP0420, pending = true)
        // The live read re-sights P0301 the moment the page opens, so its "last seen" becomes TODAY —
        // the intended meaning of "last seen" (latest sighting). P0420 is not in the live read.
        coEvery { obdRepository.readDtcs() } returns listOf(DtcEntry("P0301", "Cylinder 1 Misfire Detected", false))
        launchDtcScreen()
        waitForText(string(R.string.dtc_tab_history))

        showHistory()

        fun dates(first: Long, last: Long) = composeRule.activity.getString(
            R.string.dtc_history_dates, dateFormat.format(Date(first)), dateFormat.format(Date(last))
        )
        waitForText("P0420")
        // Wait for the text instead of asserting at once: the recording of the live read re-composes the row.
        waitForText(dates(firstSeenP0301, now))
        composeRule.onNodeWithText("P0301").assertIsDisplayed()
        composeRule.onNodeWithText("Cylinder 1 Misfire Detected").assertIsDisplayed()
        composeRule.onNodeWithText("Catalyst System Efficiency Below Threshold (Bank 1)").assertIsDisplayed()
        composeRule.onNodeWithText(dates(firstSeenP0301, now)).assertIsDisplayed()
        composeRule.onNodeWithText(dates(seenP0420, seenP0420)).assertIsDisplayed()
        // P0301 is in the last live read (active), P0420 was last seen as pending.
        composeRule.onNodeWithText(string(R.string.dtc_badge_active)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.dtc_badge_pending)).assertIsDisplayed()
    }

    @Test
    fun historyView_showsTheEmptyState_whenNothingWasRecorded() {
        launchDtcScreen()

        showHistory()

        waitForText(string(R.string.dtc_history_empty))
        composeRule.onNodeWithText(string(R.string.dtc_history_empty)).assertIsDisplayed()
    }

    @Test
    fun historyView_works_withoutAConnection_andNothingIsActive() {
        connected.value = false
        record("P0301", System.currentTimeMillis())
        launchDtcScreen()

        showHistory()

        waitForText("P0301")
        composeRule.onAllNodesWithText(string(R.string.dtc_badge_active)).assertCountEquals(0)
    }

    @Test
    fun historyView_rowTrash_hidesTheEntry_andOffersUndo_withoutAskingFirst() {
        record("P0301", System.currentTimeMillis())
        record("P0420", System.currentTimeMillis() - 1_000L)
        launchDtcScreen()
        showHistory()
        waitForText("P0301")

        composeRule.onAllNodesWithContentDescription(string(R.string.dtc_history_cd_delete)).get(0).performClick()

        waitForTextToDisappear("P0301")
        composeRule.onNodeWithText("P0420").assertIsDisplayed()
        waitForText(deletedMessage(1))
        composeRule.onNodeWithText(string(R.string.undo)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.dtc_history_delete_all_title)).assertCountEquals(0)
        // Not deleted yet: the undo window is still open.
        assertEquals(2, runBlocking { dtcHistoryDao.observeAll().first() }.size)

        composeRule.onNodeWithText(string(R.string.undo)).performClick()
        waitForText("P0301")
    }

    @Test
    fun historyView_swipeLeft_hidesTheEntry_andOffersUndo() {
        record("P0301", System.currentTimeMillis())
        launchDtcScreen()
        showHistory()
        waitForText("P0301")

        composeRule.onNodeWithTag(dtcHistoryRowTestTag("P0301")).performTouchInput { swipeLeft() }

        waitForTextToDisappear("P0301")
        waitForText(deletedMessage(1))
        assertEquals(1, runBlocking { dtcHistoryDao.observeAll().first() }.size)
    }

    @Test
    fun topBarTrash_existsOnlyInTheHistoryView() {
        record("P0301", System.currentTimeMillis())
        launchDtcScreen()
        waitForText(string(R.string.dtc_tab_history))

        composeRule.onAllNodesWithContentDescription(string(R.string.dtc_history_cd_delete_all)).assertCountEquals(0)

        showHistory()

        waitForText("P0301")
        composeRule.onNodeWithContentDescription(string(R.string.dtc_history_cd_delete_all)).assertIsDisplayed()
    }

    @Test
    fun topBarTrash_asksFirst_cancelKeepsEverything_confirmHidesAllAndOffersUndo() {
        record("P0301", System.currentTimeMillis())
        record("P0420", System.currentTimeMillis() - 1_000L)
        launchDtcScreen()
        showHistory()
        waitForText("P0301")

        composeRule.onNodeWithContentDescription(string(R.string.dtc_history_cd_delete_all)).performClick()
        composeRule.onNodeWithText(string(R.string.dtc_history_delete_all_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.dtc_history_delete_all_message)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        waitForTextToDisappear(string(R.string.dtc_history_delete_all_title))
        composeRule.onNodeWithText("P0301").assertIsDisplayed()

        composeRule.onNodeWithContentDescription(string(R.string.dtc_history_cd_delete_all)).performClick()
        composeRule.onNodeWithText(string(R.string.delete)).performClick()

        waitForTextToDisappear("P0301")
        waitForTextToDisappear("P0420")
        waitForText(deletedMessage(2))
        composeRule.onNodeWithText(string(R.string.dtc_history_empty)).assertIsDisplayed()
        assertEquals("nothing is deleted before the undo window ends", 2, runBlocking { dtcHistoryDao.observeAll().first() }.size)

        composeRule.onNodeWithText(string(R.string.undo)).performClick()
        waitForText("P0301")
        waitForText("P0420")
    }
}
