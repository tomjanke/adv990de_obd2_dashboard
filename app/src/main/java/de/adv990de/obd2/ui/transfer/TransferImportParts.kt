package de.adv990de.obd2.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
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
import de.adv990de.obd2.transfer.CounterValues
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.DtcHistoryPlan
import de.adv990de.obd2.transfer.OdometerDecision
import de.adv990de.obd2.transfer.PartOutcome
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.SettingReject
import de.adv990de.obd2.transfer.SettingsGroup
import de.adv990de.obd2.transfer.RejectedSetting
import de.adv990de.obd2.transfer.SettingChange
import de.adv990de.obd2.transfer.SettingsCatalog
import de.adv990de.obd2.transfer.SettingsPlan
import de.adv990de.obd2.transfer.TripMetaSummary
import de.adv990de.obd2.transfer.UnreadableReason
import de.adv990de.obd2.tripimport.ParsedTripFile
import de.adv990de.obd2.tripimport.RejectedRow
import de.adv990de.obd2.tripimport.TripImportPlan
import de.adv990de.obd2.tripimport.readTripCsv
import de.adv990de.obd2.ui.formatDateTime
import de.adv990de.obd2.ui.logbook.messageRes
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.StatusColors
import java.io.StringReader
import java.time.LocalDate

/** Hoechstens so viele abgelehnte Zeilen nennt die Vorschau einzeln; der Rest steht als "… und N weitere". */
internal const val MAX_LISTED_REJECTED_ROWS = 20

/** Hoechstens so viele abgelehnte Logbuch-Zeilen nennt die Vorschau einzeln. */
internal const val MAX_LISTED_ROW_ERRORS = 20

/**
 * Die Vorschau der Fahrten eines Imports (die Zahlen, [meta] = was `trips_meta.csv` zu FIN und Fehlercodes sagt, die abgelehnten Zeilen) auf der Seite „Daten sichern und wiederherstellen“. Ein Rahmen ohne Scroll und ohne Hintergrund —
 * der Aufrufer gibt ihn mit [modifier].
 */
@Composable
internal fun TripsPreviewBody(plan: TripImportPlan, modifier: Modifier = Modifier, meta: TripMetaSummary = TripMetaSummary(0, 0, 0, 0, false)) {
    val nothingNew = plan.trips.isEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (nothingNew) {
            Text(stringResource(R.string.trip_import_nothing_new), style = MaterialTheme.typography.titleMedium)
        } else {
            Text(pluralStringResource(R.plurals.trip_import_new, plan.trips.size, plan.trips.size), style = MaterialTheme.typography.titleMedium)
            Text(pluralStringResource(R.plurals.trip_import_readings, plan.readingCount, plan.readingCount))
            Text(stringResource(R.string.trip_import_range, formatDateTime(plan.trips.minOf { it.startMs }), formatDateTime(plan.trips.maxOf { it.endMs })))
            if (meta.withVin > 0) Text(pluralStringResource(R.plurals.transfer_trips_with_vin, meta.withVin, meta.withVin))
            if (meta.withCodesRecorded > 0) Text(pluralStringResource(R.plurals.transfer_trips_with_codes, meta.withCodesRecorded, meta.withCodesRecorded))
        }
        if (plan.duplicateTrips > 0) Text(pluralStringResource(R.plurals.trip_import_duplicates, plan.duplicateTrips, plan.duplicateTrips))
        if (plan.expiredTrips > 0) Text(pluralStringResource(R.plurals.trip_import_expired, plan.expiredTrips, plan.expiredTrips))
        // Der Hinweis "nicht erfasst" nur, wenn mindestens eine neue Fahrt ohne FIN und ohne Fehlercodes kommt (die Datei allein, ohne Angaben, nennt keine).
        val withoutDetails = plan.trips.count { it.vin == null && it.dtcCodes == null }
        if (withoutDetails > 0) {
            val allWithout = withoutDetails == plan.trips.size && !meta.unreadable
            Text(
                stringResource(if (allWithout) R.string.trip_import_note else R.string.transfer_trips_some_unrecorded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (meta.invalidVins > 0) Text(pluralStringResource(R.plurals.transfer_trips_invalid_vin, meta.invalidVins, meta.invalidVins))
        if (meta.orphanRows > 0) Text(pluralStringResource(R.plurals.transfer_trips_orphan_meta, meta.orphanRows, meta.orphanRows))
        if (meta.unreadable) Text(stringResource(R.string.transfer_trips_meta_unreadable), color = StatusColors.warningOn(MaterialTheme.colorScheme.surface))
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

/**
 * Die Vorschau der Fehlerhistorie eines Imports: neue, aktualisierte und unveraenderte Codes (die Zahlen, die nicht null sind, ausser bei der ersten Zeile: ohne etwas Neues steht nur
 * die Zeile der unveraenderten Codes), die ueber die Grenze von 200 fallen, und die abgelehnten Zeilen.
 */
@Composable
internal fun DtcHistoryPreviewBody(plan: DtcHistoryPlan, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plan.newCodes > 0) Text(pluralStringResource(R.plurals.transfer_dtc_new, plan.newCodes, plan.newCodes), style = MaterialTheme.typography.titleMedium)
        if (plan.updatedCodes > 0) Text(pluralStringResource(R.plurals.transfer_dtc_updated, plan.updatedCodes, plan.updatedCodes))
        if (plan.unchangedCodes > 0) Text(pluralStringResource(R.plurals.transfer_dtc_unchanged, plan.unchangedCodes, plan.unchangedCodes))
        if (plan.droppedByCap > 0) {
            Text(pluralStringResource(R.plurals.transfer_dtc_dropped, plan.droppedByCap, plan.droppedByCap), color = StatusColors.warningOn(MaterialTheme.colorScheme.surface))
        }
        if (plan.rejected > 0) Text(pluralStringResource(R.plurals.transfer_dtc_rejected, plan.rejected, plan.rejected), color = MaterialTheme.colorScheme.error)
    }
}

/** Der Name einer Gruppe der Einstellungen in der Vorschau. */
@androidx.annotation.StringRes
internal fun settingsGroupLabelRes(group: SettingsGroup): Int = when (group) {
    SettingsGroup.DISPLAY -> R.string.transfer_group_display
    SettingsGroup.DASHBOARD -> R.string.transfer_group_dashboard
    SettingsGroup.CONNECTION -> R.string.transfer_group_connection
    SettingsGroup.ALARMS -> R.string.transfer_group_alarms
    SettingsGroup.VEHICLE_FUEL -> R.string.transfer_group_vehicle_fuel
    SettingsGroup.MAINTENANCE -> R.string.transfer_group_maintenance
    SettingsGroup.GEAR -> R.string.transfer_group_gear
}

@androidx.annotation.StringRes
internal fun settingRejectRes(reason: SettingReject): Int = when (reason) {
    SettingReject.WRONG_TYPE -> R.string.transfer_reject_wrong_type
    SettingReject.OUT_OF_RANGE -> R.string.transfer_reject_out_of_range
    SettingReject.NOT_AN_OPTION -> R.string.transfer_reject_not_an_option
    SettingReject.UNKNOWN_VALUE -> R.string.transfer_reject_unknown_value
}

/** Hoechstens so viele abgelehnte Einstellungen nennt die Vorschau einzeln. */
internal const val MAX_LISTED_REJECTED_SETTINGS = 20

/**
 * Die Vorschau der Einstellungen eines Imports: „n von m Einstellungen aendern sich“, je Gruppe mit Aenderungen eine Zeile, die abgelehnten Eintraege (Schluessel und Grund, hoechstens
 * [MAX_LISTED_REJECTED_SETTINGS]) und die unbekannten.
 */
@Composable
internal fun SettingsPreviewBody(plan: SettingsPlan, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pluralStringResource(R.plurals.transfer_settings_changes, plan.changes.size, plan.changes.size, plan.total), style = MaterialTheme.typography.titleMedium)
        SettingsGroup.entries.forEach { group ->
            val n = plan.changesBy(group)
            if (n > 0) Text(stringResource(R.string.transfer_part_row, stringResource(settingsGroupLabelRes(group)), n.toString()), modifier = Modifier.padding(start = 8.dp))
        }
        if (plan.rejected.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.transfer_settings_rejected, plan.rejected.size, plan.rejected.size), color = MaterialTheme.colorScheme.error)
            plan.rejected.take(MAX_LISTED_REJECTED_SETTINGS).forEach { row ->
                Text(
                    stringResource(R.string.transfer_reject_row, row.key, stringResource(settingRejectRes(row.reason))),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (plan.rejected.size > MAX_LISTED_REJECTED_SETTINGS) {
                val more = plan.rejected.size - MAX_LISTED_REJECTED_SETTINGS
                Text(pluralStringResource(R.plurals.trip_import_more_rows, more, more), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (plan.unknownKeys.isNotEmpty()) Text(pluralStringResource(R.plurals.transfer_settings_unknown, plan.unknownKeys.size, plan.unknownKeys.size))
    }
}

/** Die Vorschau der Zaehler: der Tankzaehler, die Zeile zur Kilometer-Schaetzung je Entscheidung (eine Vorhersage: endgueltig entscheidet das Einlesen) und abgelehnte Schluessel. */
@Composable
internal fun CountersPreviewBody(plan: PartPlan.Counters, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.values.fuelKmSinceFill?.let { km ->
            Text(stringResource(R.string.transfer_counters_fuel, "%.1f".format(LocalLocale.current.platformLocale, km)), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(
                when (plan.decision) {
                    OdometerDecision.APPLY -> R.string.transfer_counters_odometer_apply
                    OdometerDecision.SKIP_ANCHOR_MISMATCH -> R.string.transfer_counters_odometer_skip_anchor
                    OdometerDecision.SKIP_NO_LOGBOOK -> R.string.transfer_counters_odometer_skip_no_logbook
                    OdometerDecision.ABSENT -> R.string.transfer_counters_odometer_absent
                },
            ),
        )
        if (plan.decision != OdometerDecision.ABSENT) {
            Text(stringResource(R.string.transfer_counters_decided_on_import), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (plan.rejectedKeys.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.transfer_settings_rejected, plan.rejectedKeys.size, plan.rejectedKeys.size), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Die Vorschau des Logbuchs eines Imports, gebaut wie [TripsPreviewBody]. */
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
        is PartOutcome.Done -> Text(stringResource(R.string.transfer_result_done, name, doneText(part, outcome)), modifier = modifier)
        // Der technische Grund (die Meldung des Systems) steht nicht in der Oberflaeche: ein fester, lokalisierter Satz (der Grund bleibt in `PartOutcome.Failed.cause`).
        is PartOutcome.Failed -> Text(
            stringResource(R.string.transfer_result_done, name, stringResource(R.string.transfer_error_write)),
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}

@Composable
private fun doneText(part: DataPart, outcome: PartOutcome.Done): String {
    val count = outcome.count
    return when (part) {
        DataPart.TRIPS -> pluralStringResource(R.plurals.trip_import_done, count, count)
        DataPart.LOGBOOK -> pluralStringResource(R.plurals.logbook_import_done, count, count)
        DataPart.DTC_HISTORY -> pluralStringResource(R.plurals.transfer_done_codes, count, count)
        DataPart.SETTINGS -> pluralStringResource(R.plurals.transfer_done_settings, count, count)
        DataPart.COUNTERS ->
            if (outcome.skipped == 0) stringResource(R.string.transfer_done_counters) else stringResource(R.string.transfer_done_counters_partial, count, outcome.skipped)
        DataPart.APP_LOG -> count.toString()
    }
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

/** Vier neue, zwei aktualisierte und 17 unveraenderte Codes, einer ueber der Grenze, eine abgelehnte Zeile. */
internal fun previewDtcPlan(): DtcHistoryPlan = DtcHistoryPlan(emptyList(), newCodes = 4, updatedCodes = 2, unchangedCodes = 17, droppedByCap = 1, rejected = 1)

internal fun previewSettingsPlan(): SettingsPlan {
    fun change(key: String, to: Any) = SettingChange(SettingsCatalog.spec(key)!!, null, to)
    return SettingsPlan(
        changes = listOf(change("theme", "amber"), change("use_imperial", true), change("alarm_coolant_warning_c", 110), change("fuel_range_full_km", 280), change("gear_ratio_1", 2.5f)),
        unchanged = 29,
        rejected = listOf(RejectedSetting("rpm_blink_threshold", SettingReject.OUT_OF_RANGE), RejectedSetting("slot_pids", SettingReject.UNKNOWN_VALUE)),
        unknownKeys = listOf("future_key"),
    )
}

internal fun previewCountersPlan(decision: OdometerDecision = OdometerDecision.APPLY): PartPlan.Counters =
    PartPlan.Counters(CounterValues(12.5f, 40_362, 4.2f), rejectedKeys = emptyList(), decision = decision, predictedMaxKm = 40_362)

@Preview(name = "SettingsPreviewBody (light)", showBackground = true)
@Composable
private fun PreviewSettingsPreviewBodyLight() {
    PreviewTheme(dark = false) { SettingsPreviewBody(previewSettingsPlan(), Modifier.padding(16.dp)) }
}

@Preview(name = "CountersPreviewBody (dark)", showBackground = true)
@Composable
private fun PreviewCountersPreviewBodyDark() {
    PreviewTheme(dark = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CountersPreviewBody(previewCountersPlan())
            CountersPreviewBody(previewCountersPlan(OdometerDecision.SKIP_ANCHOR_MISMATCH))
        }
    }
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

@Preview(name = "DtcHistoryPreviewBody (dark)", showBackground = true)
@Composable
private fun PreviewDtcHistoryPreviewBodyDark() {
    PreviewTheme(dark = true) { DtcHistoryPreviewBody(previewDtcPlan(), Modifier.padding(16.dp)) }
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
