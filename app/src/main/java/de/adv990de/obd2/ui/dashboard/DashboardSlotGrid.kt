package de.adv990de.obd2.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.terrain.TerrainDisplay
import de.adv990de.obd2.ui.theme.PreviewTheme

/**
 * Das Kachelraster des Dashboards: Querformat vier gleich breite Spalten in einer Reihe (nutzt die Breite, statt eine Spalte zu stapeln und eine halbe Kachel leer zu lassen),
 * Hochformat zwei Spalten in zwei Reihen (CLAUDE.md: "2 Spalten Standard"). Zwischen den Kacheln liegt eine 1-dp-Trennlinie aus `outlineVariant` als Hintergrund (Cockpit-Look,
 * keine Abstaende). Die Kachelhoehe ist fest ([tileHeight], Begruendung am Aufrufer): sie bestimmt der Aufrufer, das Raster nimmt keine Resthoehe.
 *
 * [slots] hat immer vier Eintraege (der Wert je Slot oder `null`), [slotPids] die Belegung dazu; [onLongPress] bekommt den Index des Slots.
 */
@Composable
internal fun DashboardSlotGrid(
    slotPids: List<String>,
    slots: List<SensorValue?>,
    state: DashboardUiState,
    terrain: TerrainDisplay?,
    useImperial: Boolean,
    supportedPidIds: Set<String>?,
    useGpsSpeed: Boolean,
    gpsPermissionMissing: Boolean,
    isLive: Boolean,
    isLandscape: Boolean,
    tileHeight: Dp,
    onLongPress: (Int) -> Unit,
) {
    // Eine Stelle fuer beide Ausrichtungen: dieselben Argumente je Kachel, nur Rahmen und Gewicht unterscheiden sich.
    @Composable
    fun Slot(i: Int, modifier: Modifier) {
        DashboardSlot(
            slotPid = slotPids.getOrNull(i),
            reading = slots[i],
            state = state,
            terrain = terrain,
            useImperial = useImperial,
            modifier = modifier,
            placeholderRes = slotPlaceholderRes(
                slotPids.getOrNull(i), supportedPidIds, state.isConnected, useGpsSpeed, gpsPermissionMissing,
            ),
            isLive = isLive,
            onLongPress = { onLongPress(i) },
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxWidth().height(tileHeight)
                    .background(MaterialTheme.colorScheme.outlineVariant),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                repeat(4) { i -> Slot(i, Modifier.weight(1f).fillMaxHeight()) }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.outlineVariant),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                repeat(2) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(tileHeight),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        repeat(2) { col -> Slot(row * 2 + col, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
    }
}

/**
 * Eine Kachel des Rasters: die Gelaende-Kachel, wenn der Slot [DerivedPids.TERRAIN_ID] traegt (sie zeigt drei Werte des Telefons, `DashboardViewModel.terrain`, unabhaengig von der Verbindung), sonst die
 * Kachel eines Messwerts. Eine Stelle fuer beide Ausrichtungen, damit Hoch- und Querformat nie auseinanderlaufen.
 */
@Composable
internal fun DashboardSlot(
    slotPid: String?,
    reading: SensorValue?,
    state: DashboardUiState,
    terrain: TerrainDisplay?,
    useImperial: Boolean,
    modifier: Modifier,
    @androidx.annotation.StringRes placeholderRes: Int,
    isLive: Boolean,
    onLongPress: () -> Unit,
) {
    if (slotPid == DerivedPids.TERRAIN_ID) {
        TerrainSlot(
            terrain = terrain,
            useImperial = useImperial,
            placeholderRes = placeholderRes,
            modifier = modifier,
            onLongPress = onLongPress,
        )
    } else {
        // Die GPS-Geschwindigkeit in "veraltet" ist die einzige Zahl, die ausserhalb von "live" gezeichnet wird; ihre Kurve gibt es nicht (die alte Kurve des Fahrzeugs gehoert nicht zu ihr).
        val drawsValue = isLive || reading?.isStaleGpsReading() == true
        SensorCardSlot(
            reading, if (isLive) state.sparklines else emptyMap(), useImperial, modifier,
            placeholderRes = placeholderRes,
            isLive = drawsValue,
            onLongPress = onLongPress,
            voltageTrend = state.voltageTrend,
        )
    }
}

// region — Previews

private val PREVIEW_PIDS = listOf(PidRegistry.COOLANT_TEMP, PidRegistry.SPEED, PidRegistry.RPM, PidRegistry.CONTROL_MODULE_VOLTAGE)

@Composable
private fun SlotGridPreviewContent(isLandscape: Boolean) {
    val values = listOf(87f, 64f, 2840f, 13.6f)
    val slots = PREVIEW_PIDS.mapIndexed { i, pid -> SensorValue(pid, values[i]) }
    DashboardSlotGrid(
        slotPids = PREVIEW_PIDS.map { it.id },
        slots = slots,
        state = DashboardUiState(isConnected = true),
        terrain = null,
        useImperial = false,
        supportedPidIds = null,
        useGpsSpeed = false,
        gpsPermissionMissing = false,
        isLive = true,
        isLandscape = isLandscape,
        tileHeight = if (isLandscape) 200.dp else 170.dp,
        onLongPress = {},
    )
}

@Preview(name = "DashboardSlotGrid · portrait (light)", showBackground = true, widthDp = 420)
@Composable
private fun PreviewDashboardSlotGridPortraitLight() {
    PreviewTheme(dark = false) { SlotGridPreviewContent(isLandscape = false) }
}

@Preview(name = "DashboardSlotGrid · landscape (dark)", showBackground = true, widthDp = 900)
@Composable
private fun PreviewDashboardSlotGridLandscapeDark() {
    PreviewTheme(dark = true) { SlotGridPreviewContent(isLandscape = true) }
}

// endregion
