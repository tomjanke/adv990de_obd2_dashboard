package de.adv990de.obd2.ui.history

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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.R
import de.adv990de.obd2.tripimport.RejectedRow
import de.adv990de.obd2.tripimport.TripImportPlan
import de.adv990de.obd2.tripimport.readTripCsv
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.formatDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringReader
import java.util.Locale

/** Der Dialog des Fahrten-Imports in jedem Zustand: Arbeit, Vorschau (mit Zahlen, Zeitraum und abgelehnten Zeilen), nichts Neues, Ergebnis, Fehler. */
@RunWith(AndroidJUnit4::class)
class TripImportDialogDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun plural(id: Int, n: Int) = composeRule.activity.resources.getQuantityString(id, n, n)

    private val start = 1_791_000_000_000L

    /** [trips] Fahrten zu je [rows] Messwerten, eine je zwei Stunden, gelesen aus einer Datei im Format des Exports. */
    private fun plan(trips: Int = 9, rows: Int = 120, duplicates: Int = 4, expired: Int = 0, rejected: List<RejectedRow> = emptyList(), rejectedCount: Int = rejected.size): TripImportPlan {
        val text = buildString {
            append("timestamp,pid,value,name,source,session\n")
            for (t in 0 until trips) for (r in 0 until rows) append(start + t * 7_200_000L + r * 1_000L).append(",010C,1.0,n,ECU,").append(t + 1).append('\n')
        }
        val parsed = readTripCsv(StringReader(text), start + 365L * 24 * 3_600_000)
        return TripImportPlan(parsed.trips, duplicates, expired, rejected, rejectedCount)
    }

    private var confirmed = 0
    private var dismissed = 0

    // Der Inhalt der Vorschau scrollt: je nach Ausrichtung des Geraets liegt unten, was im Hochformat noch zu sehen ist — Pruefungen auf spaetere Zeilen scrollen deshalb erst hin.
    private fun show(state: TripImportState) {
        composeRule.setContent { TripImportDialog(state, onConfirm = { confirmed++ }, onDismiss = { dismissed++ }) }
        composeRule.waitForIdle()
    }

    @Test
    fun idle_showsNothing() {
        show(TripImportState.Idle)

        composeRule.onAllNodesWithText(string(R.string.trip_import_title)).assertCountEquals(0)
    }

    @Test
    fun working_showsTheProgressText_withoutButtons() {
        show(TripImportState.Working)

        composeRule.onNodeWithText(string(R.string.trip_import_working)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(TRIP_IMPORT_CONFIRM_TEST_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(TRIP_IMPORT_CLOSE_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun theCounts_theTimeRange_andTheNote_showAsTheyAre() {
        show(TripImportState.Preview(plan(trips = 9, rows = 120, duplicates = 4, expired = 2)))

        composeRule.onNodeWithText(plural(R.plurals.trip_import_new, 9)).assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.trip_import_readings, 9 * 120)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_import_range, formatDateTime(start), formatDateTime(start + 8 * 7_200_000L + 119 * 1_000L))).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.trip_import_duplicates, 4)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.trip_import_expired, 2)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_import_note)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theNumberOfReadings_isWrittenWithTheThousandsSeparatorOfTheDevice() {
        // 10 800 Messwerte: fuenf Ziffern, damit jede Sprache trennt (Spanisch trennt vierstellige Zahlen nicht immer).
        show(TripImportState.Preview(plan(trips = 9, rows = 1_200)))

        val grouped = String.format(Locale.getDefault(), "%,d", 9 * 1_200)
        assertNotEquals("the device language groups digits, else this test proves nothing", "10800", grouped)
        composeRule.onNodeWithText(grouped, substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theRejectedRows_nameTheirLineAndTheColumn_andAreCountedByTheirTotal() {
        show(TripImportState.Preview(plan(rejected = listOf(RejectedRow(17, "timestamp"), RejectedRow(23, "value")), rejectedCount = 2)))

        composeRule.onNodeWithText(plural(R.plurals.trip_import_rejected, 2)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_import_row, 17, string(R.string.trip_import_bad_value, "timestamp"))).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_import_row, 23, string(R.string.trip_import_bad_value, "value"))).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aZeroIsNotShown_aLineForDuplicatesExpiredOrRejectedRowsOnlyAppearsWhenThereAreSome() {
        show(TripImportState.Preview(plan(trips = 1, duplicates = 0, expired = 0)))

        composeRule.onNodeWithText(plural(R.plurals.trip_import_new, 1)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.trip_import_nothing_new)).assertCountEquals(0)
        composeRule.onAllNodesWithText(plural(R.plurals.trip_import_duplicates, 0)).assertCountEquals(0)
        composeRule.onAllNodesWithText(plural(R.plurals.trip_import_expired, 0)).assertCountEquals(0)
        composeRule.onAllNodesWithText(plural(R.plurals.trip_import_rejected, 0)).assertCountEquals(0)
    }

    @Test
    fun onlyTwentyRejectedRowsAreListed_theRestIsCountedFromTheTotalNotFromTheKeptList() {
        // The plan keeps at most a hundred rows, but counts them all: 25 kept of 4 000 rejected.
        val rejected = List(25) { RejectedRow(it + 2, "timestamp") }
        show(TripImportState.Preview(plan(rejected = rejected, rejectedCount = 4_000)))

        composeRule.onNodeWithText(plural(R.plurals.trip_import_rejected, 4_000)).assertExists()
        composeRule.onNodeWithText(string(R.string.trip_import_row, 21, string(R.string.trip_import_bad_value, "timestamp"))).assertExists()
        composeRule.onAllNodesWithText(string(R.string.trip_import_row, 22, string(R.string.trip_import_bad_value, "timestamp"))).assertCountEquals(0)
        composeRule.onNodeWithText(plural(R.plurals.trip_import_more_rows, 3_980)).assertExists()
    }

    @Test
    fun theTwoButtonsOfAPreview_areAtLeast56dp_andCallTheirHandlers() {
        show(TripImportState.Preview(plan()))

        composeRule.onNodeWithTag(TRIP_IMPORT_CONFIRM_TEST_TAG).assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithTag(TRIP_IMPORT_CANCEL_TEST_TAG).assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithTag(TRIP_IMPORT_CONFIRM_TEST_TAG).performClick()
        assertEquals(1, confirmed)
        composeRule.onNodeWithTag(TRIP_IMPORT_CANCEL_TEST_TAG).performClick()
        assertEquals(1, dismissed)
        assertEquals("confirming does not close by itself: the view model does that with the result", 1, confirmed)
    }

    @Test
    fun withNothingNew_thereIsNoConfirmAndNoNote_onlyClose() {
        show(TripImportState.Preview(plan(trips = 0, duplicates = 13, expired = 1)))

        composeRule.onNodeWithText(string(R.string.trip_import_nothing_new)).assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.trip_import_duplicates, 13)).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.trip_import_note)).assertCountEquals(0)
        composeRule.onAllNodesWithTag(TRIP_IMPORT_CONFIRM_TEST_TAG).assertCountEquals(0)
        composeRule.onNodeWithTag(TRIP_IMPORT_CLOSE_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun done_saysHowManyCameIn_andCloses() {
        show(TripImportState.Done(9))

        composeRule.onNodeWithText(plural(R.plurals.trip_import_done, 9)).assertIsDisplayed()
        composeRule.onNodeWithTag(TRIP_IMPORT_CLOSE_TEST_TAG).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun failed_showsTheReason_andCloses() {
        show(TripImportState.Failed(UiText.StringResource(R.string.trip_import_error_too_large)))

        composeRule.onNodeWithText(string(R.string.trip_import_error_too_large)).assertIsDisplayed()
        composeRule.onNodeWithTag(TRIP_IMPORT_CLOSE_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun aFailureWithADynamicText_isShownAsIs() {
        show(TripImportState.Failed(UiText.DynamicString("odd failure")))

        composeRule.onNodeWithText("odd failure").assertIsDisplayed()
    }
}
