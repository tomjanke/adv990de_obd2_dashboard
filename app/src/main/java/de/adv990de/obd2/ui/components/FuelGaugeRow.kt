package de.adv990de.obd2.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.fuel.FuelGaugeState
import de.adv990de.obd2.fuel.FuelLevel
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.blueColorScheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Test-only selectors: die ganze Zeile (Long-Press), die Spur und die Fuellung des Balkens. */
internal const val FUEL_GAUGE_ROW_TEST_TAG = "fuel_gauge_row"
internal const val FUEL_GAUGE_TRACK_TEST_TAG = "fuel_gauge_track"
internal const val FUEL_GAUGE_FILL_TEST_TAG = "fuel_gauge_fill"

/** Halbe Blinkperiode der untersten Stufe: 500 ms an, 500 ms aus. */
internal const val FUEL_BLINK_HALF_PERIOD_MS = 500L

/** Deckkraft der Fuellung in der "aus"-Phase des Blinkens — nicht ganz weg, damit der Balken als Form lesbar bleibt. */
internal const val FUEL_BLINK_OFF_ALPHA = 0.15f

/** Hoehe der Zeile: 40 dp mit dem 16-dp-Balken in der Mitte = 12 dp Luft oben und unten (Nutzerwunsch 2026-10-15: engerer Abstand; vorher 56 dp mit 20 dp). */
internal val FUEL_ROW_HEIGHT = 40.dp

private val FuelBarHeight = 16.dp
private val FuelIconSize = 24.dp

/**
 * Die Farbe der Balkenfuellung je Stufe: Ampel-Semantik (Gruen/Gelb/Rot), eine dokumentierte Ausnahme wie Bluetooth-Punkt, Oel-Balken und "N"
 * (CLAUDE.md "Dokumentierte Ausnahme — Tankanzeige"). Reine Funktion; das feste Ampel-Gruen (`StatusColors.TrafficLightGreen`), nicht
 * Theme-`tertiary`, das nur im gruenen Cockpit-Schema gruen ist.
 */
internal fun fuelBarColor(level: FuelLevel): Color = when (level) {
    FuelLevel.Green -> StatusColors.TrafficLightGreen
    FuelLevel.Yellow -> StatusColors.Warning
    FuelLevel.Red, FuelLevel.RedBlinking -> StatusColors.Critical
}

/**
 * Die virtuelle Tankanzeige (Nutzerwunsch 2026-10-15): ein Streckenzaehler, der als Balken ganz unten im **Dashboard** steht (direkt ueber dem
 * Hauptmenue bzw. der Statuszeile; nur dort, nicht auf den anderen Seiten — Nutzerwunsch 2026-10-15).
 * Links das Tankstellen-Symbol (macht klar, dass es eine Tankanzeige ist), daneben ueber die ganze uebrige Breite der Balken — er fuellt
 * sich von links mit dem Anteil der Restreichweite ([FuelGaugeState.fraction]) und wechselt die Farbe nach [FuelLevel]; unter 5 %
 * blinkt er rot.
 *
 * Die Zeile ist [FUEL_ROW_HEIGHT] hoch (40 dp, volle Breite), der Balken darin 16 dp hoch und zentriert. **Bewusste Ausnahme von der 56-dp-Regel fuer
 * Touch-Ziele** (CLAUDE.md): der Nutzer wollte weniger Abstand um den Balken; der Long-Press ist eine seltene, ueber eine Rueckfrage abgesicherte
 * Handlung, die Zeile ist dafuer ueber die ganze Breite beruehrbar. Ein Long-Press — der Standard des Systems, ca. 0,4 s — auf Symbol oder Balken ruft [onResetRequested]; die Rueckfrage stellt der Aufrufer. Ein
 * einfacher Tipp tut nichts: die Zeile steht dauerhaft im Blick des Fahrers und darf nicht aus Versehen etwas ausloesen.
 *
 * Die Blink-Schleife laeuft nur, solange die Stufe blinkt (wie der Drehzahlbalken: keine Dauerschleife ohne Bedarf).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FuelGaugeRow(
    state: FuelGaugeState,
    onResetRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val level = state.level
    val blinkOn = rememberFuelBlinkPhase(active = level.blinks)
    val description = stringResource(R.string.fuel_gauge_desc, state.remainingKm.roundToInt(), state.percent)
    val resetAction = stringResource(R.string.fuel_gauge_reset_action)
    val fillColor = fuelBarColor(level).let { if (blinkOn) it else it.copy(alpha = FUEL_BLINK_OFF_ALPHA) }

    Column(modifier.fillMaxWidth()) {
        HorizontalDivider()
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(FUEL_ROW_HEIGHT)
                .testTag(FUEL_GAUGE_ROW_TEST_TAG)
                .combinedClickable(onClick = {}, onLongClick = onResetRequested, onLongClickLabel = resetAction)
                .semantics { contentDescription = description },
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.LocalGasStation,
                    contentDescription = null,
                    modifier = Modifier.size(FuelIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(FuelBarHeight)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag(FUEL_GAUGE_TRACK_TEST_TAG),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(state.fraction)
                            .background(fillColor)
                            .testTag(FUEL_GAUGE_FILL_TEST_TAG),
                    )
                }
            }
        }
    }
}

/** `true` = Fuellung sichtbar. Wechselt alle [FUEL_BLINK_HALF_PERIOD_MS], solange [active]; sonst immer `true` (und keine Schleife). */
@Composable
private fun rememberFuelBlinkPhase(active: Boolean): Boolean {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(active) {
        on = true
        while (active) {
            delay(FUEL_BLINK_HALF_PERIOD_MS)
            on = !on
        }
    }
    return on
}

/**
 * Rueckfrage vor dem Zuruecksetzen der Tankanzeige (nach dem Tanken). Ein Long-Press ist leicht aus Versehen gemacht — die Anzeige springt
 * auf "voll", und das waere eine falsche Aussage.
 */
@Composable
internal fun FuelResetDialog(
    rangeFullKm: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fuel_reset_title)) },
        text = { Text(stringResource(R.string.fuel_reset_message, rangeFullKm)) },
        // Ein Dialog ist ein eigenes Fenster: die 56-dp-Regel gilt hier nur, wenn sie in den Slots erneut gesetzt wird (s. ProvideMinTouchTarget).
        confirmButton = {
            ProvideMinTouchTarget { TextButton(onClick = onConfirm) { Text(stringResource(R.string.fuel_reset_confirm)) } }
        },
        dismissButton = {
            ProvideMinTouchTarget { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        },
    )
}

@Preview(name = "FuelGaugeRow · green · dark (blue cockpit)")
@Composable
private fun PreviewFuelGaugeRowGreenDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        FuelGaugeRow(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 70f), onResetRequested = {})
    }
}

@Preview(name = "FuelGaugeRow · yellow · light (Default scheme)")
@Composable
private fun PreviewFuelGaugeRowYellowLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        FuelGaugeRow(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 220f), onResetRequested = {})
    }
}

@Preview(name = "FuelGaugeRow · red · dark (blue cockpit)")
@Composable
private fun PreviewFuelGaugeRowRedDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        FuelGaugeRow(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 280f), onResetRequested = {})
    }
}

@Preview(name = "FuelGaugeRow · almost empty (blinking) · light (Default scheme)")
@Composable
private fun PreviewFuelGaugeRowBlinkingLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        FuelGaugeRow(FuelGaugeState(rangeFullKm = 320, kmSinceFill = 310f), onResetRequested = {})
    }
}

@Preview(name = "FuelResetDialog · dark (blue cockpit)")
@Composable
private fun PreviewFuelResetDialogDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        FuelResetDialog(rangeFullKm = 320, onConfirm = {}, onDismiss = {})
    }
}

@Preview(name = "FuelResetDialog · light (Default scheme)")
@Composable
private fun PreviewFuelResetDialogLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        FuelResetDialog(rangeFullKm = 320, onConfirm = {}, onDismiss = {})
    }
}
