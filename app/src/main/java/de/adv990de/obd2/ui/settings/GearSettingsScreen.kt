package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GearSettingsScreen(
    navController: NavController,
    vm: GearSettingsViewModel = hiltViewModel(),
) {
    val wheelCircumferenceMm by vm.wheelCircumferenceMm.collectAsState()
    val primaryRatio by vm.primaryRatio.collectAsState()
    val secondaryRatio by vm.secondaryRatio.collectAsState()
    val gearRatios by vm.gearRatios.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gear_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.gear_settings_back_desc)
                        )
                    }
                }
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                GearRatioField(
                    label = stringResource(R.string.gear_settings_wheel_circumference),
                    value = wheelCircumferenceMm,
                    onValueChange = vm::setWheelCircumferenceMm,
                )
            }
            item {
                GearRatioField(
                    label = stringResource(R.string.gear_settings_primary_ratio),
                    value = primaryRatio,
                    onValueChange = vm::setPrimaryRatio,
                )
            }
            item {
                GearRatioField(
                    label = stringResource(R.string.gear_settings_secondary_ratio),
                    value = secondaryRatio,
                    onValueChange = vm::setSecondaryRatio,
                )
            }
            items(gearRatios.size) { index ->
                GearRatioField(
                    label = stringResource(R.string.gear_settings_gear_n, index + 1),
                    value = gearRatios[index],
                    onValueChange = { vm.setGearRatio(index, it) },
                )
            }
        }
    }
}

/**
 * Parst eine Nutzereingabe als positive Gleitkommazahl, oder `null` bei ungueltiger Eingabe
 * (nicht parsebar, negativ, oder exakt 0). Eigene Top-Level-Funktion statt Inline-Lambda, damit
 * die Validierungsregel unabhaengig vom Composable testbar ist (s. `GearSettingsScreenTest`).
 */
internal fun parsePositiveFloat(text: String): Float? = text.toFloatOrNull()?.takeIf { it > 0f }

/**
 * Ein Zahlen-Eingabefeld fuer einen Getriebe-Wert. Haelt einen lokalen Text-Puffer, der nur bei
 * gueltiger, positiver Zahl an [onValueChange] weitergereicht wird — eine ungueltige Eingabe
 * (leer, Buchstaben, negativ) bleibt lokal sichtbar, ohne den zuletzt gespeicherten Wert zu
 * ueberschreiben.
 *
 * Bewusst `remember { }` OHNE Schluessel auf [value]: Wuerde der Puffer bei jeder Aenderung von
 * [value] neu aus dem (nach jedem gueltigen Tastendruck aktualisierten) State gesetzt, spraenge
 * der Cursor beim Tippen zurueck ans Textende — ein bekannter Compose-Stolperstein bei
 * selbst-rueckgekoppelten Textfeldern.
 */
@Composable
private fun GearRatioField(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    val isValid = parsePositiveFloat(text) != null

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            parsePositiveFloat(newText)?.let(onValueChange)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = !isValid,
        supportingText = if (!isValid) {
            { Text(stringResource(R.string.gear_settings_invalid_value), color = MaterialTheme.colorScheme.error) }
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
