package de.adv990de.obd2.ui.transfer

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ExportCounts
import de.adv990de.obd2.logbook.ImportPlan
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.ExportFormat
import de.adv990de.obd2.transfer.FileKind
import de.adv990de.obd2.transfer.PartOutcome
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.TransferPreview
import de.adv990de.obd2.transfer.TransferResult
import de.adv990de.obd2.transfer.UnreadableReason
import de.adv990de.obd2.tripimport.RejectedRow
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.assertTouchTargetAtLeast56
import de.adv990de.obd2.ui.theme.PreviewTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Die zustandslosen Inhalte der Seite „Daten sichern und wiederherstellen“ mit gesetzten Zustaenden — in beiden Ausrichtungen (die Seite scrollt, im Querformat ist die Hoehe knapp).
 * Der Rahmen mit ViewModel, Launchern und Navigation steht in `AppNavHostDeviceTest` (die Route) und `DataTransferViewModelTest` (der Zustand); die Systemdialoge sind nicht bedienbar.
 */
@RunWith(AndroidJUnit4::class)
class DataTransferScreenDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun unlockOrientation() {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    private fun requestOrientation(orientation: Int) {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        composeRule.waitForIdle()
    }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun plural(id: Int, n: Int) = composeRule.activity.resources.getQuantityString(id, n, n)

    private val counts = ExportCounts(trips = 61, logbook = 104, appLog = 312)
    private val toggled = mutableListOf<DataPart>()
    private val formats = mutableListOf<ExportFormat>()
    private var saves = 0
    private var shares = 0
    private var picks = 0
    private var confirms = 0
    private var dismisses = 0

    private val exportActions = ExportActions({ toggled += it }, { formats += it }, { saves++ }, { shares++ })
    private val importActions = ImportActions({ picks++ }, { toggled += it }, { confirms++ }, { dismisses++ })

    private fun exportUi(
        counts: ExportCounts? = this.counts,
        selection: Set<DataPart> = DataPart.DEFAULT_EXPORT,
        format: ExportFormat = ExportFormat.PACKAGE,
        csvAllowed: Boolean = false,
        canExport: Boolean = true,
    ) = ExportUi(counts, selection, format, csvAllowed, canExport)

    /** Die Seite scrollt wie in der App: im Test ein `verticalScroll`, damit jeder Knoten erreichbar ist. */
    private fun showExport(state: ExportUi, fontScale: Float = 1f, width: Int? = null) = composeRule.setContent {
        PreviewTheme(dark = false) {
            Scaffolded(fontScale, width) { Column { TransferModeSwitch(TransferMode.EXPORT, {}); DataTransferExportContent(state, exportActions) } }
        }
    }

    private fun showImport(state: TransferImportState, fontScale: Float = 1f, width: Int? = null) = composeRule.setContent {
        PreviewTheme(dark = false) { Scaffolded(fontScale, width) { DataTransferImportContent(state, importActions) } }
    }

    @androidx.compose.runtime.Composable
    private fun Scaffolded(fontScale: Float, width: Int?, content: @androidx.compose.runtime.Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            Box((if (width != null) Modifier.width(width.dp) else Modifier).verticalScroll(rememberScrollState())) { content() }
        }
    }

    private fun tag(t: String) = composeRule.onNodeWithTag(t)

    private fun SemanticsNodeInteraction.assertNotCutOff(): SemanticsNodeInteraction {
        val result = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val action = fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        assertTrue("der Knoten hat keinen Text", action != null && action(result))
        val layout = result.first()
        assertFalse("Text abgeschnitten (Breite)", layout.didOverflowWidth)
        assertFalse("Text abgeschnitten (Hoehe)", layout.didOverflowHeight)
        return this
    }

    // ── Export: Masse in beiden Ausrichtungen ────────────────────────────────

    private fun rowsAndControlsHaveTheRequiredSizes() {
        showExport(exportUi(selection = setOf(DataPart.LOGBOOK), format = ExportFormat.CSV, csvAllowed = true))
        val density = Density(composeRule.activity)
        for (part in listOf(DataPart.TRIPS, DataPart.LOGBOOK, DataPart.APP_LOG)) {
            tag(transferPartRowTestTag(part)).performScrollTo().assertHeightIsAtLeast(64.dp).assertTouchTargetAtLeast56(density)
        }
        for (control in listOf(TRANSFER_MODE_EXPORT_TEST_TAG, TRANSFER_MODE_IMPORT_TEST_TAG, TRANSFER_FORMAT_PACKAGE_TEST_TAG, TRANSFER_FORMAT_CSV_TEST_TAG, TRANSFER_SAVE_TEST_TAG, TRANSFER_SHARE_TEST_TAG)) {
            tag(control).performScrollTo().assertHeightIsAtLeast(56.dp).assertTouchTargetAtLeast56(density)
        }
    }

    @Test
    fun rowsAreAtLeast64dp_andControlsAtLeast56dp_inPortrait() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        rowsAndControlsHaveTheRequiredSizes()
    }

    @Test
    fun rowsAreAtLeast64dp_andControlsAtLeast56dp_inLandscape() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        rowsAndControlsHaveTheRequiredSizes()
    }

    // ── Export: Zeilen, Format, Hinweise, Knoepfe ────────────────────────────

    @Test
    fun aRowShowsNameAndCount_oneThatIsEmptySaysSo_andBeforeTheCountsItIsOnlyTheName() {
        showExport(exportUi(counts = ExportCounts(trips = 0, logbook = 104, appLog = 1)))

        composeRule.onNodeWithText(string(R.string.transfer_part_row, string(R.string.transfer_part_trips), string(R.string.transfer_part_empty))).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_part_row, string(R.string.transfer_part_logbook), plural(R.plurals.transfer_count_entries, 104))).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_part_row, string(R.string.transfer_part_app_log), plural(R.plurals.transfer_count_lines, 1))).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withoutCountsEveryRowIsJustItsName_andSelectable() {
        showExport(exportUi(counts = null))

        composeRule.onNodeWithText(string(R.string.transfer_part_trips)).assertIsDisplayed()
        tag(transferPartRowTestTag(DataPart.TRIPS)).assertIsEnabled()
    }

    @Test
    fun theBoxesShowTheSelection_andATapTogglesThatPart() {
        showExport(exportUi(selection = setOf(DataPart.TRIPS, DataPart.LOGBOOK)))

        tag(transferPartRowTestTag(DataPart.TRIPS)).assertIsOn()
        tag(transferPartRowTestTag(DataPart.LOGBOOK)).assertIsOn()
        tag(transferPartRowTestTag(DataPart.APP_LOG)).performScrollTo().assertIsOff()

        tag(transferPartRowTestTag(DataPart.APP_LOG)).performClick()
        tag(transferPartRowTestTag(DataPart.TRIPS)).performClick()
        assertEquals(listOf(DataPart.APP_LOG, DataPart.TRIPS), toggled)
    }

    @Test
    fun aRowWithoutEntriesIsGreyedOutAndDoesNothing() {
        showExport(exportUi(counts = ExportCounts(trips = 0, logbook = 104, appLog = 0), selection = setOf(DataPart.LOGBOOK)))

        tag(transferPartRowTestTag(DataPart.TRIPS)).assertIsNotEnabled().performClick()
        tag(transferPartRowTestTag(DataPart.APP_LOG)).performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(emptyList<DataPart>(), toggled)
    }

    @Test
    fun csvCannotBeChosenForSeveralKinds_andTheHintSaysWhy() {
        showExport(exportUi(selection = setOf(DataPart.TRIPS, DataPart.LOGBOOK), csvAllowed = false))

        tag(TRANSFER_FORMAT_CSV_TEST_TAG).performScrollTo().assertIsNotEnabled()
        tag(TRANSFER_FORMAT_PACKAGE_TEST_TAG).assertIsEnabled()
        tag(TRANSFER_CSV_HINT_TEST_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_format_csv_hint)).assertIsDisplayed()
    }

    @Test
    fun csvCanBeChosenForOneKind_noHint_andTheTapAsksForIt() {
        showExport(exportUi(selection = setOf(DataPart.LOGBOOK), format = ExportFormat.CSV, csvAllowed = true))

        tag(TRANSFER_FORMAT_CSV_TEST_TAG).performScrollTo().assertIsEnabled()
        composeRule.onAllNodesWithTag(TRANSFER_CSV_HINT_TEST_TAG).assertCountEquals(0)
        tag(TRANSFER_FORMAT_PACKAGE_TEST_TAG).performClick()
        tag(TRANSFER_FORMAT_CSV_TEST_TAG).performClick()
        assertEquals(listOf(ExportFormat.PACKAGE, ExportFormat.CSV), formats)
    }

    @Test
    fun theAppLogNoteShowsOnlyWithTheAppLogChosen() {
        showExport(exportUi(selection = setOf(DataPart.TRIPS)))
        composeRule.onAllNodesWithTag(TRANSFER_NOTE_APP_LOG_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun theAppLogNoteShowsWhenTheAppLogIsChosen() {
        showExport(exportUi(selection = setOf(DataPart.TRIPS, DataPart.APP_LOG)))
        tag(TRANSFER_NOTE_APP_LOG_TEST_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_note_app_log)).assertIsDisplayed()
    }

    @Test
    fun saveAndShareAreOffWithNothingToExport_andOtherwiseCallBack() {
        showExport(exportUi(selection = emptySet(), canExport = false))
        tag(TRANSFER_SAVE_TEST_TAG).performScrollTo().assertIsNotEnabled().performClick()
        tag(TRANSFER_SHARE_TEST_TAG).assertIsNotEnabled().performClick()
        assertEquals(0, saves + shares)
    }

    @Test
    fun saveAndShareCallBackWhenThereIsSomethingToExport() {
        showExport(exportUi())
        tag(TRANSFER_SAVE_TEST_TAG).performScrollTo().assertIsEnabled().performClick()
        tag(TRANSFER_SHARE_TEST_TAG).assertIsEnabled().performClick()
        assertEquals(1, saves)
        assertEquals(1, shares)
    }

    // ── Import ───────────────────────────────────────────────────────────────

    private val logbookNews = PartPlan.Logbook(ImportPlan(emptyList(), 0, emptyList(), 0))
    private val tripPlan = PartPlan.Trips(previewTripPlan(listOf(RejectedRow(17, "timestamp"), RejectedRow(23, "value")), rejectedCount = 2, duplicates = 4))
    private val logbookPlan = PartPlan.Logbook(previewLogbookPlan())

    private fun preview(
        parts: Map<DataPart, PartPlan>,
        selected: Set<DataPart> = parts.filterValues { it !is PartPlan.Unreadable }.keys,
        newer: Boolean = false,
        appLog: Boolean = false,
    ) = TransferImportState.Preview(TransferPreview(FileKind.PACKAGE, newer, appLog, parts), selected)

    @Test
    fun idleOffersTheFilePicker() {
        showImport(TransferImportState.Idle)

        composeRule.onNodeWithText(string(R.string.transfer_import_hint)).assertIsDisplayed()
        tag(TRANSFER_PICK_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, picks)
    }

    @Test
    fun workingShowsTheProgressAndNoButtons() {
        showImport(TransferImportState.Working)

        tag(TRANSFER_WORKING_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_working)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(TRANSFER_CONFIRM_TEST_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(TRANSFER_PICK_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun thePreviewShowsEveryPart_withTheNumbersAndTheRejectedRows() {
        showImport(preview(linkedMapOf(DataPart.LOGBOOK to logbookPlan, DataPart.TRIPS to tripPlan)))

        composeRule.onNodeWithText(plural(R.plurals.logbook_import_new, 98)).assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.logbook_import_rejected, 2)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.trip_import_new, 9)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_import_row, 17, string(R.string.trip_import_bad_value, "timestamp"))).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.trip_import_duplicates, 4)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aPartShowsItsBoxAsTheSelectionSays_andATapToggles() {
        showImport(preview(linkedMapOf(DataPart.LOGBOOK to logbookPlan, DataPart.TRIPS to tripPlan), selected = setOf(DataPart.LOGBOOK)))

        tag(transferImportPartTestTag(DataPart.LOGBOOK)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_part_logbook)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_part_trips)).performScrollTo().performClick()
        assertEquals(listOf(DataPart.TRIPS), toggled)
    }

    @Test
    fun anUnreadablePartShowsItsReason_andCannotBeChosen() {
        showImport(preview(linkedMapOf(DataPart.LOGBOOK to logbookPlan, DataPart.TRIPS to PartPlan.Unreadable(UnreadableReason.TOO_LARGE))))

        composeRule.onNodeWithText(string(R.string.transfer_part_unreadable_too_large)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_part_trips)).performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(emptyList<DataPart>(), toggled)
    }

    @Test
    fun confirmIsOffWithNothingChosen_andOtherwiseCallsBack_cancelAlways() {
        showImport(preview(linkedMapOf(DataPart.LOGBOOK to logbookPlan), selected = emptySet()))
        tag(TRANSFER_CONFIRM_TEST_TAG).performScrollTo().assertIsNotEnabled().performClick()
        tag(TRANSFER_CANCEL_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(0, confirms)
        assertEquals(1, dismisses)
    }

    @Test
    fun confirmCallsBackWithAChoice() {
        showImport(preview(linkedMapOf(DataPart.LOGBOOK to logbookPlan)))
        tag(TRANSFER_CONFIRM_TEST_TAG).performScrollTo().assertHeightIsAtLeast(56.dp).assertIsEnabled().performClick()
        assertEquals(1, confirms)
    }

    @Test
    fun theNewerFormatWarningAndTheIgnoredAppLogNoteShowOnlyWhenTheyApply() {
        showImport(preview(linkedMapOf(DataPart.LOGBOOK to logbookPlan), newer = false, appLog = false))
        composeRule.onAllNodesWithTag(TRANSFER_NEWER_FORMAT_TEST_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(TRANSFER_APP_LOG_IGNORED_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun theNewerFormatWarningAndTheIgnoredAppLogNoteShow() {
        showImport(preview(linkedMapOf(DataPart.LOGBOOK to logbookPlan), newer = true, appLog = true))
        tag(TRANSFER_NEWER_FORMAT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_warning_newer_format)).assertIsDisplayed()
        tag(TRANSFER_APP_LOG_IGNORED_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun aFileWithNothingImportableSaysSo_andOffersNoConfirmationOfAnything() {
        showImport(preview(emptyMap()))

        tag(TRANSFER_NOTHING_TEST_TAG).assertIsDisplayed()
        tag(TRANSFER_CONFIRM_TEST_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theResultShowsEachPart_theFailedOneWithAFixedSentence() {
        showImport(
            TransferImportState.Done(
                TransferResult(linkedMapOf(DataPart.LOGBOOK to PartOutcome.Done(98), DataPart.TRIPS to PartOutcome.Failed(IOException()))),
            ),
        )

        composeRule.onNodeWithText(string(R.string.transfer_result_done, string(R.string.transfer_part_logbook), plural(R.plurals.logbook_import_done, 98))).assertIsDisplayed()
        composeRule.onNodeWithText(
            string(R.string.transfer_result_done, string(R.string.transfer_part_trips), string(R.string.transfer_error_write)),
        ).assertIsDisplayed()
        tag(TRANSFER_CLOSE_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, dismisses)
    }

    @Test
    fun aFailureShowsItsTextAndAClose() {
        showImport(TransferImportState.Failed(UiText.StringResource(R.string.transfer_error_not_a_backup)))

        composeRule.onNodeWithText(string(R.string.transfer_error_not_a_backup)).assertIsDisplayed()
        tag(TRANSFER_CLOSE_TEST_TAG).performClick()
        assertEquals(1, dismisses)
    }

    @Test
    fun aFailureWithADynamicText_isShownAsIs() {
        showImport(TransferImportState.Failed(UiText.DynamicString("odd failure")))

        composeRule.onNodeWithText("odd failure").assertIsDisplayed()
    }

    // ── Grosse Schrift, schmales Fenster ─────────────────────────────────────

    @Test
    fun withBigFontOnANarrowWindowTheTextsWrapInsteadOfBeingCutOff() {
        showExport(exportUi(selection = setOf(DataPart.TRIPS, DataPart.APP_LOG), csvAllowed = false), fontScale = 1.3f, width = 360)

        tag(TRANSFER_CSV_HINT_TEST_TAG).performScrollTo().assertNotCutOff()
        tag(TRANSFER_NOTE_APP_LOG_TEST_TAG).performScrollTo().assertNotCutOff()
        composeRule.onNodeWithText(string(R.string.transfer_part_row, string(R.string.transfer_part_trips), plural(R.plurals.transfer_count_trips, 61))).performScrollTo().assertNotCutOff()
        for (button in listOf(TRANSFER_SAVE_TEST_TAG, TRANSFER_SHARE_TEST_TAG)) {
            tag(button).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        }
    }

    @Test
    fun withBigFontOnANarrowWindowTheImportTextsWrapInsteadOfBeingCutOff() {
        showImport(preview(linkedMapOf(DataPart.LOGBOOK to logbookPlan), newer = true, appLog = true), fontScale = 1.3f, width = 360)

        tag(TRANSFER_NEWER_FORMAT_TEST_TAG).performScrollTo().assertNotCutOff()
        tag(TRANSFER_APP_LOG_IGNORED_TEST_TAG).performScrollTo().assertNotCutOff()
        tag(TRANSFER_CONFIRM_TEST_TAG).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
    }
}
