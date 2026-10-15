package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget
import de.adv990de.obd2.ui.theme.blueColorScheme

/** Test-only selectors: die Zeile in der Gruppe "Fahrzeug" und das Eingabefeld des Dialogs. */
internal const val FUEL_RANGE_ROW_TEST_TAG = "settings_fuel_range_row"
internal const val FUEL_RANGE_FIELD_TEST_TAG = "settings_fuel_range_field"

/**
 * "Reichweite bei vollem Tank" in der Gruppe "Fahrzeug": Titel, der gespeicherte Wert (oder "Nicht festgelegt") als Zusammenfassung; ein Tipp
 * oeffnet [FuelRangeDialog]. Eine Wert-Zeile ohne Pfeil: sie fuehrt nicht auf eine Unterseite.
 */
@Composable
internal fun FuelRangeSettingRow(
    rangeFullKm: Int,
    onRangeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val summary = if (rangeFullKm > 0) {
        stringResource(R.string.settings_fuel_range_summary, rangeFullKm)
    } else {
        stringResource(R.string.settings_fuel_range_not_set)
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_fuel_range_title)) },
        supportingContent = { Text(summary) },
        modifier = modifier
            .heightIn(min = SettingsRowMinHeight)
            .testTag(FUEL_RANGE_ROW_TEST_TAG)
            .clickable(role = Role.Button) { showDialog = true },
    )
    if (showDialog) {
        FuelRangeDialog(
            initialKm = rangeFullKm,
            onSave = { km ->
                onRangeChange(km)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * Eingabe der Reichweite in ganzen Kilometern ([de.adv990de.obd2.fuel.FuelRangeLimits]). Das Feld nimmt nur Ziffern an; "Speichern" ist nur bei einer
 * gueltigen Zahl oder leerem Feld (= Reichweite loeschen, Tankanzeige aus) frei, sonst steht der Hinweis in `error`-Farbe da.
 */
@Composable
internal fun FuelRangeDialog(
    initialKm: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(if (initialKm > 0) initialKm.toString() else "") }
    val input = parseFuelRange(text)
    val invalid = input is FuelRangeInput.Invalid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_fuel_range_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c in '0'..'9' }.take(MAX_INPUT_DIGITS) },
                label = { Text(stringResource(R.string.settings_fuel_range_label)) },
                singleLine = true,
                // "Fertig" der Tastatur speichert (bei gueltiger Eingabe): im Querformat verdeckt die Tastatur die Schaltflaechen des Dialogs, dort
                // waere "Speichern" sonst nur nach dem Schliessen der Tastatur erreichbar (am Pixel 8 Pro im Querformat gesehen).
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!invalid) onSave((input as? FuelRangeInput.Valid)?.km ?: 0) }),
                isError = invalid,
                supportingText = {
                    if (invalid) {
                        Text(stringResource(R.string.settings_fuel_range_error), color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.settings_fuel_range_help))
                    }
                },
                modifier = Modifier.testTag(FUEL_RANGE_FIELD_TEST_TAG),
            )
        },
        // Ein Dialog ist ein eigenes Fenster: die 56-dp-Regel gilt hier nur, wenn sie in den Slots erneut gesetzt wird (s. ProvideMinTouchTarget).
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(
                    enabled = !invalid,
                    onClick = { onSave((input as? FuelRangeInput.Valid)?.km ?: 0) },
                ) { Text(stringResource(R.string.settings_fuel_range_save)) }
            }
        },
        dismissButton = {
            ProvideMinTouchTarget { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        },
    )
}

private const val MAX_INPUT_DIGITS = 4

@Preview(name = "FuelRangeSettingRow · set · dark (blue cockpit)")
@Composable
private fun PreviewFuelRangeSettingRowSetDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        FuelRangeSettingRow(rangeFullKm = 320, onRangeChange = {})
    }
}

@Preview(name = "FuelRangeSettingRow · not set · light (Default scheme)")
@Composable
private fun PreviewFuelRangeSettingRowNotSetLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        FuelRangeSettingRow(rangeFullKm = 0, onRangeChange = {})
    }
}

@Preview(name = "FuelRangeDialog · dark (blue cockpit)")
@Composable
private fun PreviewFuelRangeDialogDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        FuelRangeDialog(initialKm = 320, onSave = {}, onDismiss = {})
    }
}

@Preview(name = "FuelRangeDialog · light (Default scheme)")
@Composable
private fun PreviewFuelRangeDialogLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        FuelRangeDialog(initialKm = 0, onSave = {}, onDismiss = {})
    }
}
