package de.adv990de.obd2.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.logbook.ImportPlan
import de.adv990de.obd2.logbook.ImportReason
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogIssue
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.RowError
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.PartOutcome
import de.adv990de.obd2.transfer.UnreadableReason
import de.adv990de.obd2.tripimport.ParsedTripFile
import de.adv990de.obd2.tripimport.RejectedRow
import de.adv990de.obd2.tripimport.TripImportPlan
import de.adv990de.obd2.tripimport.readTripCsv
import de.adv990de.obd2.ui.formatDateTime
import de.adv990de.obd2.ui.logbook.messageRes
import de.adv990de.obd2.ui.theme.PreviewTheme
import java.io.StringReader
import java.time.LocalDate

/** Hoechstens so viele abgelehnte Zeilen nennt die Vorschau einzeln; der Rest steht als "… und N weitere". */
internal const val MAX_LISTED_REJECTED_ROWS = 20

/** Hoechstens so viele abgelehnte Logbuch-Zeilen nennt die Vorschau einzeln. */
internal const val MAX_LISTED_ROW_ERRORS = 20

/**
 * Die Vorschau der Fahrten eines Imports (die Zahlen, die abgelehnten Zeilen): der gemeinsame Baustein der Seite „Daten sichern und wiederherstellen“ und des alten Dialogs der
 * Fahrten-Liste. Ein Rahmen ohne Scroll und ohne Hintergrund — der Aufrufer gibt ihn mit [modifier].
 */
@Composable
internal fun TripsPreviewBody(plan: TripImportPlan, modifier: Modifier = Modifier) {
    val nothingNew = plan.trips.isEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (nothingNew) {
            Text(stringResource(R.string.trip_import_nothing_new), style = MaterialTheme.typography.titleMedium)
        } else {
            Text(pluralStringResource(R.plurals.trip_import_new, plan.trips.size, plan.trips.size), style = MaterialTheme.typography.titleMedium)
            Text(pluralStringResource(R.plurals.trip_import_readings, plan.readingCount, plan.readingCount))
            Text(stringResource(R.string.trip_import_range, formatDateTime(plan.trips.minOf { it.startMs }), formatDateTime(plan.trips.maxOf { it.endMs })))
        }
        if (plan.duplicateTrips > 0) Text(pluralStringResource(R.plurals.trip_import_duplicates, plan.duplicateTrips, plan.duplicateTrips))
        if (plan.expiredTrips > 0) Text(pluralStringResource(R.plurals.trip_import_expired, plan.expiredTrips, plan.expiredTrips))
        if (!nothingNew) Text(stringResource(R.string.trip_import_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Die abgelehnten Zeilen stehen zuletzt: ihre Liste kann lang sein, der Hinweis soll davor stehen.
        if (plan.rejectedCount > 0) {
            Text(pluralStringResource(R.plurals.trip_import_rejected, plan.rejectedCount, plan.rejectedCount), color = MaterialTheme.colorScheme.error)
            plan.rejected.take(MAX_LISTED_REJECTED_ROWS).forEach { row ->
                Text(
                    stringResource(R.string.trip_import_row, row.line, stringResource(R.string.trip_import_bad_value, row.column)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (plan.rejectedCount > MAX_LISTED_REJECTED_ROWS) {
                val more = plan.rejectedCount - MAX_LISTED_REJECTED_ROWS
                Text(pluralStringResource(R.plurals.trip_import_more_rows, more, more), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Die Vorschau des Logbuchs eines Imports, wie [TripsPreviewBody] der gemeinsame Baustein von Seite und altem Dialog. */
@Composable
internal fun LogbookPreviewBody(plan: ImportPlan, modifier: Modifier = Modifier) {
    val nothingNew = plan.toInsert.isEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (nothingNew) stringResource(R.string.logbook_import_nothing_new)
            else pluralStringResource(R.plurals.logbook_import_new, plan.toInsert.size, plan.toInsert.size),
            style = MaterialTheme.typography.titleMedium,
        )
        if (plan.duplicates > 0) Text(pluralStringResource(R.plurals.logbook_import_duplicates, plan.duplicates, plan.duplicates))
        if (plan.warnings > 0) Text(pluralStringResource(R.plurals.logbook_import_warnings, plan.warnings, plan.warnings))
        if (plan.rejected.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.logbook_import_rejected, plan.rejected.size, plan.rejected.size),
                color = MaterialTheme.colorScheme.error,
            )
            plan.rejected.take(MAX_LISTED_ROW_ERRORS).forEach { row ->
                Text(stringResource(R.string.logbook_import_row, row.line, logbookRowReasonText(row)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            }
            if (plan.rejected.size > MAX_LISTED_ROW_ERRORS) {
                val more = plan.rejected.size - MAX_LISTED_ROW_ERRORS
                Text(pluralStringResource(R.plurals.logbook_import_more_rows, more, more), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Die Gruende einer abgelehnten Zeile, durch Semikolon getrennt: ein nicht lesbarer Wert nennt seine Spalte, eine Regel ihren Text. */
@Composable
private fun logbookRowReasonText(row: RowError): String = row.reasons.map { reason ->
    when (reason) {
        is ImportReason.BadValue -> stringResource(R.string.logbook_import_bad_value, reason.column)
        is ImportReason.Rule -> stringResource(reason.error.messageRes(null))
    }
}.joinToString("; ")

/** Ein Teil des Pakets, der sich nicht lesen liess: der Grund statt einer Vorschau; die anderen Teile sind davon unabhaengig. */
@Composable
internal fun UnreadablePartBody(reason: UnreadableReason, modifier: Modifier = Modifier) {
    Text(
        stringResource(
            when (reason) {
                UnreadableReason.FORMAT -> R.string.transfer_part_unreadable_format
                UnreadableReason.TOO_LARGE -> R.string.transfer_part_unreadable_too_large
                UnreadableReason.UNREADABLE -> R.string.transfer_part_unreadable_io
            },
        ),
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

/** Eine Zeile des Ergebnisses: „Fahrten: 9 Fahrten importiert“ bzw. „Logbuch: Das Speichern ist fehlgeschlagen …“ in der Fehlerfarbe. */
@Composable
internal fun PartResultLine(part: DataPart, outcome: PartOutcome, modifier: Modifier = Modifier) {
    val name = stringResource(partLabelRes(part))
    when (outcome) {
        is PartOutcome.Done -> Text(stringResource(R.string.transfer_result_done, name, doneText(part, outcome.count)), modifier = modifier)
        // Der technische Grund (die Meldung des Systems) steht nicht in der Oberflaeche: ein fester, lokalisierter Satz (der Grund bleibt in `PartOutcome.Failed.cause`).
        is PartOutcome.Failed -> Text(
            stringResource(R.string.transfer_result_done, name, stringResource(R.string.transfer_error_write)),
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}

@Composable
private fun doneText(part: DataPart, count: Int): String = when (part) {
    DataPart.TRIPS -> pluralStringResource(R.plurals.trip_import_done, count, count)
    DataPart.LOGBOOK -> pluralStringResource(R.plurals.logbook_import_done, count, count)
    else -> count.toString()
}

// region — Previews

private const val PREVIEW_START_MS = 1_791_000_000_000L

/** Neun Fahrten zu je 120 Messwerten, gelesen aus einer kleinen CSV-Datei wie der Export sie schreibt. */
internal fun previewTripPlan(rejected: List<RejectedRow> = emptyList(), rejectedCount: Int = 0, duplicates: Int = 0, expired: Int = 0): TripImportPlan {
    val text = buildString {
        append("timestamp,pid,value,name,source,session\n")
        for (trip in 0 until 9) for (row in 0 until 120) {
            append(PREVIEW_START_MS + trip * 7_200_000L + row * 1_000L).append(",010C,").append(1_500 + row).append(".0,Engine RPM,ECU,").append(trip + 1).append('\n')
        }
    }
    val parsed: ParsedTripFile = readTripCsv(StringReader(text), PREVIEW_START_MS + 365L * 24 * 3_600_000)
    return TripImportPlan(parsed.trips, duplicates, expired, rejected, rejectedCount)
}

internal fun previewLogbookPlan(): ImportPlan {
    val entry = LogEntry(
        type = LogType.FUEL, date = LocalDate.of(2026, 10, 2), odometerKm = 40_362, amountMl = 14_370,
        fullTank = true, costCents = 2_743, note = null, brand = null,
    )
    return ImportPlan(
        toInsert = List(98) { entry.copy(odometerKm = 30_000 + it) },
        duplicates = 4,
        rejected = listOf(
            RowError(17, listOf(ImportReason.BadValue("date"))),
            RowError(23, listOf(ImportReason.Rule(LogIssue.CostRange), ImportReason.Rule(LogIssue.AmountRange))),
        ),
        warnings = 3,
    )
}

@Preview(name = "TripsPreviewBody (light)", showBackground = true)
@Composable
private fun PreviewTripsPreviewBodyLight() {
    PreviewTheme(dark = false) {
        TripsPreviewBody(previewTripPlan(listOf(RejectedRow(17, "timestamp"), RejectedRow(23, "value")), rejectedCount = 2, duplicates = 4, expired = 1), Modifier.padding(16.dp))
    }
}

@Preview(name = "LogbookPreviewBody (dark)", showBackground = true)
@Composable
private fun PreviewLogbookPreviewBodyDark() {
    PreviewTheme(dark = true) { LogbookPreviewBody(previewLogbookPlan(), Modifier.padding(16.dp)) }
}

@Preview(name = "PartResultLine · failed (light)", showBackground = true)
@Composable
private fun PreviewPartResultLinesLight() {
    PreviewTheme(dark = false) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PartResultLine(DataPart.LOGBOOK, PartOutcome.Done(98))
            PartResultLine(DataPart.TRIPS, PartOutcome.Failed(java.io.IOException()))
            UnreadablePartBody(UnreadableReason.TOO_LARGE)
        }
    }
}

// endregion
