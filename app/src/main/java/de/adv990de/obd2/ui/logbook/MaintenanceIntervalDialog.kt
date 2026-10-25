package de.adv990de.obd2.ui.logbook

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import de.adv990de.obd2.R
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.MaintenanceIntervalInput
import de.adv990de.obd2.logbook.parseMaintenanceInterval
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget

/** Test tags: das Eingabefeld und die Schaltflaeche "Speichern" des Dialogs. */
internal const val INTERVAL_FIELD_TEST_TAG = "logbook_interval_field"
internal const val INTERVAL_SAVE_TEST_TAG = "logbook_interval_save"

/** Hoechstens so viele Ziffern nimmt das Feld an ([de.adv990de.obd2.logbook.MaintenanceIntervalLimits.MAX_KM] hat sechs). */
private const val MAX_INPUT_DIGITS = 6

/**
 * Das Intervall einer Wartungsart in ganzen Kilometern festlegen ([de.adv990de.obd2.logbook.MaintenanceIntervalLimits]); [initialKm] ist der gespeicherte Wert (`null` = keiner). Das Feld nimmt nur
 * Ziffern an; "Speichern" ist nur bei einer gueltigen Zahl oder leerem Feld (= Intervall loeschen, [onSave] mit 0) frei, sonst steht der Hinweis in `error`-Farbe da. Ein Dialog ist ein eigenes
 * Fenster: die 56-dp-Regel gilt in den Slots nur mit [ProvideMinTouchTarget]. "Fertig" der Tastatur speichert bei gueltiger Eingabe (im Querformat verdeckt die Tastatur sonst die Schaltflaechen).
 */
@Composable
internal fun MaintenanceIntervalDialog(
    type: LogType,
    initialKm: Int?,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialKm?.toString().orEmpty()) }
    val input = parseMaintenanceInterval(text)
    val invalid = input is MaintenanceIntervalInput.Invalid
    val km = (input as? MaintenanceIntervalInput.Valid)?.km ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logbook_interval_title, stringResource(type.labelRes()))) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c in '0'..'9' }.take(MAX_INPUT_DIGITS) },
                label = { Text(stringResource(R.string.logbook_interval_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!invalid) onSave(km) }),
                isError = invalid,
                supportingText = {
                    if (invalid) Text(stringResource(R.string.logbook_interval_error), color = MaterialTheme.colorScheme.error)
                    else Text(stringResource(R.string.logbook_interval_help))
                },
                modifier = Modifier.testTag(INTERVAL_FIELD_TEST_TAG),
            )
        },
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(enabled = !invalid, onClick = { onSave(km) }, modifier = Modifier.testTag(INTERVAL_SAVE_TEST_TAG)) { Text(stringResource(R.string.logbook_save)) }
            }
        },
        dismissButton = { ProvideMinTouchTarget { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } } },
    )
}

// region — Previews

@Preview(name = "Maintenance interval dialog (light)")
@Composable
private fun PreviewMaintenanceIntervalDialogLight() {
    PreviewTheme(dark = false) { MaintenanceIntervalDialog(LogType.CHAIN_SMALL, initialKm = 1_000, onSave = {}, onDismiss = {}) }
}

@Preview(name = "Maintenance interval dialog (dark)")
@Composable
private fun PreviewMaintenanceIntervalDialogDark() {
    PreviewTheme(dark = true) { MaintenanceIntervalDialog(LogType.SERVICE_LARGE, initialKm = null, onSave = {}, onDismiss = {}) }
}

// endregion
