package de.adv990de.obd2.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.adv990de.obd2.R
import de.adv990de.obd2.data.MinMax
import de.adv990de.obd2.data.SessionStats
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.convertUnit
import de.adv990de.obd2.obd.formatSensorNumber
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.components.SectionLabel
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.asLiveValue

/** Test tag des Blocks „Messwerte“ im Detail-Dialog. */
internal const val SESSION_STATS_TEST_TAG = "session_stats"

/** Breite einer Wertespalte: „212 °F“ in Plex Mono mit Luft; bei grosser Systemschrift schrumpft der Wert bis [VALUE_MIN_FONT_SIZE] statt abgeschnitten zu werden. */
private val ValueColumnWidth = 96.dp

private val VALUE_MIN_FONT_SIZE = 12.sp

/** Mindesthoehe einer Zeile: die Werte sind nicht anklickbar (die 56-dp-Regel gilt fuer Tipp-Ziele), 28 dp halten die beiden Zeilen ruhig auseinander und lassen im Querformat den Hinweis darunter ohne Scrollen ganz sichtbar (bei 32 dp war seine letzte Zeile angeschnitten). */
private val RowMinHeight = 28.dp

/**
 * Ein Wert mit Einheit, wie ihn der Block „Messwerte“ zeigt: die Zahl wie in den Kacheln ([formatSensorNumber]: Volt mit einer Nachkommastelle, sonst ganz, in der Sprache des Geraets),
 * bei imperialen Einheiten umgerechnet ([convertUnit]: °C wird °F). [rawUnit] ist die Einheit der Aufzeichnung. Ein festes Leerzeichen trennt Zahl und Einheit: in der schmalen Spalte
 * darf der Zeilenumbruch nicht dazwischen fallen.
 */
internal fun formatStatValue(value: Float, rawUnit: String, imperial: Boolean): String {
    val (converted, unit) = convertUnit(value, rawUnit, imperial)
    return "${formatSensorNumber(converted, rawUnit)} $unit"
}

/**
 * Der Block „Messwerte der Fahrt“ im Detail-Dialog (F11): kleinster und groesster Wert von Kuehlmittel und Spannung als zwei Zeilen unter den Spalten Min und Max, eine Zahl je Feld, kein Diagramm.
 * Fehlt ein Wert (nicht aufgezeichnet, ungueltig, die Spannung nie bei laufendem Motor), steht „–“ — nie 0. Die Regel der Spannung nennt ein Hinweis darunter, weil sie sonst nicht zu erraten ist
 * (s. `SessionStatsAccumulator`). TalkBack liest jede Zeile als ein Satz („Kühlmittel: kleinster Wert 12 °C, größter Wert 92 °C“).
 */
@Composable
internal fun SessionStatsSection(state: SessionStatsState, imperial: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.testTag(SESSION_STATS_TEST_TAG)) {
        SectionLabel(stringResource(R.string.history_detail_stats_heading))
        Spacer(Modifier.height(4.dp))
        when (state) {
            SessionStatsState.Loading -> Text(
                stringResource(R.string.history_detail_stats_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SessionStatsState.Failed -> Text(stringResource(R.string.history_detail_stats_failed), style = MaterialTheme.typography.bodyMedium)
            is SessionStatsState.Ready -> {
                ColumnHeaders()
                StatsRow(stringResource(PidRegistry.COOLANT_TEMP.labelRes), PidRegistry.COOLANT_TEMP.unit, state.stats.coolant, imperial)
                StatsRow(stringResource(PidRegistry.CONTROL_MODULE_VOLTAGE.labelRes), PidRegistry.CONTROL_MODULE_VOLTAGE.unit, state.stats.voltage, imperial)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.history_detail_stats_voltage_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ColumnHeaders() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        SectionLabel(stringResource(R.string.history_detail_stats_min), Modifier.width(ValueColumnWidth), textAlign = TextAlign.End)
        SectionLabel(stringResource(R.string.history_detail_stats_max), Modifier.width(ValueColumnWidth), textAlign = TextAlign.End)
    }
}

@Composable
private fun StatsRow(label: String, rawUnit: String, range: MinMax?, imperial: Boolean) {
    val min = range?.let { formatStatValue(it.min, rawUnit, imperial) }
    val max = range?.let { formatStatValue(it.max, rawUnit, imperial) }
    val description = if (min != null && max != null) {
        stringResource(R.string.history_detail_stats_row_desc, label, min, max)
    } else {
        stringResource(R.string.history_detail_stats_row_none_desc, label)
    }
    // Ein Knoten je Zeile mit einem ganzen Satz: ohne ihn liest TalkBack „Kühlmittel, 12 °C, 92 °C“ und kein Wort sagt, was Min und was Max ist.
    Row(
        Modifier.fillMaxWidth().heightIn(min = RowMinHeight).semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
        StatValue(min)
        StatValue(max)
    }
}

@Composable
private fun StatValue(text: String?) {
    Box(Modifier.width(ValueColumnWidth), contentAlignment = Alignment.CenterEnd) {
        LiveValueText(
            value = text ?: stringResource(R.string.history_detail_stats_missing),
            style = MaterialTheme.typography.titleMedium.asLiveValue(),
            color = if (text != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            minFontSize = VALUE_MIN_FONT_SIZE,
        )
    }
}

// region — Previews

private val PREVIEW_STATS = SessionStatsState.Ready(SessionStats(coolant = MinMax(14f, 93f), voltage = MinMax(12.1f, 14.3f)))

@Preview(name = "SessionStatsSection · light", showBackground = true, widthDp = 420)
@Composable
private fun PreviewSessionStatsSectionLight() {
    PreviewTheme(dark = false) { SessionStatsSection(PREVIEW_STATS, imperial = false, Modifier.padding(24.dp)) }
}

// Dunkel: imperiale Einheiten und eine Fahrt ohne Spannung — der Platzhalter „–“ steht in der Spalte.
@Preview(name = "SessionStatsSection · dark", showBackground = true, widthDp = 420)
@Composable
private fun PreviewSessionStatsSectionDark() {
    PreviewTheme(dark = true) {
        SessionStatsSection(SessionStatsState.Ready(SessionStats(coolant = MinMax(57f, 199f), voltage = null)), imperial = true, Modifier.padding(24.dp))
    }
}

// endregion
