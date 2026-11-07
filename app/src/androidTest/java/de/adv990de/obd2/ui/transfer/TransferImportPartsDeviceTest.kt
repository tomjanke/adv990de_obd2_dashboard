package de.adv990de.obd2.ui.transfer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.R
import de.adv990de.obd2.tripimport.RejectedRow
import de.adv990de.obd2.tripimport.TripImportPlan
import de.adv990de.obd2.tripimport.readTripCsv
import de.adv990de.obd2.ui.formatDateTime
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringReader
import java.util.Locale

/**
 * Die Vorschau der Fahrten ([TripsPreviewBody]) in jedem Fall, wie die Seite „Daten sichern und wiederherstellen“ sie je Teil zeigt: die Zahlen, der Zeitraum, der Hinweis, die
 * abgelehnten Zeilen. Die Faelle stammen aus dem Test des frueheren Dialogs der Fahrten-Liste (der Dialog entfiel mit der Seite); der Rahmen der Seite steht in `DataTransferScreenDeviceTest`.
 */
@RunWith(AndroidJUnit4::class)
class TransferImportPartsDeviceTest {

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

    // Der Baustein scrollt nicht selbst (die Seite tut es): im Test steht er in einer scrollbaren Spalte, und Pruefungen auf spaetere Zeilen scrollen erst hin.
    private fun showTrips(plan: TripImportPlan) {
        composeRule.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { TripsPreviewBody(plan) } }
        composeRule.waitForIdle()
    }

    @Test
    fun theTripCounts_theTimeRange_andTheNote_showAsTheyAre() {
        showTrips(plan(trips = 9, rows = 120, duplicates = 4, expired = 2))

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
        showTrips(plan(trips = 9, rows = 1_200))

        val grouped = String.format(Locale.getDefault(), "%,d", 9 * 1_200)
        assertNotEquals("the device language groups digits, else this test proves nothing", "10800", grouped)
        composeRule.onNodeWithText(grouped, substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theRejectedRows_nameTheirLineAndTheColumn_andAreCountedByTheirTotal() {
        showTrips(plan(rejected = listOf(RejectedRow(17, "timestamp"), RejectedRow(23, "value")), rejectedCount = 2))

        composeRule.onNodeWithText(plural(R.plurals.trip_import_rejected, 2)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_import_row, 17, string(R.string.trip_import_bad_value, "timestamp"))).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_import_row, 23, string(R.string.trip_import_bad_value, "value"))).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aZeroIsNotShown_aLineForDuplicatesExpiredOrRejectedRowsOnlyAppearsWhenThereAreSome() {
        showTrips(plan(trips = 1, duplicates = 0, expired = 0))

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
        showTrips(plan(rejected = rejected, rejectedCount = 4_000))

        composeRule.onNodeWithText(plural(R.plurals.trip_import_rejected, 4_000)).assertExists()
        composeRule.onNodeWithText(string(R.string.trip_import_row, 21, string(R.string.trip_import_bad_value, "timestamp"))).assertExists()
        composeRule.onAllNodesWithText(string(R.string.trip_import_row, 22, string(R.string.trip_import_bad_value, "timestamp"))).assertCountEquals(0)
        composeRule.onNodeWithText(plural(R.plurals.trip_import_more_rows, 3_980)).assertExists()
    }

    @Test
    fun withNothingNew_theTitleSaysSo_theOldOnesAreCounted_andThereIsNoNote() {
        showTrips(plan(trips = 0, duplicates = 13, expired = 1))

        composeRule.onNodeWithText(string(R.string.trip_import_nothing_new)).assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.trip_import_duplicates, 13)).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.trip_import_note)).assertCountEquals(0)
    }
}
