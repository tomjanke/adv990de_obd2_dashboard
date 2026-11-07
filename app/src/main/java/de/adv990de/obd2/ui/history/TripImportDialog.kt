package de.adv990de.obd2.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget
import de.adv990de.obd2.ui.transfer.TripsPreviewBody
import de.adv990de.obd2.ui.transfer.previewTripPlan

/** Test tags of the buttons and the preview body. */
internal const val TRIP_IMPORT_CONFIRM_TEST_TAG = "trip_import_confirm"
internal const val TRIP_IMPORT_CLOSE_TEST_TAG = "trip_import_close"
internal const val TRIP_IMPORT_CANCEL_TEST_TAG = "trip_import_cancel"
internal const val TRIP_IMPORT_BODY_TEST_TAG = "trip_import_body"

/** Glove-friendly touch height of the dialog buttons (the M3 default of a dialog `TextButton` is only 40 dp). */
private val DialogButtonMinHeight = 56.dp

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
        text = { TripsPreviewBody(plan, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(TRIP_IMPORT_BODY_TEST_TAG)) },
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

// region — Previews
//
// Ein Dialog ist ein eigenes Fenster: jede Vorschau zeigt ihn in einer Flaeche.

@Preview(name = "TripImportDialog · preview (light)", showBackground = true)
@Composable
private fun PreviewTripImportDialogPreviewLight() {
    PreviewTheme(dark = false) {
        TripImportDialog(
            TripImportState.Preview(previewTripPlan(listOf(RejectedRow(17, "timestamp"), RejectedRow(23, "value")), rejectedCount = 2, duplicates = 4, expired = 1)),
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
