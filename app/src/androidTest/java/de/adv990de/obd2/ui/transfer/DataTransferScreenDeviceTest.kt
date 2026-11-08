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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import de.adv990de.obd2.transfer.OdometerDecision
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
        exporting: Boolean = false,
    ) = ExportUi(counts, selection, format, csvAllowed, canExport, exporting)

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
        // didOverflowWidth compares with the width the paragraph was laid out in (the maximum), not with the box: a short text in a wide slot would count as cut off.
        // What matters is that no line reaches beyond the box and the last line ends inside it.
        val what = "'${layout.layoutInput.text.text.take(40)}' size=${layout.size} lines=${layout.lineCount}"
        for (line in 0 until layout.lineCount) {
            assertTrue("Text abgeschnitten (Breite, Zeile $line): $what", layout.getLineRight(line) <= layout.size.width + 1f)
        }
        assertTrue("Text abgeschnitten (Hoehe): $what", layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1f)
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

    private fun assertNoBackupHintOnExport(selection: Set<DataPart>) {
        showExport(exportUi(selection = selection, canExport = selection.isNotEmpty()))
        tag(TRANSFER_NOTE_NO_BACKUP_TEST_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_note_no_backup)).assertIsDisplayed()
    }

    @Test
    fun theNoBackupHintIsOnTheExportViewWithTheDefaultSelection() = assertNoBackupHintOnExport(DataPart.DEFAULT_EXPORT)

    @Test
    fun theNoBackupHintIsOnTheExportViewWithOnlyTheAppLog() = assertNoBackupHintOnExport(setOf(DataPart.APP_LOG))

    @Test
    fun theNoBackupHintIsOnTheExportViewWithNothingChosen() = assertNoBackupHintOnExport(emptySet())

    private fun assertNoExportHintsOnImport(state: TransferImportState) {
        showImport(state)
        composeRule.onAllNodesWithTag(TRANSFER_NOTE_NO_BACKUP_TEST_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(TRANSFER_NOTE_RECORDING_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun noExportHintIsOnTheImportViewBeforeAFileIsChosen() = assertNoExportHintsOnImport(TransferImportState.Idle)

    @Test
    fun noExportHintIsOnTheImportViewWhileReading() = assertNoExportHintsOnImport(TransferImportState.Working)

    @Test
    fun noExportHintIsOnTheImportViewInThePreview() = assertNoExportHintsOnImport(preview(linkedMapOf(DataPart.LOGBOOK to logbookPlan, DataPart.TRIPS to tripPlan)))

    @Test
    fun theRecordingHintShowsOnlyWhileTheTripsAreChosen() {
        showExport(exportUi(selection = setOf(DataPart.TRIPS, DataPart.LOGBOOK)))
        tag(TRANSFER_NOTE_RECORDING_TEST_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_note_recording_excluded)).assertIsDisplayed()
    }

    @Test
    fun theRecordingHintIsMissingWithoutTheTrips() {
        showExport(exportUi(selection = setOf(DataPart.LOGBOOK, DataPart.APP_LOG)))
        composeRule.onAllNodesWithTag(TRANSFER_NOTE_RECORDING_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun theVinHintShowsForTheTripsInAPackage() {
        showExport(exportUi(selection = setOf(DataPart.TRIPS, DataPart.LOGBOOK), format = ExportFormat.PACKAGE))
        tag(TRANSFER_NOTE_VIN_TEST_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_note_vin)).assertIsDisplayed()
    }

    @Test
    fun theVinHintIsMissingForTheCsvOfTheTrips() {
        showExport(exportUi(selection = setOf(DataPart.TRIPS), format = ExportFormat.CSV, csvAllowed = true))
        composeRule.onAllNodesWithTag(TRANSFER_NOTE_VIN_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun theVinHintIsMissingWithoutTheTrips() {
        showExport(exportUi(selection = setOf(DataPart.LOGBOOK, DataPart.APP_LOG)))
        composeRule.onAllNodesWithTag(TRANSFER_NOTE_VIN_TEST_TAG).assertCountEquals(0)
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
        // Both parts have two unreadable rows, so the sentence stands twice (once per part).
        composeRule.onAllNodesWithText(plural(R.plurals.logbook_import_rejected, 2)).assertCountEquals(2)
        composeRule.onAllNodesWithText(plural(R.plurals.logbook_import_rejected, 2))[0].performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.trip_import_new, 9)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_import_row, 17, string(R.string.trip_import_bad_value, "timestamp"))).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.trip_import_duplicates, 4)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aPartShowsItsBoxAsTheSelectionSays_andATapToggles() {
        val initial = preview(linkedMapOf(DataPart.LOGBOOK to logbookPlan, DataPart.TRIPS to tripPlan), selected = setOf(DataPart.LOGBOOK))
        composeRule.setContent {
            PreviewTheme(dark = false) {
                Scaffolded(1f, null) {
                    // A real selection behind the page, like the ViewModel: a tap changes what the boxes show.
                    val holder = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
                    val actions = ImportActions(
                        onPick = {},
                        onToggle = { part ->
                            toggled += part
                            val now = holder.value
                            holder.value = now.copy(selected = if (part in now.selected) now.selected - part else now.selected + part)
                        },
                        onConfirm = {},
                        onDismiss = {},
                    )
                    DataTransferImportContent(holder.value, actions)
                }
            }
        }

        // The tag sits on the column around a part; the box is the toggleable row with the name of the part.
        fun box(part: DataPart) = composeRule.onNode(isToggleable() and hasText(string(partLabelRes(part))))
        box(DataPart.LOGBOOK).assertIsDisplayed().assertIsOn()
        box(DataPart.TRIPS).performScrollTo().assertIsOff()
        box(DataPart.TRIPS).performClick()
        assertEquals(listOf(DataPart.TRIPS), toggled)
        box(DataPart.TRIPS).assertIsOn()
        box(DataPart.LOGBOOK).performScrollTo().assertIsOn().performClick()
        box(DataPart.LOGBOOK).assertIsOff()
        box(DataPart.TRIPS).assertIsOn()
    }

    @Test
    fun bothExportButtonsAreOffWhileAnExportRuns_andOnOtherwise() {
        showExport(exportUi(exporting = true))
        tag(TRANSFER_SAVE_TEST_TAG).performScrollTo().assertIsNotEnabled().performClick()
        tag(TRANSFER_SHARE_TEST_TAG).performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(0, saves + shares)
        // The size stays: a disabled button is still a 56 dp target.
        val density = Density(composeRule.activity)
        tag(TRANSFER_SAVE_TEST_TAG).assertHeightIsAtLeast(56.dp).assertTouchTargetAtLeast56(density)
        tag(TRANSFER_SHARE_TEST_TAG).assertHeightIsAtLeast(56.dp).assertTouchTargetAtLeast56(density)
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

    // ── Fehlerhistorie ──

    private val dtcPlan = PartPlan.DtcHistory(previewDtcPlan())

    @Test
    fun theFaultHistoryRowShowsItsCodes_andOneWithoutCodesSaysSo() {
        showExport(exportUi(counts = ExportCounts(trips = 1, logbook = 1, appLog = 1, dtcHistory = 4)))

        composeRule.onNodeWithText(string(R.string.transfer_part_row, string(R.string.transfer_part_dtc_history), plural(R.plurals.transfer_count_codes, 4))).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anEmptyFaultHistoryRowIsGreyedOut() {
        showExport(exportUi(counts = ExportCounts(trips = 1, logbook = 1, appLog = 1, dtcHistory = 0)))

        composeRule.onNodeWithText(string(R.string.transfer_part_row, string(R.string.transfer_part_dtc_history), string(R.string.transfer_part_empty))).performScrollTo().assertIsDisplayed()
        tag(transferPartRowTestTag(DataPart.DTC_HISTORY)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun thePreviewOfTheFaultHistoryShowsNewUpdatedUnchangedDroppedAndRejected() {
        showImport(preview(linkedMapOf(DataPart.DTC_HISTORY to dtcPlan)))

        composeRule.onNodeWithText(plural(R.plurals.transfer_dtc_new, 4)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.transfer_dtc_updated, 2)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.transfer_dtc_unchanged, 17)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.transfer_dtc_dropped, 1)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.transfer_dtc_rejected, 1)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun thePreviewOfAFaultHistoryThatChangesNothingNamesNoNewCodes() {
        val plan = PartPlan.DtcHistory(de.adv990de.obd2.transfer.DtcHistoryPlan(emptyList(), 0, 0, 5, 0, 0))
        showImport(preview(linkedMapOf(DataPart.DTC_HISTORY to plan), selected = emptySet()))

        composeRule.onNodeWithText(plural(R.plurals.transfer_dtc_unchanged, 5)).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(plural(R.plurals.transfer_dtc_new, 0)).assertCountEquals(0)
        composeRule.onAllNodesWithText(plural(R.plurals.transfer_dtc_dropped, 1)).assertCountEquals(0)
    }

    @Test
    fun theResultNamesTheMergedCodes() {
        showImport(TransferImportState.Done(TransferResult(linkedMapOf(DataPart.DTC_HISTORY to PartOutcome.Done(6)))))

        composeRule.onNodeWithText(string(R.string.transfer_result_done, string(R.string.transfer_part_dtc_history), plural(R.plurals.transfer_done_codes, 6))).assertIsDisplayed()
    }

    // ── Einstellungen und Zaehler ──

    private val settingsPlan = PartPlan.Settings(previewSettingsPlan())

    private fun settingsChangesText(changes: Int, total: Int) = composeRule.activity.resources.getQuantityString(R.plurals.transfer_settings_changes, changes, changes, total)

    @Test
    fun theRowsOfSettingsAndCountersAreJustTheirNames_andAlwaysSelectable() {
        showExport(exportUi(counts = ExportCounts(trips = 0, logbook = 0, appLog = 0, dtcHistory = 0), selection = setOf(DataPart.SETTINGS)))

        composeRule.onNodeWithText(string(R.string.transfer_part_settings)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_part_counters)).performScrollTo().assertIsDisplayed()
        tag(transferPartRowTestTag(DataPart.SETTINGS)).performScrollTo().assertIsEnabled().assertIsOn()
        tag(transferPartRowTestTag(DataPart.COUNTERS)).performScrollTo().assertIsEnabled().assertIsOff().performClick()
        assertEquals(listOf(DataPart.COUNTERS), toggled)
    }

    private fun settingsPreviewShowsGroupsRejectionsAndUnknown() {
        showImport(preview(linkedMapOf(DataPart.SETTINGS to settingsPlan)))

        composeRule.onNodeWithText(settingsChangesText(5, 36)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_part_row, string(R.string.transfer_group_display), "2")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_part_row, string(R.string.transfer_group_alarms), "1")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_part_row, string(R.string.transfer_group_vehicle_fuel), "1")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_part_row, string(R.string.transfer_group_gear), "1")).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.transfer_part_row, string(R.string.transfer_group_connection), "0")).assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.transfer_part_row, string(R.string.transfer_group_maintenance), "0")).assertCountEquals(0)
        composeRule.onNodeWithText(plural(R.plurals.transfer_settings_rejected, 2)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_reject_row, "rpm_blink_threshold", string(R.string.transfer_reject_out_of_range))).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_reject_row, "slot_pids", string(R.string.transfer_reject_unknown_value))).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.transfer_settings_unknown, 1)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun thePreviewOfTheSettingsShowsGroupsRejectionsAndUnknown_inPortrait() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        settingsPreviewShowsGroupsRejectionsAndUnknown()
    }

    @Test
    fun thePreviewOfTheSettingsShowsGroupsRejectionsAndUnknown_inLandscape() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        settingsPreviewShowsGroupsRejectionsAndUnknown()
    }

    private fun counterPlan(decision: OdometerDecision) = PartPlan.Counters(previewCountersPlan().values, emptyList(), decision, 40_362)

    private fun assertCountersLine(decision: OdometerDecision, line: Int) {
        showImport(preview(linkedMapOf(DataPart.COUNTERS to counterPlan(decision))))

        composeRule.onNodeWithText(string(R.string.transfer_counters_fuel, "%.1f".format(java.util.Locale.getDefault(), 12.5f))).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(line)).performScrollTo().assertIsDisplayed()
        // Die Zeile "endgueltig beim Importieren" steht bei jeder Entscheidung ausser "nicht in der Datei".
        composeRule.onAllNodesWithText(string(R.string.transfer_counters_decided_on_import)).assertCountEquals(if (decision == OdometerDecision.ABSENT) 0 else 1)
    }

    @Test
    fun theCountersPreviewTakesTheEstimateOver() = assertCountersLine(OdometerDecision.APPLY, R.string.transfer_counters_odometer_apply)

    @Test
    fun theCountersPreviewSkipsAForeignAnchor() = assertCountersLine(OdometerDecision.SKIP_ANCHOR_MISMATCH, R.string.transfer_counters_odometer_skip_anchor)

    @Test
    fun theCountersPreviewSkipsWithoutALogbook() = assertCountersLine(OdometerDecision.SKIP_NO_LOGBOOK, R.string.transfer_counters_odometer_skip_no_logbook)

    @Test
    fun theCountersPreviewSaysThereIsNoEstimateInTheFile() = assertCountersLine(OdometerDecision.ABSENT, R.string.transfer_counters_odometer_absent)

    @Test
    fun theResultNamesTheSettingsAndTheCounters_withAndWithoutSkipped() {
        showImport(
            TransferImportState.Done(
                TransferResult(linkedMapOf(DataPart.SETTINGS to PartOutcome.Done(count = 5, skipped = 2), DataPart.COUNTERS to PartOutcome.Done(count = 1, skipped = 1))),
            ),
        )

        composeRule.onNodeWithText(string(R.string.transfer_result_done, string(R.string.transfer_part_settings), plural(R.plurals.transfer_done_settings, 5))).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_result_done, string(R.string.transfer_part_counters), string(R.string.transfer_done_counters_partial, 1, 1))).assertIsDisplayed()
    }

    @Test
    fun theResultSaysCountersApplied_whenNothingWasSkipped() {
        showImport(TransferImportState.Done(TransferResult(linkedMapOf(DataPart.COUNTERS to PartOutcome.Done(count = 2)))))

        composeRule.onNodeWithText(string(R.string.transfer_result_done, string(R.string.transfer_part_counters), string(R.string.transfer_done_counters))).assertIsDisplayed()
    }
}
