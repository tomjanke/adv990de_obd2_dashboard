package de.adv990de.obd2.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.tripimport.RejectedRow
import de.adv990de.obd2.tripimport.TripImportPlan
import de.adv990de.obd2.tripimport.readTripCsv
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.formatDateTime
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget
import java.io.StringReader

/** Test tags of the buttons and the preview body. */
internal const val TRIP_IMPORT_CONFIRM_TEST_TAG = "trip_import_confirm"
internal const val TRIP_IMPORT_CLOSE_TEST_TAG = "trip_import_close"
internal const val TRIP_IMPORT_CANCEL_TEST_TAG = "trip_import_cancel"
internal const val TRIP_IMPORT_BODY_TEST_TAG = "trip_import_body"

/** Glove-friendly touch height of the dialog buttons (the M3 default of a dialog `TextButton` is only 40 dp). */
private val DialogButtonMinHeight = 56.dp

/** Hoechstens so viele abgelehnte Zeilen nennt die Vorschau einzeln; der Rest steht als "… und N weitere". */
internal const val MAX_LISTED_REJECTED_ROWS = 20

/**
 * Der Dialog des Fahrten-Imports: Arbeit ([TripImportState.Working]), die Vorschau mit den Zahlen und den abgelehnten Zeilen ([TripImportState.Preview] — erst "Importieren"
 * schreibt), das Ergebnis ([TripImportState.Done]) oder der Grund eines Scheiterns ([TripImportState.Failed]). Bei [TripImportState.Idle] ist nichts zu sehen. Ein [AlertDialog],
 * kein Bottom Sheet; `ProvideMinTouchTarget` in den Slots (eigenes Fenster).
 */
@Composable
internal fun TripImportDialog(state: TripImportState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        TripImportState.Idle -> Unit
        TripImportState.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.trip_import_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.trip_import_working))
                }
            },
            confirmButton = {},
        )
        is TripImportState.Preview -> PreviewDialog(state.plan, onConfirm, onDismiss)
        is TripImportState.Done -> MessageDialog(pluralStringResource(R.plurals.trip_import_done, state.tripCount, state.tripCount), onDismiss)
        is TripImportState.Failed -> MessageDialog(state.text.asString(), onDismiss)
    }
}

@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    ProvideMinTouchTarget {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = DialogButtonMinHeight).testTag(TRIP_IMPORT_CLOSE_TEST_TAG)) {
            Text(stringResource(R.string.trip_import_close))
        }
    }
}

@Composable
private fun MessageDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trip_import_title)) },
        text = { Text(message) },
        confirmButton = { CloseButton(onDismiss) },
    )
}

@Composable
private fun PreviewDialog(plan: TripImportPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val nothingNew = plan.trips.isEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trip_import_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(TRIP_IMPORT_BODY_TEST_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            stringResource(R.string.trip_import_row, row.line, rowReasonText(row)),
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
        },
        confirmButton = {
            if (nothingNew) CloseButton(onDismiss)
            else ProvideMinTouchTarget {
                TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = DialogButtonMinHeight).testTag(TRIP_IMPORT_CONFIRM_TEST_TAG)) {
                    Text(stringResource(R.string.trip_import_confirm))
                }
            }
        },
        dismissButton = if (nothingNew) null else {
            {
                ProvideMinTouchTarget {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = DialogButtonMinHeight).testTag(TRIP_IMPORT_CANCEL_TEST_TAG)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        },
    )
}

/** Der Grund einer abgelehnten Zeile: die Spalte, deren Wert nicht lesbar oder nicht plausibel war. */
@Composable
private fun rowReasonText(row: RejectedRow): String = stringResource(R.string.trip_import_bad_value, row.column)

// region — Previews
//
// Ein Dialog ist ein eigenes Fenster: jede Vorschau zeigt ihn in einer Flaeche.

private const val PREVIEW_START_MS = 1_791_000_000_000L

/** Neun Fahrten zu je 120 Messwerten, gelesen aus einer kleinen CSV-Datei wie der Export sie schreibt. */
private fun previewPlan(rejected: List<RejectedRow>, rejectedCount: Int, duplicates: Int, expired: Int): TripImportPlan {
    val text = buildString {
        append("timestamp,pid,value,name,source,session\n")
        for (trip in 0 until 9) for (row in 0 until 120) {
            append(PREVIEW_START_MS + trip * 7_200_000L + row * 1_000L).append(",010C,").append(1_500 + row).append(".0,Engine RPM,ECU,").append(trip + 1).append('\n')
        }
    }
    val parsed = readTripCsv(StringReader(text), PREVIEW_START_MS + 365L * 24 * 3_600_000)
    return TripImportPlan(parsed.trips, duplicates, expired, rejected, rejectedCount)
}

@Preview(name = "TripImportDialog · preview (light)", showBackground = true)
@Composable
private fun PreviewTripImportDialogPreviewLight() {
    PreviewTheme(dark = false) {
        TripImportDialog(
            TripImportState.Preview(previewPlan(listOf(RejectedRow(17, "timestamp"), RejectedRow(23, "value")), rejectedCount = 2, duplicates = 4, expired = 1)),
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "TripImportDialog · nothing new (dark)", showBackground = true)
@Composable
private fun PreviewTripImportDialogNothingNewDark() {
    PreviewTheme(dark = true) {
        TripImportDialog(TripImportState.Preview(TripImportPlan(emptyList(), duplicateTrips = 13, expiredTrips = 0, rejected = emptyList(), rejectedCount = 0)), onConfirm = {}, onDismiss = {})
    }
}

@Preview(name = "TripImportDialog · done (light)", showBackground = true)
@Composable
private fun PreviewTripImportDialogDoneLight() {
    PreviewTheme(dark = false) { TripImportDialog(TripImportState.Done(9), onConfirm = {}, onDismiss = {}) }
}

@Preview(name = "TripImportDialog · failed (dark)", showBackground = true)
@Composable
private fun PreviewTripImportDialogFailedDark() {
    PreviewTheme(dark = true) {
        TripImportDialog(TripImportState.Failed(UiText.StringResource(R.string.trip_import_error_format)), onConfirm = {}, onDismiss = {})
    }
}

// endregion
