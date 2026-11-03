package de.adv990de.obd2.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.StatusColors

/**
 * Der Lade-/Entlade-Balken der Spannungs-Kachel (Idee F3, Entwurf `<intern>/superpowers/archive/specs/2026-10-19-voltage-gauge-design.md`): senkrecht, Skala 11–15 V, die Nulllinie bei 12,7 V.
 * Die Füllung geht von der Nulllinie bis zur geglätteten Spannung — darüber nach oben, darunter nach unten — in der Farbe des Zustands.
 *
 * - **Spur:** `surfaceVariant`, wie die unbeleuchteten Segmente der übrigen Anzeigen (Drehzahl, Tank): die Skala bleibt lesbar, auch wenn nichts gefüllt ist.
 * - **Füllung:** **ein** deckendes Rechteck, scharfe Kanten, stufenlos (die Nulllage muss genau stehen). Sie sieht bei laufendem Motor und **vor dem Start** (verbunden, Motor aus; [VoltageTrend.engineRunning]
 *   = false, Nutzerwunsch 2026-10-19) gleich aus: dieselbe Lage, dieselben Farben. Ohne Zustand ([trend] = null: noch keine Probe seit dem Wechsel Motor an/aus, nach dem ersten Verbinden zu wenige Proben) oder ohne
 *   Verbindung ([isLive] = false) gibt es keine Füllung — nur Spur und Nulllinie; eine Farbe über einem Wert, den niemand gemessen hat, wäre eine Aussage ohne Grundlage.
 * - **Nulllinie:** 2 dp in `onSurface` über die Balkenbreite, über der Füllung (gedämpft ohne Verbindung).
 * - **Keine eigene Beschreibung:** der Balken ist reine Zeichnung; sein Zustand steht als Wort in der Beschreibung der Kachel ([sensorCardDescription]).
 *
 * Die Breite und die Abstände bestimmt der Kachelrahmen (`TileFrame(gauge)`): 16 dp breit wie der Tankbalken, 8 dp hinter der Statusleiste, oben und unten die 12 dp Innenrand.
 */
@Composable
internal fun VoltageGauge(trend: VoltageTrend?, isLive: Boolean, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val zeroLine = MaterialTheme.colorScheme.onSurface.let { if (isLive) it else it.copy(alpha = 0.35f) }
    val fill = if (isLive && trend != null) voltageBarFill(trend.volts)?.let { it to voltageGaugeColor(trend.state) } else null
    Canvas(modifier.fillMaxSize().testTag(VOLTAGE_GAUGE_TEST_TAG)) {
        drawRect(color = track)
        if (fill != null) {
            val (span, color) = fill
            drawRect(
                color = color,
                topLeft = Offset(0f, size.height * (1f - span.toFraction)),
                size = Size(size.width, size.height * (span.toFraction - span.fromFraction)),
            )
        }
        val lineHeight = ZERO_LINE_HEIGHT.toPx()
        val centre = size.height * (1f - VOLTAGE_BAR_ZERO_FRACTION)
        drawRect(color = zeroLine, topLeft = Offset(0f, centre - lineHeight / 2f), size = Size(size.width, lineHeight))
    }
}

internal const val VOLTAGE_GAUGE_TEST_TAG = "voltage_gauge"

private val ZERO_LINE_HEIGHT = 2.dp

/**
 * Die Farbe der Füllung: das feste Ampel-Grün/-Gelb/-Rot wie bei Tankanzeige und Ölbalken (dritte dokumentierte Farb-Ausnahme, CLAUDE.md „Statusfarben“) — Theme-`tertiary` wäre nur
 * im grünen Cockpit-Schema grün. Rot heißt hier „die Batterie wird entladen“, nicht „Gefahr“: im Stand mit Lüfter ist es der Normalfall.
 */
internal fun voltageGaugeColor(state: ChargeState): Color = when (state) {
    ChargeState.Charging -> StatusColors.TrafficLightGreen
    ChargeState.Marginal -> StatusColors.Warning
    ChargeState.Discharging -> StatusColors.Critical
}

/** Das Wort, das ein Screenreader für den Zustand liest. */
@StringRes
internal fun voltageStateRes(state: ChargeState): Int = when (state) {
    ChargeState.Charging -> R.string.voltage_state_charging
    ChargeState.Marginal -> R.string.voltage_state_marginal
    ChargeState.Discharging -> R.string.voltage_state_discharging
}

// region — Previews

@Composable
private fun VoltageGaugePreviewContent() {
    val voltage = PidRegistry.CONTROL_MODULE_VOLTAGE
    // Eine Kachel nimmt, was man ihr gibt (im Dashboard gibt die Reihe die Hoehe vor): ohne feste Hoehe fuellte sie das ganze Vorschau-Fenster. Zwei Reihen, weil das Vorschau-Fenster
    // schmal ist (Geraetebreite): fuenf Kacheln nebeneinander schnitten die Werte ab.
    val tile = Modifier.height(160.dp)
    Column(Modifier.fillMaxWidth().padding(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Box(tile.weight(1f)) { SensorCard(SensorValue(voltage, 13.6f), modifier = Modifier.fillMaxSize(), voltageTrend = VoltageTrend(13.6f, ChargeState.Charging)) }
            Box(tile.weight(1f)) { SensorCard(SensorValue(voltage, 13.0f), modifier = Modifier.fillMaxSize(), voltageTrend = VoltageTrend(13.0f, ChargeState.Marginal)) }
            Box(tile.weight(1f)) { SensorCard(SensorValue(voltage, 12.3f), modifier = Modifier.fillMaxSize(), voltageTrend = VoltageTrend(12.3f, ChargeState.Discharging)) }
        }
        Row(Modifier.fillMaxWidth()) {
            // Vor dem Start (verbunden, Motor aus): derselbe Balken, nur ohne Zustandswort in der Beschreibung.
            Box(tile.weight(1f)) { SensorCard(SensorValue(voltage, 11.9f), modifier = Modifier.fillMaxSize(), voltageTrend = VoltageTrend(11.9f, ChargeState.Discharging, engineRunning = false)) }
            Box(tile.weight(1f)) { SensorCard(SensorValue(voltage, 0f), modifier = Modifier.fillMaxSize(), isLive = false) }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Preview(name = "VoltageGauge (light)", showBackground = true, widthDp = 480)
@Composable
private fun PreviewVoltageGaugeLight() {
    PreviewTheme(dark = false) { VoltageGaugePreviewContent() }
}

@Preview(name = "VoltageGauge (dark)", showBackground = true, widthDp = 480)
@Composable
private fun PreviewVoltageGaugeDark() {
    PreviewTheme(dark = true) { VoltageGaugePreviewContent() }
}

// endregion
