package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.terrain.TerrainDisplay
import de.adv990de.obd2.terrain.TerrainInputs
import de.adv990de.obd2.terrain.TerrainTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Was die Gelaende-Kachel zeigt (Hoehe, Steigung, Peilung, Verlauf der Steigung); `null`, solange keine Rechnung laeuft oder noch kein Wert da ist — dann sagt der
 * Slot, was fehlt (`slotPlaceholderRes`). Herausgeloest aus dem `DashboardViewModel` (die Datei war auf ueber 490 Zeilen gewachsen); dort steht nur noch die Verdrahtung.
 *
 * **Unabhaengig von der Verbindung zum Fahrzeug:** die Werte kommen vom Telefon (GPS und Drucksensor), nicht vom Steuergeraet; die Kachel zeigt sie sofort, auch wenn
 * kein Adapter verbunden ist (Nutzerwunsch 2026-10-18; bis 1.35.0 hing sie an der Verbindung). Gerechnet wird, wenn ein Slot [DerivedPids.TERRAIN_ID] traegt ([slotPids]) und der
 * GPS-Schalter an ist ([useGpsSpeed]; die Kachel nutzt dessen Einstellung und Berechtigung) — **und nur, solange die Oberflaeche sammelt:** `WhileSubscribed` haelt GPS und Barometer
 * an, sobald das Dashboard 5 s lang nicht zu sehen ist (anderer Reiter, App im Hintergrund). Das ist zulaessig, weil der Fluss nur die Anzeige speist (s. `README`/`CLAUDE.md`,
 * „Zustand sammeln“) — nichts anderes haengt an ihm. Die Werte stehen bewusst **nicht** im `DashboardUiState`: dessen Zuruecksetzen bei jedem Verbindungsereignis
 * (`disconnected()`) loeschte sie sonst jedes Mal, obwohl sie mit der Verbindung nichts zu tun haben.
 *
 * Jede Aktivierung beginnt mit einem frischen [TerrainTracker] (Kalibrierung und Verlauf gelten nicht ueber eine Luecke hinweg): `onStart` setzt ihn zurueck, ob der Fluss neu
 * anlaeuft (Dashboard wieder sichtbar) oder die Bedingung wieder wahr wird (GPS-Schalter, Slot). Bei Abschalten der Bedingung ist der Stand sofort `null` — `flatMapLatest`
 * hat den vorigen Sammler vorher abgebrochen, ein spaeter Wert kann ihn nicht neu fuellen. `replayExpirationMillis = 0` setzt den Stand nach dem Anhalten auf `null` zurueck:
 * wer zum Dashboard zurueckkommt, sieht keinen alten Wert. [now] ist die monotone Uhr des ViewModels (Tests setzen sie).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun terrainDisplayFlow(
    scope: CoroutineScope,
    slotPids: Flow<List<String>>,
    useGpsSpeed: Flow<Boolean>,
    inputs: TerrainInputs,
    tracker: TerrainTracker,
    now: () -> Long,
): StateFlow<TerrainDisplay?> = combine(slotPids, useGpsSpeed) { slots, gps ->
    DerivedPids.TERRAIN_ID in slots && gps
}
    .distinctUntilChanged()
    .flatMapLatest<Boolean, TerrainDisplay?> { active ->
        if (active) {
            inputs.inputs
                .onStart { tracker.reset() }
                .map { input -> tracker.update(now(), input) }
        } else {
            flowOf(null)
        }
    }
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)
