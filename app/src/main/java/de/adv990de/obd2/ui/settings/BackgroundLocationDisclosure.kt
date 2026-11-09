package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget

/** Glove-friendly touch height of the dialog buttons (CLAUDE.md design principle 4); the M3 default of a dialog `TextButton` is only 40 dp. */
private val DialogButtonMinHeight = 56.dp

/**
 * Die Erklaerung VOR der Anfrage nach dem Hintergrund-Standort (`ACCESS_BACKGROUND_LOCATION`) — die „Prominent Disclosure“ der Play-Richtlinie zu Standortdaten:
 * sie steht in der App, im normalen Ablauf (wenn „Geschwindigkeit per GPS“ eingeschaltet wird), nennt **was** erfasst wird (der Standort), **wozu**
 * (Geschwindigkeit, Ganganzeige, Strecke der Tankanzeige, geschaetzter Kilometerstand im Logbuch), **dass es im Hintergrund weiterlaeuft**, **wohin es nicht geht**
 * (nirgends hin, nur Geschwindigkeit und Strecke werden gespeichert, nie Positionen) und **dass es freiwillig ist**.
 *
 * Zwei Antworten und keine dritte: „Weiter“ ([onContinue]) ist die einzige Zustimmung und fuehrt zur System-Anfrage; „Nicht jetzt“ ([onNotNow]) — und auch
 * Wegtippen und Zurueck (`onDismissRequest`) — fragt nichts an. Was auf die Antwort folgt, steht in `backgroundDisclosureOutcome` (rein); dass die System-Anfrage nur
 * hinter „Weiter“ steht, haelt `BackgroundDisclosureWiringSourceTest` fest.
 *
 * Ein plain Material-3-[AlertDialog] (kein Bottom Sheet, s. CLAUDE.md „Verbotene Patterns“). Der Text ist laenger als bei den uebrigen Dialogen und scrollt, damit er im
 * Querformat und bei grosser Schrift erreichbar bleibt; die beiden Knoepfe stehen ausserhalb des Scrollbereichs und sind immer sichtbar.
 */
@Composable
internal fun BackgroundLocationDisclosureDialog(onContinue: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(stringResource(R.string.bg_location_disclosure_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.bg_location_disclosure_body))
            }
        },
        // Ein Dialog ist ein eigenes Fenster: die 56-dp-Regel aus AppTheme gilt nur, wenn sie in den Slots erneut gesetzt wird (s. ProvideMinTouchTarget).
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onContinue, modifier = Modifier.heightIn(min = DialogButtonMinHeight)) {
                    Text(stringResource(R.string.bg_location_disclosure_continue))
                }
            }
        },
        dismissButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = DialogButtonMinHeight)) {
                    Text(stringResource(R.string.bg_location_disclosure_not_now))
                }
            }
        },
    )
}

// region — Previews

@Preview(name = "BackgroundLocationDisclosureDialog · light", showBackground = true)
@Composable
private fun PreviewBackgroundLocationDisclosureDialogLight() {
    PreviewTheme(dark = false) { BackgroundLocationDisclosureDialog(onContinue = {}, onNotNow = {}) }
}

@Preview(name = "BackgroundLocationDisclosureDialog · dark", showBackground = true)
@Composable
private fun PreviewBackgroundLocationDisclosureDialogDark() {
    PreviewTheme(dark = true) { BackgroundLocationDisclosureDialog(onContinue = {}, onNotNow = {}) }
}

// endregion
