package de.adv990de.obd2.ui.settings

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
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget

/** Was die Pruefung einer Zahleneingabe ergibt: [Invalid] (nicht speichern, Hinweis zeigen) oder [Save] mit dem Wert; 0 = Einstellung loeschen (leeres Feld). */
internal sealed interface InputStatus {
    data object Invalid : InputStatus
    data class Save(val value: Int) : InputStatus
}

internal fun FuelRangeInput.toStatus(): InputStatus = when (this) {
    FuelRangeInput.Cleared -> InputStatus.Save(0)
    is FuelRangeInput.Valid -> InputStatus.Save(km)
    FuelRangeInput.Invalid -> InputStatus.Invalid
}

internal fun FuelCapacityInput.toStatus(): InputStatus = when (this) {
    FuelCapacityInput.Cleared -> InputStatus.Save(0)
    is FuelCapacityInput.Valid -> InputStatus.Save(dl)
    FuelCapacityInput.Invalid -> InputStatus.Invalid
}

/**
 * Der gemeinsame Dialog fuer eine Einstellung als Zahl (Reichweite bei vollem Tank, Reserve und Tankinhalt der Tankanzeige): ein Feld, das nur die erlaubten Zeichen annimmt
 * ([acceptsChar], hoechstens [maxChars]), "Speichern" nur bei einer gueltigen Eingabe oder leerem Feld (= Einstellung loeschen), sonst steht der Hinweis in `error`-Farbe da.
 * [status] ist die reine Pruefung ([parseFuelRange], [parseFuelReserve], [parseFuelCapacity], jeweils als [InputStatus]); [initialText] ist der Text des Feldes beim Oeffnen
 * (leer = nicht festgelegt). Eigene Texte je Einstellung ueber [title], [label], [help], [error].
 */
@Composable
internal fun NumberInputDialog(
    initialText: String,
    status: (String) -> InputStatus,
    acceptsChar: (Char) -> Boolean,
    maxChars: Int,
    keyboardType: KeyboardType,
    title: String,
    label: String,
    help: String,
    error: String,
    fieldTestTag: String,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    val current = status(text)
    val invalid = current is InputStatus.Invalid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(acceptsChar).take(maxChars) },
                label = { Text(label) },
                singleLine = true,
                // "Fertig" der Tastatur speichert (bei gueltiger Eingabe): im Querformat verdeckt die Tastatur die Schaltflaechen des Dialogs, dort
                // waere "Speichern" sonst nur nach dem Schliessen der Tastatur erreichbar (am Pixel 8 Pro im Querformat gesehen).
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { (current as? InputStatus.Save)?.let { onSave(it.value) } }),
                isError = invalid,
                supportingText = {
                    if (invalid) Text(error, color = MaterialTheme.colorScheme.error) else Text(help)
                },
                modifier = Modifier.testTag(fieldTestTag),
            )
        },
        // Ein Dialog ist ein eigenes Fenster: die 56-dp-Regel gilt hier nur, wenn sie in den Slots erneut gesetzt wird (s. ProvideMinTouchTarget).
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(
                    enabled = !invalid,
                    onClick = { (current as? InputStatus.Save)?.let { onSave(it.value) } },
                ) { Text(stringResource(R.string.settings_fuel_range_save)) }
            }
        },
        dismissButton = {
            ProvideMinTouchTarget { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        },
    )
}

/** Ganze Kilometer: nur Ziffern. */
internal fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

/** Liter: Ziffern, Punkt und Komma (die Pruefung [parseFuelCapacity] verlangt hoechstens ein Trennzeichen und eine Nachkommastelle). */
internal fun isLiterChar(c: Char): Boolean = c in '0'..'9' || c == '.' || c == ','
