package de.adv990de.obd2.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.adv990de.obd2.R
import de.adv990de.obd2.fuel.FUEL_SEGMENT_COUNT
import de.adv990de.obd2.fuel.FuelGaugeState
import de.adv990de.obd2.fuel.FuelLevel
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.asLiveValue
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.blueColorScheme
import kotlinx.coroutines.delay

/** Test-only selectors: die ganze Zeile (Long-Press) und die Spur, auf der die zehn Segmente stehen. */
internal const val FUEL_GAUGE_ROW_TEST_TAG = "fuel_gauge_row"
internal const val FUEL_GAUGE_TRACK_TEST_TAG = "fuel_gauge_track"

/** Test-only selectors (F5): die Box mit Zahl und Einheit, die Zahl selbst und ihre Einheit. */
internal const val FUEL_GAUGE_READOUT_TEST_TAG = "fuel_gauge_readout"
internal const val FUEL_GAUGE_RANGE_TEST_TAG = "fuel_gauge_range"
internal const val FUEL_GAUGE_UNIT_TEST_TAG = "fuel_gauge_unit"

/** Halbe Blinkperiode der untersten Stufe: 500 ms an, 500 ms aus. */
internal const val FUEL_BLINK_HALF_PERIOD_MS = 500L

/** Deckkraft der leuchtenden Segmente in der "aus"-Phase des Blinkens — nicht ganz weg, damit der Balken als Form lesbar bleibt. */
internal const val FUEL_BLINK_OFF_ALPHA = 0.15f

/** Hoehe der Zeile: 40 dp mit dem 16-dp-Balken in der Mitte = 12 dp Luft oben und unten (Nutzerwunsch 2026-10-15: engerer Abstand; vorher 56 dp mit 20 dp). */
internal val FUEL_ROW_HEIGHT = 40.dp

/**
 * Linker Rand der Tankzeile (Nutzerwunsch 2026-10-15: Tankstellen-Symbol und Oelkanne naeher am Rand): 8 dp + 4 dp Eigenrand des Material-Symbols = **12 dp**
 * sichtbar (vorher 16 dp Rand, 20 dp sichtbar). Die Oelkanne im Drehzahlbalken leitet ihren Rand davon ab (`OIL_ROW_START_PADDING`),
 * damit beide an derselben sichtbaren Kante stehen — wer diesen Wert aendert, verschiebt die Kanne mit.
 */
internal val FUEL_ROW_START_PADDING = 8.dp

/** Rechter Rand der Tankzeile: der Balken endet **12 dp** vor dem Bildschirmrand (vorher 16 dp) — dieselbe sichtbare Randbreite wie links beim Symbol (Nutzerwunsch 2026-10-15). */
internal val FUEL_ROW_END_PADDING = 12.dp

private val FuelBarHeight = 16.dp
private val FuelIconSize = 24.dp

/**
 * Die gemeinsame Grundlinie von Symbol, Zahl und Einheit (Nutzerwunsch 2026-10-26): ihr Abstand vom unteren Zeilenrand. Das Symbol bleibt in der Zeile zentriert
 * (24 dp Kasten, 8 dp darunter, `FuelIconBottomPadding`); sein Fuss (die Zapfsaeule) endet im Material-Symbol 3 dp ueber dem Kasten, also **11 dp** ueber dem Zeilenrand — auf
 * dieser Hoehe stehen die Unterkanten von Zahl und Einheit (`paddingFromBaseline`), unabhaengig von der Schriftgroesse.
 */
private val FuelIconBottomPadding = (FUEL_ROW_HEIGHT - FuelIconSize) / 2
private val FuelBaselineFromBottom = FuelIconBottomPadding + 3.dp

/** Die Zahl ist um diesen Faktor groesser als `titleMedium` (Nutzerwunsch 2026-10-26, Variante B: "die Zahl koennte etwas hoeher werden"); drei Ziffern und die Einheit passen so noch in die 64-dp-Box, ohne zu schrumpfen. */
private const val FuelNumberScale = 1.25f

/** Breite der Box mit der Restreichweite als Zahl und ihrer Einheit (F5): fest, damit der Balken beim Wechsel der Ziffernzahl (100 -> 99) nicht springt. */
internal val FUEL_READOUT_WIDTH = 64.dp

/** Abstand zwischen Tankstellen-Symbol und Zahl; hinter der Zahl bleiben 12 dp bis zum Balken (vorher 12 dp zwischen Symbol und Balken). */
private val FuelIconToReadoutGap = 8.dp
private val FuelReadoutToBarGap = 12.dp

/** Breite der Reserve-Marke und ihre Hoehe: 2 dp breit, 24 dp hoch — 4 dp ueber und unter dem 16-dp-Balken, innerhalb der 12 dp Luft der 40-dp-Zeile. */
private val FuelReserveMarkWidth = 2.dp
private val FuelReserveMarkHeight = 24.dp

/** Kleinste Schriftgroesse der Zahl: schrumpft bei grosser Systemschrift statt abgeschnitten zu werden (Prinzip "nie ein still abgeschnittener Wert"); bei Schriftskala 1,3 braucht eine vierstellige Zahl in 64 dp weniger als 12 sp (gemessen am Geraet: 12 sp reichten nicht). */
private val FuelReadoutMinFontSize = 10.sp

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
 * Links das Tankstellen-Symbol (macht klar, dass es eine Tankanzeige ist), daneben ueber die ganze uebrige Breite der Balken — er besteht seit
 * 2026-10-15 (Nutzerwunsch) aus [FUEL_SEGMENT_COUNT] = 10 Segmenten, die sich von links mit dem Anteil der Restreichweite ([FuelGaugeState.fraction])
 * fuellen **und segmentweise abnehmen, nicht stufenlos** (`ceil(Anteil * 10)`, s. [FuelGaugeState.litSegments]); die Luecke zwischen den Segmenten ist
 * dieselbe wie beim Drehzahlbalken ([SEGMENT_GAP], eine gemeinsame Konstante). Alle leuchtenden Segmente haben eine Farbe, die an der **Zahl der
 * leuchtenden Segmente** haengt ([FuelLevel], s. [FuelGaugeState.level]): 5-10 gruen, 3-4 gelb, 2 rot, 1 rot blinkend — sie wechselt genau dann, wenn
 * ein Segment erloescht.
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
    val description = fuelGaugeDescription(state)
    val resetAction = stringResource(R.string.fuel_gauge_reset_action)
    val unlitColor = MaterialTheme.colorScheme.surfaceVariant
    // In der "aus"-Phase des Blinkens ist die Farbe vorab ueber das unbeleuchtete Segment gemischt (Deckkraft FUEL_BLINK_OFF_ALPHA), nicht beim Zeichnen:
    // Jedes Segment ist **ein** deckendes Rechteck wie beim Drehzahlbalken. Zwei uebereinanderliegende Rechtecke (unbeleuchtet darunter) haben an der
    // geglaetteten Kante einen Saum aus der Farbe des unteren: der Randpunkt ist keine reine Mischung aus Segment und Luecke mehr (am Bildschirmfoto
    // (40, 164, 87) mit einem Rotanteil, den keine Mischung aus Gruen und Luecke hat; Test `theSegmentEdgesAreCleanBlends…`).
    val fillColor = fuelBarColor(level).let { if (blinkOn) it else it.copy(alpha = FUEL_BLINK_OFF_ALPHA).compositeOver(unlitColor) }
    val litSegments = state.litSegments
    val reserveMarkFraction = state.reserveFraction
    val markColor = MaterialTheme.colorScheme.onSurface

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
                modifier = Modifier.fillMaxSize().padding(start = FUEL_ROW_START_PADDING, end = FUEL_ROW_END_PADDING),
                verticalAlignment = Alignment.Bottom,
            ) {
                Icon(
                    imageVector = Icons.Default.LocalGasStation,
                    contentDescription = null,
                    modifier = Modifier.padding(bottom = FuelIconBottomPadding).size(FuelIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(FuelIconToReadoutGap))
                FuelRangeReadout(state)
                Spacer(Modifier.width(FuelReadoutToBarGap))
                // Zehn Segmente wie beim Drehzahlbalken: unbeleuchtete in surfaceVariant (die Skala bleibt lesbar), die Luecken zeigen den
                // Zeilenhintergrund. Je Segment genau ein deckendes Rechteck (s. fillColor).
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.CenterVertically)
                        .height(FuelBarHeight)
                        .testTag(FUEL_GAUGE_TRACK_TEST_TAG),
                ) {
                    val gap = SEGMENT_GAP.toPx()
                    val segmentWidth = segmentWidthPx(size.width, gap, FUEL_SEGMENT_COUNT)
                    for (i in 0 until FUEL_SEGMENT_COUNT) {
                        drawRect(
                            color = if (i < litSegments) fillColor else unlitColor,
                            topLeft = Offset(i * (segmentWidth + gap), 0f),
                            size = Size(segmentWidth, size.height),
                        )
                    }
                    // Die Reserve-Marke: ein Strich ueber den Segmenten an der Stelle reserve / Reichweite von links (`null` = keine Marke).
                    reserveMarkFraction?.let { fraction ->
                        val markWidth = FuelReserveMarkWidth.toPx()
                        val markHeight = FuelReserveMarkHeight.toPx()
                        val center = (fraction * size.width).coerceIn(markWidth / 2f, size.width - markWidth / 2f)
                        drawRect(
                            color = markColor,
                            topLeft = Offset(center - markWidth / 2f, (size.height - markHeight) / 2f),
                            size = Size(markWidth, markHeight),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Die Restreichweite als Zahl in der Tankzeile (F5): die Zahl in Plex Mono mit `tnum` (auf ganze Kilometer abgerundet, [FuelGaugeState.displayKm], 1,25-mal so gross wie `titleMedium`) und ihre Einheit, in einer festen Box
 * ([FUEL_READOUT_WIDTH]). **Zahl und Einheit stehen auf einer Grundlinie** (`paddingFromBaseline`, [FuelBaselineFromBottom] ueber dem Zeilenrand) — der Fuss des Tankstellen-Symbols steht auf derselben Hoehe. Die Zahl schrumpft bei grosser Systemschrift bis [FuelReadoutMinFontSize] (10 sp), die Einheit behaelt ihren Platz (wie die Kachel). Normal in `onSurface`;
 * hat der Rest die Reserve erreicht, in `StatusColors.criticalOn` — der Rest gegen die Reserve ist eine Aussage ueber die Strecke, kein zweites Alarmsignal neben den Segmenten.
 * Die Box traegt keine eigene Semantik: die Beschreibung der Zeile ([fuelGaugeDescription]) nennt die Zahl schon, TalkBack soll sie nicht doppelt ansagen.
 */
@Composable
private fun FuelRangeReadout(state: FuelGaugeState) {
    val color = if (state.inReserve) StatusColors.criticalOn(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .width(FUEL_READOUT_WIDTH)
            .fillMaxHeight()
            .testTag(FUEL_GAUGE_READOUT_TEST_TAG)
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // `paddingFromBaseline` steht vor dem Test-Tag: der Knoten bleibt der Text (seine Grenzen messen Zahl und Einheit), der Abstand liegt ausserhalb.
        LiveValueText(
            value = state.displayKm.toString(),
            modifier = Modifier
                .weight(1f, fill = false)
                .paddingFromBaseline(bottom = FuelBaselineFromBottom)
                .testTag(FUEL_GAUGE_RANGE_TEST_TAG),
            style = fuelNumberStyle(MaterialTheme.typography.titleMedium.asLiveValue()),
            color = color,
            minFontSize = FuelReadoutMinFontSize,
        )
        Text(
            text = stringResource(R.string.fuel_gauge_unit_km),
            modifier = Modifier
                .paddingFromBaseline(bottom = FuelBaselineFromBottom)
                .testTag(FUEL_GAUGE_UNIT_TEST_TAG),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Der Stil der Zahl: [FuelNumberScale]-mal so gross wie die Vorlage (die Zeilenhoehe bleibt die der Vorlage — die Zahl steht ueber `paddingFromBaseline` auf der Grundlinie, nicht ueber ihre Zeilenhoehe). */
private fun fuelNumberStyle(base: TextStyle): TextStyle = base.copy(fontSize = base.fontSize * FuelNumberScale)

/**
 * Die Beschreibung der Zeile fuer TalkBack: die angezeigte Zahl (nicht der genaue Rest: sie soll mit dem Bild uebereinstimmen) und der Anteil; mit Reserve nennt sie, ab wo
 * sie gilt, und im Fall "in der Reserve" sagt sie das.
 */
@Composable
private fun fuelGaugeDescription(state: FuelGaugeState): String = when {
    state.inReserve -> stringResource(R.string.fuel_gauge_desc_in_reserve, state.displayKm, state.percent)
    state.reserveFraction != null -> stringResource(R.string.fuel_gauge_desc_reserve, state.displayKm, state.percent, state.reserveKm)
    else -> stringResource(R.string.fuel_gauge_desc, state.displayKm, state.percent)
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

@Preview(name = "FuelGaugeRow · green with reserve mark · dark (blue cockpit)")
@Composable
private fun PreviewFuelGaugeRowReserveDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        FuelGaugeRow(FuelGaugeState(rangeFullKm = 260, kmSinceFill = 70f, reserveKm = 40), onResetRequested = {})
    }
}

@Preview(name = "FuelGaugeRow · in the reserve · light (Default scheme)")
@Composable
private fun PreviewFuelGaugeRowInReserveLight() {
    MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) {
        FuelGaugeRow(FuelGaugeState(rangeFullKm = 260, kmSinceFill = 225f, reserveKm = 40), onResetRequested = {})
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
