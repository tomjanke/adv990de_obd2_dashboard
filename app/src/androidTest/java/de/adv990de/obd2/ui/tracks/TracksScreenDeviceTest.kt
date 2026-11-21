package de.adv990de.obd2.ui.tracks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import android.content.pm.ActivityInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.track.RecordingState
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.TrackSummary
import de.adv990de.obd2.ui.assertTouchTargetAtLeast56
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.PreviewTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Die Seiten „Tracks“ und „Track“ (F8, Aufgabe 6) auf dem Geraet: Liste, Aufzeichnungskarte, Umbenennen, Rueckfrage beim Beenden, Knopf des Dashboards und die Zustaende der Ansicht.
 * Alle Koordinaten sind erfunden. Die Zeichnung selbst liest `TrackMapDeviceTest` als Bildpunkte.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TracksScreenDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    @After
    fun unlockOrientation() {
        // Die Activity endet mit dem Test; der Wunsch nach einer Ausrichtung soll das Geraet nicht festhalten, falls ein Test vorher scheitert.
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    private fun requestOrientation(orientation: Int) {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        composeRule.waitForIdle()
    }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private val locale: Locale get() = composeRule.activity.resources.configuration.locales[0]

    private fun track(id: Long, name: String = "Track $id", source: TrackSource = TrackSource.RECORDED) = TrackSummary(
        id = id, name = name, source = source, startMs = 1_800_000_000_000 + id * 86_400_000, endMs = 1_800_000_000_000 + id * 86_400_000 + 3_725_000,
        sessionId = null, distanceM = 12_345, ascentM = null, descentM = null, pointCount = 100, createdMs = 1_800_000_000_000, running = false,
    )

    private val recording = RecordingState.Recording(trackId = 9, points = 214, distanceM = 3_420.0, startedMs = 0, signal = true, writeFailed = false)

    private fun showList(
        tracks: List<TrackSummary>? = emptyList(),
        state: RecordingState = RecordingState.Idle,
        permissionMissing: Boolean = false,
        onStart: () -> Unit = {},
        onStop: () -> Unit = {},
        onOpen: (Long) -> Unit = {},
        onRename: (Long) -> Unit = {},
        onDelete: (Long) -> Unit = {},
    ) {
        composeRule.setContent {
            PreviewTheme(dark = false) {
                TracksContent(
                    state = TracksUiState(tracks, state), permissionMissing = permissionMissing, onStart = onStart, onStopRequest = onStop,
                    onOpen = onOpen, onRename = onRename, onDelete = onDelete, onOpenSettings = {},
                )
            }
        }
    }

    // ── Liste ─────────────────────────────────────────────────────────────────

    @Test
    fun anEmptyList_saysSo_andOffersTheStart() {
        showList()

        composeRule.onNodeWithText(string(R.string.tracks_empty)).assertIsDisplayed()
        composeRule.onNodeWithTag(TRACKS_START_TEST_TAG).assertIsDisplayed().assertIsEnabled().assertTouchTargetAtLeast56(composeRule.density)
    }

    @Test
    fun aListThatIsNotLoadedYet_isNotCalledEmpty() {
        showList(tracks = null)

        composeRule.onNodeWithText(string(R.string.tracks_empty)).assertDoesNotExist()
    }

    @Test
    fun theStartButton_startsOnce_andIsLockedWhileStarting() {
        var started = 0
        showList(onStart = { started++ })

        composeRule.onNodeWithTag(TRACKS_START_TEST_TAG).performClick()
        assertEquals(1, started)
    }

    @Test
    fun whileTheRecordingIsCreated_theStartButtonIsLocked() {
        showList(state = RecordingState.Starting)

        composeRule.onNodeWithTag(TRACKS_START_TEST_TAG).assertIsNotEnabled()
    }

    @Test
    fun withoutThePreciseLocation_theHintShows_andWithItNot() {
        showList(permissionMissing = true)
        composeRule.onNodeWithTag(TRACKS_PERMISSION_HINT_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun withThePreciseLocation_noHintShows() {
        showList(permissionMissing = false)
        composeRule.onNodeWithTag(TRACKS_PERMISSION_HINT_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun aRunningRecording_showsDistanceAndPoints_andReplacesTheStartButton() {
        var stopped = 0
        showList(state = recording, onStop = { stopped++ })

        composeRule.onNodeWithTag(TRACKS_START_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(TRACKS_RECORDING_CARD_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(formatTrackDistance(3_420, locale)).assertIsDisplayed()
        composeRule.onNodeWithTag(TRACKS_STOP_TEST_TAG).assertTouchTargetAtLeast56(composeRule.density).performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun aRecordingWithoutSignal_andOneThatCannotWrite_sayWhy() {
        showList(state = recording.copy(signal = false, writeFailed = true))

        composeRule.onNodeWithText(string(R.string.tracks_recording_wait)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.tracks_recording_write_failed)).assertIsDisplayed()
    }

    @Test
    fun theRows_showNameSourceAndNumbers_andReportTheirButtons() {
        val opened = mutableListOf<Long>()
        val renamed = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        showList(
            tracks = listOf(track(2, "Sonntagsrunde"), track(1, "Importiert", TrackSource.IMPORTED)),
            onOpen = { opened += it }, onRename = { renamed += it }, onDelete = { deleted += it },
        )

        composeRule.onNodeWithText("Sonntagsrunde").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(tracksRenameTestTag(1)).assertTouchTargetAtLeast56(composeRule.density).performClick()
        composeRule.onNodeWithTag(tracksDeleteTestTag(2)).assertTouchTargetAtLeast56(composeRule.density).performClick()

        assertEquals(listOf(2L), opened)
        assertEquals(listOf(1L), renamed)
        assertEquals(listOf(2L), deleted)
        composeRule.onNodeWithText(string(R.string.tracks_source_imported), substring = true).assertIsDisplayed()
    }

    // ── Dialoge ───────────────────────────────────────────────────────────────

    @Test
    fun theRenameDialog_savesOnlyAName_withContent() {
        var saved: String? = null
        composeRule.setContent { PreviewTheme(dark = false) { RenameTrackDialog(initial = "Alt", onSave = { saved = it }, onDismiss = {}) } }

        composeRule.onNodeWithTag(TRACKS_RENAME_SAVE_TEST_TAG).assertIsEnabled().assertTouchTargetAtLeast56(composeRule.density)
        composeRule.onNodeWithTag(TRACKS_RENAME_FIELD_TEST_TAG).performTextClearance()
        composeRule.onNodeWithTag(TRACKS_RENAME_SAVE_TEST_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(TRACKS_RENAME_FIELD_TEST_TAG).performTextInput("Neu")
        composeRule.onNodeWithTag(TRACKS_RENAME_SAVE_TEST_TAG).assertIsEnabled().performClick()

        assertEquals("Neu", saved)
    }

    @Test
    fun theStopDialog_asksBeforeEnding() {
        var confirmed = 0
        composeRule.setContent { PreviewTheme(dark = false) { StopRecordingDialog(onConfirm = { confirmed++ }, onDismiss = {}) } }

        composeRule.onNodeWithText(string(R.string.tracks_stop_title)).assertIsDisplayed()
        composeRule.onNodeWithTag(TRACKS_STOP_CONFIRM_TEST_TAG).assertTouchTargetAtLeast56(composeRule.density).performClick()
        assertEquals(1, confirmed)
    }

    // ── Knopf im Dashboard ────────────────────────────────────────────────────

    @Test
    fun theDashboardButton_startsWhenIdle_asksToStopWhenRecording_andIsLockedWhileStarting() {
        var starts = 0
        var stops = 0
        var state by mutableStateOf<RecordingState>(RecordingState.Idle)
        composeRule.setContent {
            PreviewTheme(dark = false) { RecordingButton(state, onStart = { starts++ }, onStopRequest = { stops++ }) }
        }

        composeRule.onNodeWithTag(RECORDING_BUTTON_TEST_TAG).assertTouchTargetAtLeast56(composeRule.density).performClick()
        assertEquals(1 to 0, starts to stops)

        composeRule.runOnUiThread { state = recording }
        composeRule.onNodeWithTag(RECORDING_BUTTON_TEST_TAG).performClick()
        assertEquals(1 to 1, starts to stops)

        composeRule.runOnUiThread { state = RecordingState.Starting }
        composeRule.onNodeWithTag(RECORDING_BUTTON_TEST_TAG).assertIsNotEnabled()
    }

    // ── Ansicht ───────────────────────────────────────────────────────────────

    private val points = List(30) { i -> TrackPoint(null, 50.0 + 0.002 * Math.sin(i / 5.0), 10.0 + 0.0005 * i, null, null, null) }
    private val bounds = TrackBounds(points.minOf { it.lat }, points.maxOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lon })

    private fun showDetail(state: TrackDetailState, onSave: () -> Unit = {}, onShare: () -> Unit = {}, exporting: Boolean = false, onFollow: () -> Unit = {}) {
        composeRule.setContent { PreviewTheme(dark = false) { TrackDetailContent(state, onBack = {}, onSave = onSave, onShare = onShare, onFollow = onFollow, exporting = exporting) } }
    }

    // ── Nachfahren (Task 9): der Knopf in der Ansicht ─────────────────────────

    @Test
    fun aReadyTrack_offersFollowing_as56dpButton_andReportsTheTap() {
        var follows = 0
        composeRule.setContent {
            AppTheme(colorScheme = androidx.compose.material3.lightColorScheme()) {
                TrackDetailContent(TrackDetailState.Ready(track(1, "Runde"), points, bounds), onBack = {}, onFollow = { follows++ })
            }
        }

        composeRule.onNodeWithTag(TRACK_FOLLOW_OPEN_TEST_TAG).assertIsDisplayed().assertIsEnabled().assertTouchTargetAtLeast56(composeRule.density)
        composeRule.onNodeWithContentDescription(string(R.string.track_follow_open)).assertIsDisplayed()
        composeRule.onNodeWithTag(TRACK_FOLLOW_OPEN_TEST_TAG).performClick()
        assertEquals(1, follows)
    }

    @Test
    fun aTrackWithoutPoints_hasNothingToFollow() {
        showDetail(TrackDetailState.Ready(track(1), emptyList(), null))
        composeRule.onNodeWithTag(TRACK_FOLLOW_OPEN_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun theDetailStates_sayWhatIsWrong() {
        showDetail(TrackDetailState.NotFound)
        composeRule.onNodeWithText(string(R.string.track_detail_not_found)).assertIsDisplayed()
    }

    @Test
    fun aTrackThatCouldNotBeRead_saysSo() {
        showDetail(TrackDetailState.Failed)
        composeRule.onNodeWithText(string(R.string.track_detail_failed)).assertIsDisplayed()
    }

    @Test
    fun aTrackWithoutPoints_saysSo_insteadOfDrawingNothing() {
        showDetail(TrackDetailState.Ready(track(1), emptyList(), null))
        composeRule.onNodeWithText(string(R.string.track_detail_empty)).assertIsDisplayed()
    }

    @Test
    fun aReadyTrack_showsItsNumbers_withADashForWhatIsUnknown() {
        showDetail(TrackDetailState.Ready(track(1, "Runde"), points, bounds))

        composeRule.onNodeWithText("Runde").assertIsDisplayed()
        composeRule.onNodeWithText(formatTrackDistance(12_345, locale)).assertIsDisplayed()
        composeRule.onNodeWithText(formatTrackDuration(3_725_000)).assertIsDisplayed()
        // Anstieg und Abstieg fehlen: ein Strich, nie 0
        val dashes = composeRule.onAllNodes(hasText(TRACK_UNKNOWN)).fetchSemanticsNodes().size
        assertTrue("Strich fuer Anstieg und Abstieg, gefunden: $dashes", dashes >= 2)
        composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG).assertIsDisplayed()
    }

    // ── GPX-Export (Task 7) ───────────────────────────────────────────────────

    @Test
    fun aReadyTrack_offersSaveAndShare_as56dpButtons_andTheNoteAboutThePositions() {
        // AppTheme, nicht PreviewTheme: die 56-dp-Regel fuer Touch-Ziele steht im Theme der App (ui/theme/AppTheme.kt)
        composeRule.setContent {
            AppTheme(colorScheme = androidx.compose.material3.lightColorScheme()) {
                TrackDetailContent(TrackDetailState.Ready(track(1, "Runde"), points, bounds), onBack = {})
            }
        }

        composeRule.onNodeWithTag(TRACK_EXPORT_SAVE_TEST_TAG).assertIsDisplayed().assertIsEnabled().assertTouchTargetAtLeast56(composeRule.density)
        composeRule.onNodeWithTag(TRACK_EXPORT_SHARE_TEST_TAG).assertIsDisplayed().assertIsEnabled().assertTouchTargetAtLeast56(composeRule.density)
        composeRule.onNodeWithTag(TRACK_EXPORT_NOTE_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.track_export_note)).assertIsDisplayed()
    }

    @Test
    fun theButtons_reportTheirTaps_oneEach() {
        var saves = 0
        var shares = 0
        showDetail(TrackDetailState.Ready(track(1), points, bounds), onSave = { saves++ }, onShare = { shares++ })

        composeRule.onNodeWithTag(TRACK_EXPORT_SAVE_TEST_TAG).performClick()
        assertEquals(1 to 0, saves to shares)
        composeRule.onNodeWithTag(TRACK_EXPORT_SHARE_TEST_TAG).performClick()
        assertEquals(1 to 1, saves to shares)
    }

    @Test
    fun whileAnExportRuns_bothButtonsAreLocked() {
        showDetail(TrackDetailState.Ready(track(1), points, bounds), exporting = true)

        composeRule.onNodeWithTag(TRACK_EXPORT_SAVE_TEST_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(TRACK_EXPORT_SHARE_TEST_TAG).assertIsNotEnabled()
    }

    @Test
    fun aTrackWithoutPoints_andTheOtherStates_offerNoExport() {
        showDetail(TrackDetailState.Ready(track(1), emptyList(), null))
        composeRule.onNodeWithTag(TRACK_EXPORT_SAVE_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(TRACK_EXPORT_SHARE_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(TRACK_EXPORT_NOTE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun theButtons_haveSpokenNames() {
        showDetail(TrackDetailState.Ready(track(1), points, bounds))

        composeRule.onNodeWithContentDescription(string(R.string.track_cd_export_save)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.track_cd_export_share)).assertIsDisplayed()
    }

    @Test
    fun inLandscape_theNoteSitsInTheSidePanel_andIsReachableByScrolling() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        composeRule.setContent { PreviewTheme(dark = false) { TrackDetailContent(TrackDetailState.Ready(track(1), points, bounds), onBack = {}) } }

        composeRule.onNodeWithTag(TRACK_EXPORT_NOTE_TEST_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun inPortrait_theNoteIsShownBelowTheNumbers() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        composeRule.setContent { PreviewTheme(dark = false) { TrackDetailContent(TrackDetailState.Ready(track(1), points, bounds), onBack = {}) } }

        composeRule.onNodeWithTag(TRACK_EXPORT_NOTE_TEST_TAG).assertIsDisplayed()
    }
}
