package de.adv990de.obd2.ui.logbook

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.R
import de.adv990de.obd2.logbook.ImportPlan
import de.adv990de.obd2.logbook.ImportReason
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogIssue
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.RowError
import de.adv990de.obd2.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Der Dialog des Imports in jedem Zustand: Arbeit, Vorschau (mit Zahlen und abgelehnten Zeilen), nichts Neues, Ergebnis, Fehler. */
@RunWith(AndroidJUnit4::class)
class LogbookDialogsDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun plural(id: Int, n: Int) = composeRule.activity.resources.getQuantityString(id, n, n)

    private val entry = LogEntry(
        type = LogType.FUEL, date = LocalDate.of(2026, 10, 2), odometerKm = 40_362, amountMl = 14_370,
        fullTank = true, costCents = 2_743, note = null, brand = null,
    )

    private fun plan(new: Int = 98, duplicates: Int = 4, rejected: List<RowError> = emptyList(), warnings: Int = 0) =
        ImportPlan(List(new) { entry.copy(odometerKm = 30_000 + it) }, duplicates, rejected, warnings)

    private var confirmed = 0
    private var dismissed = 0

    private fun show(state: ImportState) {
        composeRule.setContent { LogbookImportDialog(state, onConfirm = { confirmed++ }, onDismiss = { dismissed++ }) }
        composeRule.waitForIdle()
    }

    @Test
    fun idle_showsNothing() {
        show(ImportState.Idle)

        composeRule.onAllNodesWithText(string(R.string.logbook_import_title)).assertCountEquals(0)
    }

    @Test
    fun working_showsTheProgressText_withoutButtons() {
        show(ImportState.Working)

        composeRule.onNodeWithText(string(R.string.logbook_import_working)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(LOGBOOK_IMPORT_CONFIRM_TEST_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(LOGBOOK_IMPORT_CLOSE_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun theCounts_showAsTheyAre_andTheRejectedRowsNameLineAndReason() {
        show(
            ImportState.Preview(
                plan(
                    new = 98, duplicates = 4, warnings = 3,
                    rejected = listOf(
                        RowError(17, listOf(ImportReason.BadValue("date"))),
                        RowError(23, listOf(ImportReason.Rule(LogIssue.CostRange), ImportReason.Rule(LogIssue.AmountRange))),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText(plural(R.plurals.logbook_import_new, 98)).assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.logbook_import_duplicates, 4)).assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.logbook_import_warnings, 3)).assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.logbook_import_rejected, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.logbook_import_row, 17, string(R.string.logbook_import_bad_value, "date"))).assertIsDisplayed()
        composeRule.onNodeWithText(
            string(R.string.logbook_import_row, 23, string(R.string.logbook_error_cost_range) + "; " + string(R.string.logbook_error_amount_range)),
        ).assertIsDisplayed()
    }

    @Test
    fun aZeroIsNotShown_aLineForDuplicatesOrWarningsOnlyAppearsWhenThereAreSome() {
        show(ImportState.Preview(plan(new = 1, duplicates = 0, warnings = 0)))

        composeRule.onNodeWithText(plural(R.plurals.logbook_import_new, 1)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.logbook_import_nothing_new)).assertCountEquals(0)
        composeRule.onAllNodesWithText(plural(R.plurals.logbook_import_duplicates, 0)).assertCountEquals(0)
        composeRule.onAllNodesWithText(plural(R.plurals.logbook_import_rejected, 0)).assertCountEquals(0)
    }

    @Test
    fun onlyTwentyRejectedRowsAreListed_theRestIsCounted() {
        val rejected = List(25) { RowError(it + 2, listOf(ImportReason.BadValue("date"))) }
        show(ImportState.Preview(plan(rejected = rejected)))

        composeRule.onNodeWithText(string(R.string.logbook_import_row, 21, string(R.string.logbook_import_bad_value, "date"))).assertExists()
        composeRule.onAllNodesWithText(string(R.string.logbook_import_row, 22, string(R.string.logbook_import_bad_value, "date"))).assertCountEquals(0)
        composeRule.onNodeWithText(plural(R.plurals.logbook_import_more_rows, 5)).assertExists()
    }

    @Test
    fun theTwoButtonsOfAPreview_areAtLeast56dp_andCallTheirHandlers() {
        show(ImportState.Preview(plan()))

        composeRule.onNodeWithTag(LOGBOOK_IMPORT_CONFIRM_TEST_TAG).assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithTag(LOGBOOK_IMPORT_CANCEL_TEST_TAG).assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithTag(LOGBOOK_IMPORT_CONFIRM_TEST_TAG).performClick()
        assertEquals(1, confirmed)
        composeRule.onNodeWithTag(LOGBOOK_IMPORT_CANCEL_TEST_TAG).performClick()
        assertEquals(1, dismissed)
        assertEquals("bestaetigen schliesst nicht selbst: das tut das ViewModel mit dem Ergebnis", 1, confirmed)
    }

    @Test
    fun withNothingNew_thereIsNoConfirm_onlyClose() {
        show(ImportState.Preview(plan(new = 0, duplicates = 102)))

        composeRule.onNodeWithText(string(R.string.logbook_import_nothing_new)).assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.logbook_import_duplicates, 102)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(LOGBOOK_IMPORT_CONFIRM_TEST_TAG).assertCountEquals(0)
        composeRule.onNodeWithTag(LOGBOOK_IMPORT_CLOSE_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun done_saysHowManyCameIn_andCloses() {
        show(ImportState.Done(98))

        composeRule.onNodeWithText(plural(R.plurals.logbook_import_done, 98)).assertIsDisplayed()
        composeRule.onNodeWithTag(LOGBOOK_IMPORT_CLOSE_TEST_TAG).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun failed_showsTheReason_andCloses() {
        show(ImportState.Failed(UiText.StringResource(R.string.logbook_import_error_too_large)))

        composeRule.onNodeWithText(string(R.string.logbook_import_error_too_large)).assertIsDisplayed()
        composeRule.onNodeWithTag(LOGBOOK_IMPORT_CLOSE_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun aFailureWithADynamicText_isShownAsIs() {
        show(ImportState.Failed(UiText.DynamicString("odd failure")))

        composeRule.onNodeWithText("odd failure").assertIsDisplayed()
    }
}
