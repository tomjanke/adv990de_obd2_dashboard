package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme

/** Test-only selectors: die Zeile in der Gruppe "Fahrzeug" und das Eingabefeld des Dialogs. */
internal const val FUEL_RESERVE_ROW_TEST_TAG = "settings_fuel_reserve_row"
internal const val FUEL_RESERVE_FIELD_TEST_TAG = "settings_fuel_reserve_field"

/**
 * "Reserve" der Tankanzeige in der Gruppe "Fahrzeug": der gespeicherte Wert in km Restreichweite (oder "Aus") als Zusammenfassung; ein Tipp oeffnet [FuelReserveDialog]. Die Reserve
 * ist eine Marke im Balken der Tankzeile, unter der die Zahl rot wird — kein Alarm, kein Ton.
 */
@Composable
internal fun FuelReserveSettingRow(
    reserveKm: Int,
    onReserveChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val summary = if (reserveKm > 0) {
        stringResource(R.string.settings_fuel_reserve_summary, reserveKm)
    } else {
        stringResource(R.string.settings_fuel_reserve_off)
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_fuel_reserve_title)) },
        supportingContent = { Text(summary) },
        modifier = modifier
            .heightIn(min = SettingsRowMinHeight)
            .testTag(FUEL_RESERVE_ROW_TEST_TAG)
            .clickable(role = Role.Button) { showDialog = true },
    )
    if (showDialog) {
        FuelReserveDialog(
            initialKm = reserveKm,
            onSave = { km ->
                onReserveChange(km)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

/** Eingabe der Reserve in ganzen Kilometern ([de.adv990de.obd2.fuel.FuelReserveLimits]); leer = aus. Aufbau im gemeinsamen [NumberInputDialog]. */
@Composable
internal fun FuelReserveDialog(
    initialKm: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    NumberInputDialog(
        initialText = if (initialKm > 0) initialKm.toString() else "",
        status = { parseFuelReserve(it).toStatus() },
        acceptsChar = ::isAsciiDigit,
        maxChars = MAX_RESERVE_DIGITS,
        keyboardType = KeyboardType.Number,
        title = stringResource(R.string.settings_fuel_reserve_title),
        label = stringResource(R.string.settings_fuel_reserve_label),
        help = stringResource(R.string.settings_fuel_reserve_help),
        error = stringResource(R.string.settings_fuel_reserve_error),
        fieldTestTag = FUEL_RESERVE_FIELD_TEST_TAG,
        onSave = onSave,
        onDismiss = onDismiss,
    )
}

private const val MAX_RESERVE_DIGITS = 3

@Preview(name = "FuelReserveSettingRow · set · dark (blue cockpit)")
@Composable
private fun PreviewFuelReserveSettingRowSetDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        FuelReserveSettingRow(reserveKm = 40, onReserveChange = {})
    }
}

@Preview(name = "FuelReserveSettingRow · off · light (Default scheme)")
@Composable
private fun PreviewFuelReserveSettingRowOffLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        FuelReserveSettingRow(reserveKm = 0, onReserveChange = {})
    }
}

@Preview(name = "FuelReserveDialog · dark (blue cockpit)")
@Composable
private fun PreviewFuelReserveDialogDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        FuelReserveDialog(initialKm = 40, onSave = {}, onDismiss = {})
    }
}

@Preview(name = "FuelReserveDialog · light (Default scheme)")
@Composable
private fun PreviewFuelReserveDialogLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        FuelReserveDialog(initialKm = 0, onSave = {}, onDismiss = {})
    }
}
