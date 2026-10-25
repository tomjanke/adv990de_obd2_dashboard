package de.adv990de.obd2.ui.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * GPS-Geschwindigkeit in km/h, null wenn das Feature aus ist ([useGpsSpeed]), keine Berechtigung vorliegt oder
 * kein Fix mit Geschwindigkeit ansteht ([speedKmh], die `GpsSpeedSource`). Herausgeloest aus der `ReadingsPipeline`.
 *
 * `Eagerly` ist hier Pflicht, nicht Geschmack: Ein `WhileSubscribed`-Flow ohne Sammler startet
 * seinen Upstream nie — der GPS-Empfaenger liefe nie an. Genau dieser Fehler liess die
 * GPS-Geschwindigkeit bis 1.10.1 nie im Dashboard ankommen. Seit 1.15.2 wird dieser Flow
 * zusaetzlich per `collect` in `ReadingsPipeline.gpsRefresh` gesammelt (nicht mehr nur ueber [StateFlow.value]
 * gelesen), damit ein neuer GPS-Fix die Anzeige sofort ausloest statt auf den naechsten
 * OBD-Takt zu warten.
 *
 * Der `flatMapLatest` sorgt dafuer, dass der GPS-Empfaenger trotzdem nur laeuft, solange der
 * Schalter an ist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun gpsSpeedFlow(scope: CoroutineScope, useGpsSpeed: Flow<Boolean>, speedKmh: Flow<Float?>): StateFlow<Float?> =
    useGpsSpeed
        .flatMapLatest { enabled -> if (enabled) speedKmh else flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)
