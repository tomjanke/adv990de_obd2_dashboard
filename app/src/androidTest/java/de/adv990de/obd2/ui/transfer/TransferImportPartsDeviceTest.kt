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
import de.adv990de.obd2.logbook.ImportPlan
import de.adv990de.obd2.logbook.ImportReason
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogIssue
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.RowError
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.PartOutcome
import de.adv990de.obd2.transfer.TripMeta
import de.adv990de.obd2.transfer.TripMetaSummary
import de.adv990de.obd2.tripimport.RejectedRow
import de.adv990de.obd2.tripimport.TripImportPlan
import de.adv990de.obd2.tripimport.readTripCsv
import de.adv990de.obd2.ui.formatDateTime
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringReader
import java.time.LocalDate
import java.util.Locale

/**
 * Die Vorschau der Fahrten ([TripsPreviewBody]) und des Logbuchs ([LogbookPreviewBody]) in jedem Fall, wie die Seite „Daten sichern und wiederherstellen“ sie je Teil zeigt: die Zahlen, der Zeitraum, der Hinweis, die
 * abgelehnten Zeilen. Die Faelle stammen aus den Tests der frueheren Import-Dialoge von Fahrten und Logbuch (die Dialoge entfielen mit der Seite); der Rahmen der Seite steht in `DataTransferScreenDeviceTest`.
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
    private fun showTrips(plan: TripImportPlan, meta: TripMetaSummary = TripMetaSummary(0, 0, 0, 0, false)) {
        composeRule.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { TripsPreviewBody(plan, meta = meta) } }
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

    // ── FIN und Fehlercodes je Fahrt (trips_meta.csv) ───────────────────────────

    private val vin = "TESTVIN0000000001"

    /** Die ersten Fahrten des Plans mit den Angaben aus [metas], die uebrigen ohne. */
    private fun planWithMeta(trips: Int, vararg metas: TripMeta): TripImportPlan {
        val base = plan(trips = trips, rows = 3, duplicates = 0)
        return base.copy(trips = base.trips.mapIndexed { i, t -> t.withMeta(metas.getOrNull(i)) })
    }

    @Test
    fun theTripsWithAVinAndWithRecordedCodesAreCounted_andNoNoteWhenEveryTripHasDetails() {
        val plan = planWithMeta(2, TripMeta(vin, "P0301"), TripMeta(vin, ""))
        showTrips(plan, TripMetaSummary(withVin = 2, withCodesRecorded = 2, invalidVins = 0, orphanRows = 0, unreadable = false))

        composeRule.onNodeWithText(plural(R.plurals.transfer_trips_with_vin, 2)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.transfer_trips_with_codes, 2)).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.trip_import_note)).assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.transfer_trips_some_unrecorded)).assertCountEquals(0)
    }

    @Test
    fun theNotRecordedNoteStaysWhenNoTripHasDetails() {
        showTrips(planWithMeta(2), TripMetaSummary(0, 0, 0, 0, false))

        composeRule.onNodeWithText(string(R.string.trip_import_note)).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.transfer_trips_some_unrecorded)).assertCountEquals(0)
        composeRule.onAllNodesWithText(plural(R.plurals.transfer_trips_with_vin, 0)).assertCountEquals(0)
    }

    @Test
    fun aNoteAppearsWhenOnlySomeTripsHaveDetails() {
        showTrips(planWithMeta(2, TripMeta(vin, null)), TripMetaSummary(withVin = 1, withCodesRecorded = 0, invalidVins = 0, orphanRows = 0, unreadable = false))

        composeRule.onNodeWithText(plural(R.plurals.transfer_trips_with_vin, 1)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_trips_some_unrecorded)).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.trip_import_note)).assertCountEquals(0)
        composeRule.onAllNodesWithText(plural(R.plurals.transfer_trips_with_codes, 0)).assertCountEquals(0)
    }

    @Test
    fun invalidVinsAndOrphanRowsHaveALineEach() {
        showTrips(planWithMeta(1, TripMeta(null, "")), TripMetaSummary(withVin = 0, withCodesRecorded = 1, invalidVins = 3, orphanRows = 2, unreadable = false))

        composeRule.onNodeWithText(plural(R.plurals.transfer_trips_invalid_vin, 3)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.transfer_trips_orphan_meta, 2)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anUnreadableMetaFileShowsAWarning_andTheNoteForTripsWithoutDetails() {
        showTrips(planWithMeta(2), TripMetaSummary(0, 0, 0, 0, unreadable = true))

        composeRule.onNodeWithText(string(R.string.transfer_trips_meta_unreadable)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_trips_some_unrecorded)).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.trip_import_note)).assertCountEquals(0)
    }

    @Test
    fun withoutAMetaFileNothingAboutDetailsIsShown() {
        showTrips(plan(trips = 1, duplicates = 0))

        composeRule.onAllNodesWithText(string(R.string.transfer_trips_meta_unreadable)).assertCountEquals(0)
        composeRule.onAllNodesWithText(plural(R.plurals.transfer_trips_invalid_vin, 1)).assertCountEquals(0)
        composeRule.onAllNodesWithText(plural(R.plurals.transfer_trips_orphan_meta, 1)).assertCountEquals(0)
    }

    // ── Logbuch ──────────────────────────────────────────────────────────────

    private val entry = LogEntry(
        type = LogType.FUEL, date = LocalDate.of(2026, 10, 2), odometerKm = 40_362, amountMl = 14_370,
        fullTank = true, costCents = 2_743, note = null, brand = null,
    )

    private fun logbookPlan(new: Int = 98, duplicates: Int = 4, rejected: List<RowError> = emptyList(), warnings: Int = 0) =
        ImportPlan(List(new) { entry.copy(odometerKm = 30_000 + it) }, duplicates, rejected, warnings)

    private fun showLogbook(plan: ImportPlan) {
        composeRule.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { LogbookPreviewBody(plan) } }
        composeRule.waitForIdle()
    }

    @Test
    fun theLogbookCounts_showAsTheyAre_andTheRejectedRowsNameLineAndReason() {
        showLogbook(
            logbookPlan(
                new = 98, duplicates = 4, warnings = 3,
                rejected = listOf(
                    RowError(17, listOf(ImportReason.BadValue("date"))),
                    RowError(23, listOf(ImportReason.Rule(LogIssue.CostRange), ImportReason.Rule(LogIssue.AmountRange))),
                ),
            ),
        )

        composeRule.onNodeWithText(plural(R.plurals.logbook_import_new, 98)).assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.logbook_import_duplicates, 4)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.logbook_import_warnings, 3)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.logbook_import_rejected, 2)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.logbook_import_row, 17, string(R.string.logbook_import_bad_value, "date"))).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            string(R.string.logbook_import_row, 23, string(R.string.logbook_error_cost_range) + "; " + string(R.string.logbook_error_amount_range)),
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aLogbookZeroIsNotShown_aLineForDuplicatesOrWarningsOnlyAppearsWhenThereAreSome() {
        showLogbook(logbookPlan(new = 1, duplicates = 0, warnings = 0))

        composeRule.onNodeWithText(plural(R.plurals.logbook_import_new, 1)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.logbook_import_nothing_new)).assertCountEquals(0)
        composeRule.onAllNodesWithText(plural(R.plurals.logbook_import_duplicates, 0)).assertCountEquals(0)
        composeRule.onAllNodesWithText(plural(R.plurals.logbook_import_rejected, 0)).assertCountEquals(0)
    }

    @Test
    fun onlyTwentyRejectedLogbookRowsAreListed_theRestIsCounted() {
        val rejected = List(25) { RowError(it + 2, listOf(ImportReason.BadValue("date"))) }
        showLogbook(logbookPlan(rejected = rejected))

        composeRule.onNodeWithText(string(R.string.logbook_import_row, 21, string(R.string.logbook_import_bad_value, "date"))).assertExists()
        composeRule.onAllNodesWithText(string(R.string.logbook_import_row, 22, string(R.string.logbook_import_bad_value, "date"))).assertCountEquals(0)
        composeRule.onNodeWithText(plural(R.plurals.logbook_import_more_rows, 5)).assertExists()
    }

    @Test
    fun withNothingNewInTheLogbook_theTitleSaysSo_andTheDuplicatesAreCounted() {
        showLogbook(logbookPlan(new = 0, duplicates = 102))

        composeRule.onNodeWithText(string(R.string.logbook_import_nothing_new)).assertIsDisplayed()
        composeRule.onNodeWithText(plural(R.plurals.logbook_import_duplicates, 102)).assertIsDisplayed()
    }

    // ── Ergebniszeile eines Teils ────────────────────────────────────────────

    @Test
    fun aFailedPart_showsAFixedLocalisedSentenceNotTheSystemMessage_andTheDoneOnesTheirCount() {
        composeRule.setContent {
            Column {
                PartResultLine(DataPart.LOGBOOK, PartOutcome.Done(98))
                PartResultLine(DataPart.TRIPS, PartOutcome.Failed(IllegalStateException("odd failure")))
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.transfer_result_done, string(R.string.transfer_part_logbook), plural(R.plurals.logbook_import_done, 98))).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.transfer_result_done, string(R.string.transfer_part_trips), string(R.string.transfer_error_write))).assertIsDisplayed()
        composeRule.onNodeWithText("odd failure", substring = true).assertDoesNotExist()
    }
}
