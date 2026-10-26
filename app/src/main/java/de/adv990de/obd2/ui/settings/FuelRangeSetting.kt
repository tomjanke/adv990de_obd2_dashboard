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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.fuel.LearnedRange
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme

/** Test-only selectors: die Zeile in der Gruppe "Fahrzeug" und das Eingabefeld des Dialogs. */
internal const val FUEL_RANGE_ROW_TEST_TAG = "settings_fuel_range_row"
internal const val FUEL_RANGE_FIELD_TEST_TAG = "settings_fuel_range_field"

/**
 * "Reichweite bei vollem Tank" in der Gruppe "Fahrzeug": Titel, der gespeicherte Wert (oder "Nicht festgelegt") als Zusammenfassung; ein Tipp
 * oeffnet [FuelRangeDialog]. Eine Wert-Zeile ohne Pfeil: sie fuehrt nicht auf eine Unterseite. Mit [learned] nennt die Zusammenfassung die aus dem Logbuch berechnete Reichweite
 * (mit ihrem Verbrauch) und, falls vorhanden, die manuelle als Rueckfall.
 */
@Composable
internal fun FuelRangeSettingRow(
    rangeFullKm: Int,
    onRangeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    learned: LearnedRange? = null,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val locale = LocalLocale.current.platformLocale
    val summary = when {
        // Die Tankanzeige rechnet mit der berechneten Reichweite; die manuelle Zahl bleibt der Rueckfall (ohne Tankinhalt oder bei zu wenigen Intervallen).
        learned != null && rangeFullKm > 0 -> stringResource(
            R.string.settings_fuel_range_summary_learned, learned.km, formatConsumption(learned.litersPer100Km, locale), rangeFullKm,
        )
        learned != null -> stringResource(R.string.settings_fuel_range_summary_learned_only, learned.km, formatConsumption(learned.litersPer100Km, locale))
        rangeFullKm > 0 -> stringResource(R.string.settings_fuel_range_summary, rangeFullKm)
        else -> stringResource(R.string.settings_fuel_range_not_set)
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
 * Eingabe der Reichweite in ganzen Kilometern ([de.adv990de.obd2.fuel.FuelRangeLimits]); Verhalten und Aufbau im gemeinsamen [NumberInputDialog] (Ziffern, Speichern nur bei gueltiger Zahl oder
 * leerem Feld = Reichweite loeschen, Tankanzeige aus).
 */
@Composable
internal fun FuelRangeDialog(
    initialKm: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    NumberInputDialog(
        initialText = if (initialKm > 0) initialKm.toString() else "",
        status = { parseFuelRange(it).toStatus() },
        acceptsChar = ::isAsciiDigit,
        maxChars = MAX_INPUT_DIGITS,
        keyboardType = KeyboardType.Number,
        title = stringResource(R.string.settings_fuel_range_title),
        label = stringResource(R.string.settings_fuel_range_label),
        help = stringResource(R.string.settings_fuel_range_help),
        error = stringResource(R.string.settings_fuel_range_error),
        fieldTestTag = FUEL_RANGE_FIELD_TEST_TAG,
        onSave = onSave,
        onDismiss = onDismiss,
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

@Preview(name = "FuelRangeSettingRow · learned from the logbook · dark (blue cockpit)")
@Composable
private fun PreviewFuelRangeSettingRowLearnedDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        FuelRangeSettingRow(rangeFullKm = 320, onRangeChange = {}, learned = LearnedRange(km = 260, litersPer100Km = 7.7))
    }
}
