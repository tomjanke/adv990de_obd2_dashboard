package de.adv990de.obd2.location

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Die Positionen des Ortungsdienstes als Fluss: ein Fix je [minIntervalMs] oder seltener, `null`, wenn der Anbieter abgeschaltet wurde. **Kalt:** der Listener wird beim Sammeln angemeldet
 * und beim Abbruch abgemeldet — wer nicht sammelt, hat keinen Listener. Laesst sich keiner anmelden (kein Ortungsdienst erreichbar, Berechtigung entzogen), kommt einmal `null`, und der Fluss
 * bleibt bis zum Abbruch offen.
 *
 * **Positionen sind sensibel:** sie stehen in keinem Log.
 */
fun LocationSpeedProvider.positionUpdates(minIntervalMs: Long): Flow<TrackFix?> = callbackFlow {
    val stop = startPositionUpdates(minIntervalMs) { trySend(it) }
    if (stop == null) trySend(null)
    awaitClose { stop?.invoke() }
}
