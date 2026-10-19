package de.adv990de.obd2.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.convertUnit
import de.adv990de.obd2.obd.formatSensorNumber
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.components.LiveValueTile
import de.adv990de.obd2.ui.components.SectionLabel
import de.adv990de.obd2.ui.components.TileStatus
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget
import androidx.compose.ui.tooling.preview.Preview
import de.adv990de.obd2.ui.theme.PreviewTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SensorCardSlot(
    reading: SensorValue?,
    sparklines: Map<String, List<Float>>,
    useImperial: Boolean,
    modifier: Modifier = Modifier,
    /** Text fuer den Fall ohne Messwert — siehe [slotPlaceholderRes]. */
    @StringRes placeholderRes: Int = R.string.dashboard_slot_empty,
    /** false zeichnet die Kachel als Vorschau: "--" statt einer Zahl, keine Statusfarbe. */
    isLive: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    /** Die geglaettete Spannung samt Zustand fuer den Balken der Spannungs-Kachel ([DashboardUiState.voltageTrend]); andere Kacheln lesen es nicht. */
    voltageTrend: VoltageTrend? = null,
) {
    if (reading != null) {
        SensorCard(
            value = reading,
            modifier = modifier.fillMaxSize(),
            sparkline = sparklines[reading.pid.id] ?: emptyList(),
            useImperial = useImperial,
            onLongPress = onLongPress,
            isLive = isLive,
            voltageTrend = voltageTrend,
        )
    } else {
        EmptySlot(placeholderRes = placeholderRes, modifier = modifier, onLongPress = onLongPress)
    }
}

/**
 * Ein Slot ohne Wert: ein unbelegter Slot war bisher eine leere Flaeche und sah nach Renderfehler aus. Der Hinweis ([placeholderRes], s. [slotPlaceholderRes])
 * nennt zugleich die einzige Geste, die ihn belegt — den Long-Press. Auch die Gelaende-Kachel zeigt sich so, solange sie noch keine Werte hat (GPS aus, kein Fix).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EmptySlot(
    @StringRes placeholderRes: Int,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .combinedClickable(
                onClick = {},
                onLongClick = { onLongPress?.invoke() },
                onLongClickLabel = stringResource(R.string.dashboard_slot_change_action),
            ),
        shape = RectangleShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(placeholderRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Schmales Band ueber dem Kachelraster, solange keine Verbindung besteht.
 *
 * Bis 1.11.0 zeigte das Dashboard ohne Verbindung Kacheln mit dem Wert 0 — optisch nicht von
 * echten Messwerten zu unterscheiden. Genau diese Verwechslung stand am Anfang der Fehlersuche
 * nach der GPS-Geschwindigkeit: Der Eindruck, die App zeige eine Geschwindigkeit, kam von der
 * Vorschau, nicht vom Fahrzeug.
 *
 * Die Vorschau bleibt — sie zeigt die Slot-Belegung und macht das Layout begreifbar — aber sie
 * sagt jetzt, was sie ist.
 *
 * [labelRes] ist normalerweise "Vorschau · nicht verbunden"; bei veralteten Werten ([DashboardUiState.isStale])
 * nutzt dasselbe Band "Keine aktuellen Werte" — die Verbindung steht, das Steuergeraet schweigt. Waehrend einer
 * Wiederverbindung nennt es den Versuch ([previewBannerLabel], [labelArgs] = Nummer und Gesamtzahl).
 *
 * [reason] (siehe [previewBannerReason]) schreibt aus, WARUM nicht verbunden ist; ohne Grund bleibt es
 * das schlichte Band. Der Grund steht in der Fehlerfarbe des Themes (theme-sicherer Kontrast, wie die
 * uebrigen Leerzustands-Fehlertexte) in lesbarer Schriftgroesse, nicht in der ALL-CAPS-Beschriftung.
 */
@Composable
internal fun PreviewBanner(
    modifier: Modifier = Modifier,
    reason: UiText? = null,
    @StringRes labelRes: Int = R.string.dashboard_preview_banner,
    labelArgs: List<Any> = emptyList(),
) {
    Surface(
        modifier = modifier,
        shape = RectangleShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SectionLabel(
                text = stringResource(labelRes, *labelArgs.toTypedArray()),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (reason != null) {
                Text(
                    text = reason.asString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Adapter from the domain type [SensorValue] to the generic [LiveValueTile]:
 * handles unit conversion (imperial flag), NaN/Inf → "--", voltage gets one
 * decimal place, everything else integer. The accessibility contentDescription
 * stays here because it depends on the PID semantics.
 */
@Composable
internal fun SensorCard(
    value: SensorValue,
    modifier: Modifier = Modifier,
    sparkline: List<Float> = emptyList(),
    useImperial: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    /**
     * false bedeutet: Es gibt keine Verbindung, der Wert stammt aus der Vorschau-Belegung und ist
     * kein Messwert. Er wird dann als "--" gezeichnet — eine "0" liesse sich von einem echten
     * Messwert nicht unterscheiden, und genau diese Verwechslung hat schon eine Fehlersuche
     * ausgeloest.
     */
    isLive: Boolean = true,
    /**
     * Nur fuer die Spannungs-Kachel ([PidRegistry.CONTROL_MODULE_VOLTAGE]): die geglaettete Spannung samt Lade-/Entlade-Zustand. Mit ihr bekommt die Kachel den senkrechten
     * Balken links ([VoltageGauge]) und, bei laufendem Motor, das Zustandswort in der Beschreibung fuer Screenreader; null (noch keine Probe seit dem Wechsel Motor an/aus, nach dem ersten Verbinden zu wenige Proben) zeigt nur Spur und Nulllinie.
     */
    voltageTrend: VoltageTrend? = null,
) {
    val (convertedValue, displayUnit) = if (value.value.isNaN() || value.value.isInfinite()) {
        value.value to value.pid.unit
    } else {
        convertUnit(value.value, value.pid.unit, useImperial)
    }
    val displayValue = when {
        !isLive -> "--"
        value.value.isNaN() || value.value.isInfinite() -> "--"
        else -> formatSensorNumber(convertedValue, value.pid.unit)
    }
    val pidLabel = stringResource(value.pid.labelRes)
    val isVoltage = value.pid.id == PidRegistry.CONTROL_MODULE_VOLTAGE.id
    // Ohne Verbindung gibt es keinen Zustand: der Balken zeigt dann nur Spur und Nulllinie, die Beschreibung kein Wort. Vor dem Start (Motor aus) zeigt der Balken die Spannung, aber die
    // Beschreibung nennt kein Wort: "laedt" und "laedt kaum" waeren bei stehendem Motor falsch.
    val shownTrend = if (isVoltage && isLive) voltageTrend else null
    val stateWord = shownTrend?.takeIf { it.engineRunning }?.let { stringResource(voltageStateRes(it.state)) }
    LiveValueTile(
        label = pidLabel,
        value = displayValue,
        unit = displayUnit,
        // Ohne Verbindung gibt es auch keinen Verlauf zu zeigen.
        sparkline = if (isLive) sparkline else emptyList(),
        status = if (isLive) pidTileStatus(value.pid.id, value.value) else TileStatus.Ok,
        isPreview = !isLive,
        onLongPress = onLongPress,
        // Das Neubelegen per Long-Press ist die einzige Geste dafuer — TalkBack soll sie beim Namen nennen (UID-13).
        onLongPressLabel = stringResource(R.string.dashboard_slot_change_action),
        modifier = modifier.semantics { contentDescription = sensorCardDescription(pidLabel, displayValue, displayUnit, stateWord) },
        // Der Balken gehoert zur Spannung, nicht zu jeder Kachel.
        gauge = if (isVoltage) ({ VoltageGauge(trend = shownTrend, isLive = isLive) }) else null,
    )
}

/** Die Beschreibung der Kachel fuer Screenreader: "Name: Wert Einheit", mit Zustand dahinter ("…, laedt"), wenn die Kachel einen hat. */
internal fun sensorCardDescription(label: String, value: String, unit: String, stateWord: String?): String =
    if (stateWord == null) "$label: $value $unit" else "$label: $value $unit, $stateWord"

/** Test-only selector for [SlotPickerSheet]'s [LazyColumn], so instrumentation tests can
 *  `performScrollToNode` to PID rows below the initially-composed viewport (confirmed empirically
 *  2026-09-29: only a handful of rows compose before the first frame settles on a Pixel 8 Pro,
 *  same root cause as `LOGGING_CONFIG_LIST_TEST_TAG`). */
internal const val SLOT_PICKER_LIST_TEST_TAG = "slot_picker_list"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlotPickerSheet(
    currentPidId: String,
    availablePids: List<PidDefinition>,
    readings: List<SensorValue>,
    useImperial: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Volle Hoehe statt halb: im Querformat zeigte das halbhohe Sheet anfangs nur ~3 Zeilen.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Nach dem angezeigten (uebersetzten) Namen geordnet, nicht nach dem englischen (UID-15).
    val labels = availablePids.associate { it.id to stringResource(it.labelRes) }
    val ordered = remember(availablePids, labels) { sortByDisplayLabel(availablePids, { labels.getValue(it.id) }) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        // Das Sheet ist ein eigenes Fenster mit eigener ViewConfiguration: die 56-dp-Regel aus AppTheme gilt nur,
        // wenn sie hier erneut gesetzt wird (s. ProvideMinTouchTarget).
        ProvideMinTouchTarget {
            LazyColumn(Modifier.testTag(SLOT_PICKER_LIST_TEST_TAG)) {
                items(ordered, key = { it.id }) { pid ->
                    val reading = readings.firstOrNull { it.pid.id == pid.id }
                    val label = if (pid.id == DerivedPids.TERRAIN_ID) {
                        // Die Gelaende-Kachel zeigt drei Werte, keinen: in der Auswahl steht nur ihr Name.
                        labels.getValue(pid.id)
                    } else if (reading == null || reading.value.isNaN() || reading.value.isInfinite()) {
                        "${labels.getValue(pid.id)}: -- (${pid.unit})"
                    } else {
                        val (convertedValue, displayUnit) = convertUnit(reading.value, pid.unit, useImperial)
                        val formatted = formatSensorNumber(convertedValue, pid.unit)
                        "${labels.getValue(pid.id)}: $formatted ($displayUnit)"
                    }
                    ListItem(
                        modifier = Modifier.clickable { onSelect(pid.id) },
                        headlineContent = { Text(label) },
                        trailingContent = if (pid.id == currentPidId) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// region — Previews
//
// SlotPickerSheet ist ein Fenster (ModalBottomSheet) und zeigt in der Vorschau nichts Brauchbares; Kachel und Band sind zustandslos.

@Composable
private fun SlotComponentsPreviewContent() {
    // Eine Kachel nimmt, was man ihr gibt (im Dashboard gibt die Reihe die Hoehe vor): ohne feste Hoehe fuellte die erste das ganze
    // Vorschau-Fenster und schob die zweite und das Band hinaus (am Geraet gerendert gesehen, v1.25.2).
    val tile = Modifier.height(160.dp)
    Column {
        SensorCard(SensorValue(PidRegistry.COOLANT_TEMP, 87f), modifier = tile, sparkline = listOf(60f, 65f, 72f, 80f, 85f, 87f))
        SensorCard(SensorValue(PidRegistry.COOLANT_TEMP, 0f), modifier = tile, isLive = false)
        PreviewBanner(reason = UiText.DynamicString("Verbindung fehlgeschlagen"))
    }
}

@Preview(name = "SensorCard and PreviewBanner (light)", showBackground = true)
@Composable
private fun PreviewSlotComponentsLight() {
    PreviewTheme(dark = false) { SlotComponentsPreviewContent() }
}

@Preview(name = "SensorCard and PreviewBanner (dark)", showBackground = true)
@Composable
private fun PreviewSlotComponentsDark() {
    PreviewTheme(dark = true) { SlotComponentsPreviewContent() }
}

// endregion
