package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme

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
    val gearRatioConflicts by vm.gearRatioConflicts.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gear_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back_desc)
                        )
                    }
                }
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (gearRatioConflicts.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        gearRatioConflicts.forEach { (gearA, gearB) ->
                            Text(
                                text = stringResource(R.string.gear_settings_ratio_conflict, gearA, gearB),
                                style = MaterialTheme.typography.bodyMedium,
                                color = StatusColors.Warning,
                            )
                        }
                    }
                }
            }
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
            items(count = gearRatios.size, key = { it }) { index ->
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
 * (nicht parsebar, negativ, exakt 0, oder "Infinity"/"NaN" — `Float.POSITIVE_INFINITY > 0f` ist
 * `true`, ein reiner Positiv-Check allein liesse diese Werte sonst durch). Eigene Top-Level-Funktion
 * statt Inline-Lambda, damit
 * die Validierungsregel unabhaengig vom Composable testbar ist (s. `GearSettingsScreenTest`).
 *
 * Ersetzt `,` durch `.` vor dem Parsen: `String.toFloatOrNull()` akzeptiert ausschliesslich `.`
 * als Dezimaltrenner, aber diese App ist zweisprachig (Deutsch/Englisch) und manche
 * Android-Tastaturen bieten bei deutscher Locale auf einem `KeyboardType.Decimal`-Ziffernblock
 * nur `,` als Dezimaltaste an (bestaetigt fuer Samsungs Tastatur) — ohne Normalisierung koennte
 * ein deutschsprachiger Nutzer auf so einer Tastatur gar keinen Kommawert eintippen, obwohl jedes
 * der neun Felder auf diesem Screen einen Dezimalpunkt braucht.
 */
internal fun parsePositiveFloat(text: String): Float? =
    text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }

/**
 * Ein Zahlen-Eingabefeld fuer einen Getriebe-Wert. Haelt einen lokalen Text-Puffer, der nur bei
 * gueltiger, positiver Zahl an [onValueChange] weitergereicht wird — eine ungueltige Eingabe
 * (leer, Buchstaben, negativ) bleibt lokal sichtbar, ohne den zuletzt gespeicherten Wert zu
 * ueberschreiben.
 *
 * [value] kommt aus einem StateFlow, das per `stateIn(..., WhileSubscribed(5_000), DEFAULT_*)`
 * gebaut ist (s. `GearSettingsViewModel`): `collectAsState()` liest bei der ersten Komposition
 * synchron den DEFAULT_*-Konstantenwert, waehrend der echte, in AppPreferences/DataStore
 * gespeicherte Wert erst asynchron nachtraeglich eintrifft. Der lokale Puffer wird deshalb per
 * `LaunchedEffect(value)` mit dem Upstream-Wert nachgezogen — aber NUR, bis der Nutzer das erste
 * Mal in diesem Feld editiert hat ([hasBeenEdited]). Ab dann greift wieder der urspruengliche
 * Schutz: Ein Upstream-Update waehrend des Tippens (z.B. weil ein gueltiger Tastendruck ueber den
 * ViewModel-Setter ins DataStore geschrieben und als derselbe Wert zurueckgespiegelt wird) wuerde
 * sonst den Puffer neu formatieren und den Cursor ans Textende zurueckspringen lassen — ein
 * bekannter Compose-Stolperstein bei selbst-rueckgekoppelten Textfeldern.
 */
@Composable
internal fun GearRatioField(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    var hasBeenEdited by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!hasBeenEdited) {
            text = value.toString()
        }
    }

    val isValid = parsePositiveFloat(text) != null

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            hasBeenEdited = true
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

// region — Previews
//
// GearSettingsScreen selbst braucht `hiltViewModel()` und bekommt deshalb bewusst kein Preview —
// kein Screen in diesem Projekt, der einen Hilt-ViewModel voraussetzt, hat eines (AppLogsScreen,
// LoggingConfigScreen, SettingsScreen selbst eingeschlossen). Stattdessen wird die ViewModel-lose
// Bausteinkomponente GearRatioField geprevit, die alles Interessante traegt.
//
// Der Fehler-Zustand laesst sich ueber die oeffentliche API nicht direkt herbeifuehren (das
// Composable nimmt einen bereits gueltigen `value: Float` entgegen, nicht rohen Text) — ein
// nicht-positiver Startwert (hier 0f) seedet den internen Text-Puffer aber mit genau der Eingabe,
// die auch ein Nutzer-Tippfehler erzeugen wuerde, und durchlaeuft dieselbe `parsePositiveFloat`-
// Pruefung wie jede echte Eingabe.

@Preview(name = "GearRatioField · valid value (blue)", showBackground = true)
@Composable
private fun PreviewGearRatioFieldValid() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            GearRatioField(
                label = "Radumfang (mm)",
                value = 2096f,
                onValueChange = {},
            )
        }
    }
}

@Preview(name = "GearRatioField · invalid value (amber)", showBackground = true)
@Composable
private fun PreviewGearRatioFieldInvalid() {
    MaterialTheme(colorScheme = amberColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            GearRatioField(
                label = "Primärübersetzung",
                value = 0f,
                onValueChange = {},
            )
        }
    }
}

// endregion
