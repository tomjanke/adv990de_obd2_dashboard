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
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme
import java.text.NumberFormat
import java.util.Locale

/** Test-only selectors: die Zeile in der Gruppe "Fahrzeug" und das Eingabefeld des Dialogs. */
internal const val FUEL_CAPACITY_ROW_TEST_TAG = "settings_fuel_capacity_row"
internal const val FUEL_CAPACITY_FIELD_TEST_TAG = "settings_fuel_capacity_field"

/** Die Eingabe "Tankinhalt" nimmt hoechstens so viele Zeichen: "60,0" sind vier, mit Luft fuer ein getipptes "20,5" ohne Abschneiden. */
private const val MAX_CAPACITY_CHARS = 5

/** Zehntelliter als Text mit einer Nachkommastelle in der Sprache des Geraets, ohne Tausendertrenner: 200 wird "20,0" (de) bzw. "20.0" (en). */
internal fun formatTankCapacity(dl: Int, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
    isGroupingUsed = false
}.format(dl / 10.0)

/** Verbrauch in l/100 km mit einer Nachkommastelle in der Sprache des Geraets ("7,7"). */
internal fun formatConsumption(litersPer100Km: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
    isGroupingUsed = false
}.format(litersPer100Km)

/**
 * "Tankinhalt" der Tankanzeige in der Gruppe "Fahrzeug": der gespeicherte Wert in Litern (oder "Nicht festgelegt") als Zusammenfassung; ein Tipp oeffnet [FuelCapacityDialog].
 * Mit Tankinhalt berechnet die Tankanzeige ihre Reichweite aus dem Logbuch (sobald es drei Voll-zu-Voll-Intervalle gibt); ohne gilt die Reichweite wie eingegeben.
 */
@Composable
internal fun FuelCapacitySettingRow(
    capacityDl: Int,
    onCapacityChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val locale = LocalLocale.current.platformLocale
    val summary = if (capacityDl > 0) {
        stringResource(R.string.settings_fuel_capacity_summary, formatTankCapacity(capacityDl, locale))
    } else {
        stringResource(R.string.settings_fuel_capacity_not_set)
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_fuel_capacity_title)) },
        supportingContent = { Text(summary) },
        modifier = modifier
            .heightIn(min = SettingsRowMinHeight)
            .testTag(FUEL_CAPACITY_ROW_TEST_TAG)
            .clickable(role = Role.Button) { showDialog = true },
    )
    if (showDialog) {
        FuelCapacityDialog(
            initialDl = capacityDl,
            onSave = { dl ->
                onCapacityChange(dl)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

/** Eingabe des Tankinhalts in Litern ([de.adv990de.obd2.fuel.FuelCapacityLimits], Zehntelliter gespeichert); leer = loeschen. Aufbau im gemeinsamen [NumberInputDialog]. */
@Composable
internal fun FuelCapacityDialog(
    initialDl: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    NumberInputDialog(
        initialText = if (initialDl > 0) formatTankCapacity(initialDl, locale) else "",
        status = { parseFuelCapacity(it).toStatus() },
        acceptsChar = ::isLiterChar,
        maxChars = MAX_CAPACITY_CHARS,
        keyboardType = KeyboardType.Decimal,
        title = stringResource(R.string.settings_fuel_capacity_title),
        label = stringResource(R.string.settings_fuel_capacity_label),
        help = stringResource(R.string.settings_fuel_capacity_help),
        error = stringResource(R.string.settings_fuel_capacity_error),
        fieldTestTag = FUEL_CAPACITY_FIELD_TEST_TAG,
        onSave = onSave,
        onDismiss = onDismiss,
    )
}

@Preview(name = "FuelCapacitySettingRow · set · dark (blue cockpit)")
@Composable
private fun PreviewFuelCapacitySettingRowSetDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        FuelCapacitySettingRow(capacityDl = 200, onCapacityChange = {})
    }
}

@Preview(name = "FuelCapacitySettingRow · not set · light (Default scheme)")
@Composable
private fun PreviewFuelCapacitySettingRowNotSetLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        FuelCapacitySettingRow(capacityDl = 0, onCapacityChange = {})
    }
}

@Preview(name = "FuelCapacityDialog · dark (blue cockpit)")
@Composable
private fun PreviewFuelCapacityDialogDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        FuelCapacityDialog(initialDl = 205, onSave = {}, onDismiss = {})
    }
}

@Preview(name = "FuelCapacityDialog · light (Default scheme)")
@Composable
private fun PreviewFuelCapacityDialogLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        FuelCapacityDialog(initialDl = 0, onSave = {}, onDismiss = {})
    }
}
