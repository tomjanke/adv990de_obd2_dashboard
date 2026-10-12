package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme

/**
 * RPM-Blink-Schwelle: Slider + Label + der davon abhaengige dynamische-Schwelle-Schalter.
 * Extrahiert (2026-09-23) aus der damaligen Seite "Dashboard konfigurieren" und seit der
 * Einstellungs-Umstrukturierung Teil der Gruppe "Dashboard & Anzeige" in `SettingsScreen`; bleibt ein
 * ViewModel-loser Baustein, damit er wie
 * `GearRatioField` (s. `GearSettingsScreen.kt`) direkt in einem Compose-UI-Test getrieben werden
 * kann, ohne den echten `AppPreferences`/DataStore-Umweg (s. `RpmThresholdSectionTest`).
 *
 * [rpmBlinkThreshold] und [rpmDynamicThreshold] kommen aus StateFlows, die per
 * `stateIn(viewModelScope, WhileSubscribed(5_000), DEFAULT)` gebaut sind (s.
 * `DisplaySettingsViewModel`): `collectAsStateWithLifecycle()` liest bei der ersten Komposition synchron den
 * Default (0 / false), waehrend der echte, in AppPreferences/DataStore gespeicherte Wert erst
 * asynchron nachtraeglich eintrifft.
 *
 * Anders als [GearRatioField] (unkeyed `remember` + `LaunchedEffect(value)`, explizit von einem
 * `hasBeenEdited`-Flag geschuetzt) synct der lokale Puffer hier per **keyed** `remember` —
 * `remember(rpmBlinkThreshold) { mutableIntStateOf(rpmBlinkThreshold) }` — bedingungslos bei
 * JEDER Aenderung des Upstream-Werts, ohne "hat der Nutzer schon editiert"-Schutz:
 * - Der Async-Eintreffen-Race wird trotzdem korrekt geloest (der Puffer zieht immer auf den
 *   neuesten Upstream-Wert nach, keine dauerhafte Staleness).
 * - Ein Echo desselben Werts (der ViewModel-Setter schreibt exakt den Wert zurueck, den der
 *   Nutzer gerade per Slider gewaehlt hat) ist unkritisch: `mutableIntStateOf` mit unveraendertem
 *   Wert neu zu erzeugen aendert sichtbar nichts.
 * - Anders als bei [GearRatioField] gibt es aber KEINEN Schutz gegen ein Echo eines ANDEREN
 *   (z.B. veralteten) Werts waehrend einer laufenden Geste — das wuerde die Slider-Position
 *   sofort ueberschreiben. Aktuell unkritisch, weil `setRpmBlinkThreshold` genau einen Aufrufer
 *   hat (den `onValueChangeFinished` dieses Sliders selbst) und Slider-Gesten diskret abschliessen
 *   (kein Zwischenzustand wie bei Tastatureingabe) — waere aber bei einem zweiten Schreiber
 *   dieser Preference erneut zu pruefen.
 */
@Composable
internal fun RpmThresholdSection(
    rpmBlinkThreshold: Int,
    rpmDynamicThreshold: Boolean,
    onThresholdChangeFinished: (Int) -> Unit,
    onDynamicThresholdChange: (Boolean) -> Unit,
) {
    var localThreshold by remember(rpmBlinkThreshold) { mutableIntStateOf(rpmBlinkThreshold) }
    val thresholdLabel = if (localThreshold == 0)
        stringResource(R.string.dashboard_rpm_threshold_disabled)
    else
        "$localThreshold rpm"
    ListItem(
        headlineContent = { Text(stringResource(R.string.dashboard_rpm_threshold_label)) },
        supportingContent = { Text(thresholdLabel) }
    )
    val sliderDescription = stringResource(R.string.dashboard_rpm_threshold_label)
    Slider(
        value = localThreshold.toFloat(),
        onValueChange = { localThreshold = it.toInt() },
        onValueChangeFinished = { onThresholdChangeFinished(localThreshold) },
        valueRange = 0f..10000f,
        steps = 19,
        // Ohne das liest TalkBack nur "Schieberegler, 60 Prozent": weder wofuer, noch dass 0 "aus" heisst und der Wert in
        // rpm gemeint ist (UID-13). Die sichtbare Zeile darueber ist ein eigener Knoten und gehoert nicht zum Slider.
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .semantics {
                contentDescription = sliderDescription
                stateDescription = thresholdLabel
            }
    )
    HorizontalDivider()
    if (localThreshold > 0) {
        SettingsSwitchRow(
            title = stringResource(R.string.dashboard_rpm_dynamic_label),
            summary = stringResource(R.string.dashboard_rpm_dynamic_desc),
            checked = rpmDynamicThreshold,
            onCheckedChange = onDynamicThresholdChange,
        )
        HorizontalDivider()
    }
}

@Preview(name = "RpmThresholdSection · disabled (blue)", showBackground = true)
@Composable
private fun PreviewRpmThresholdSectionDisabled() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RpmThresholdSection(
                rpmBlinkThreshold = 0,
                rpmDynamicThreshold = false,
                onThresholdChangeFinished = {},
                onDynamicThresholdChange = {},
            )
        }
    }
}

@Preview(name = "RpmThresholdSection · enabled, dynamic on (amber)", showBackground = true)
@Composable
private fun PreviewRpmThresholdSectionEnabled() {
    MaterialTheme(colorScheme = amberColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RpmThresholdSection(
                rpmBlinkThreshold = 6500,
                rpmDynamicThreshold = true,
                onThresholdChangeFinished = {},
                onDynamicThresholdChange = {},
            )
        }
    }
}
