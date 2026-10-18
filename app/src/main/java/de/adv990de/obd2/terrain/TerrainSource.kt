package de.adv990de.obd2.terrain

import android.os.SystemClock
import de.adv990de.obd2.location.GpsSpeedSource
import de.adv990de.obd2.location.LocationSpeedProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Was das `DashboardViewModel` von der Gelaende-Quelle kennt (Muster `FuelDistanceSink`): ein Fluss von Eingaben, der nur laeuft, solange jemand sammelt. */
interface TerrainInputs {
    val inputs: Flow<TerrainInput>

    companion object {
        /** Keine Eingaben — der Standard fuer Tests; Hilt bindet [TerrainSource] (s. `di/TerrainModule`). */
        val None: TerrainInputs = object : TerrainInputs {
            override val inputs: Flow<TerrainInput> = emptyFlow()
        }
    }
}

/**
 * Die Eingaben der Gelaende-Kachel: je GPS-Fix (eigener Listener, 1 Hz) eine [TerrainInput] mit dem **mittleren Luftdruck seit dem letzten Fix** ([PressureAverager]).
 *
 * Er fragt nichts ab, solange ihn niemand sammelt — das `DashboardViewModel` sammelt nur bei belegtem Slot, eingeschaltetem GPS-Schalter und stehender Verbindung.
 * Ohne Berechtigung wartet er (alle 2 s neu geprueft, wie `GpsSpeedSource`: eine spaeter erteilte Berechtigung wirkt ohne Neustart). **Nach [STALE_AFTER_MS] ohne Fix** meldet er
 * jede Sekunde eine Eingabe ohne Fix (aber mit Druck), bis wieder einer kommt: die Hoehe laeuft im Tunnel mit dem Barometer weiter, und Steigung und Peilung laufen kontrolliert aus,
 * statt mit dem letzten Wert stehenzubleiben. Ein abgeschalteter Anbieter (`null` vom Provider) zaehlt wie ein fehlender Fix. Ohne Drucksensor ist der Druck immer null.
 */
@Singleton
class TerrainSource @Inject constructor(
    private val location: LocationSpeedProvider,
    private val pressure: PressureProvider,
) : TerrainInputs {

    /** Monotone Uhr fuer das Altern der Druckproben; Tests setzen sie (Konvention wie `DashboardViewModel.nowMillis`, bewusst kein Konstruktor-Parameter: Hilt). */
    internal var nowMillis: () -> Long = { SystemClock.elapsedRealtime() }

    override val inputs: Flow<TerrainInput> = callbackFlow {
        while (!location.hasPermission()) delay(GpsSpeedSource.PERMISSION_RECHECK_MS)

        val averager = PressureAverager()
        val stopPressure = pressure.startUpdates { hPa -> averager.add(nowMillis(), hPa) }

        var staleJob: Job? = null
        fun emit(fix: GpsFix?) = trySend(TerrainInput(fix, averager.mean(nowMillis())))

        val stopFixes = location.startFixUpdates(FIX_INTERVAL_MS) { fix ->
            staleJob?.cancel()
            emit(fix)
            staleJob = launch {
                delay(STALE_AFTER_MS)
                while (isActive) {
                    emit(null)
                    delay(NO_FIX_INTERVAL_MS)
                }
            }
        }
        if (stopFixes == null) {
            awaitClose { stopPressure?.invoke() }
            return@callbackFlow
        }
        awaitClose {
            staleJob?.cancel()
            stopFixes()
            stopPressure?.invoke()
        }
    }.buffer(Channel.CONFLATED)

    internal companion object {
        /** Abstand der Fixes: Hoehe, Kurs und Genauigkeit brauchen keine 5 Hz wie die Geschwindigkeit. */
        const val FIX_INTERVAL_MS = 1_000L

        /** So lange ohne Fix gilt der letzte noch als aktuell; danach laeuft die Rechnung ohne ihn weiter. */
        const val STALE_AFTER_MS = 3_000L

        /** Takt der Eingaben ohne Fix. */
        const val NO_FIX_INTERVAL_MS = 1_000L
    }
}
