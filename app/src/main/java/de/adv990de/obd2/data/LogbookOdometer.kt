package de.adv990de.obd2.data

import de.adv990de.obd2.di.ApplicationScope
import de.adv990de.obd2.fuel.FuelDistanceSink
import de.adv990de.obd2.logbook.GpsOdometer
import de.adv990de.obd2.logbook.MAX_ODOMETER_KM
import de.adv990de.obd2.logbook.OdometerCounter
import de.adv990de.obd2.logbook.shouldPersistOdometer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Der GPS-Zaehler fuer den **geschaetzten** Kilometerstand des Logbuchs (Phase 3): zaehlt die gefahrenen Kilometer der Sitzung ([addDistance], dieselben wie die der Tankanzeige — das
 * `DashboardViewModel` meldet sie ueber [FuelDistanceSink], Hilt faechert sie auf, s. `FuelModule`) seit dem hoechsten Kilometerstand im Logbuch und haelt sie ueber Neustarts.
 *
 * Die Entscheidungen — wann der Zaehler bereit ist, wann er neu beginnt, was vor dem Laden zaehlt — stehen im reinen [OdometerCounter]; hier ist nur die Huelle: der gespeicherte Zaehler wird
 * einmal gelesen, der hoechste Stand des Logbuchs laufend ([VehicleLogRepository.maxOdometerKm]) und geschrieben wird **gedrosselt** (alle `ODOMETER_PERSIST_STEP_KM`, bei neuem Stand sofort, per
 * [flush] am Sitzungsende) ueber **einen** Schreiber mit einem Kanal, der nur den neuesten Wert behaelt. Ein Wert, der noch nicht gelesen ist, wird nie ueberschrieben (vorher ist [counter] `null`
 * und nichts wird geschrieben). Ein Schreibfehler ([IOException]) ist "best effort": der Zaehler bleibt im Speicher, der naechste Schritt oder [flush] versucht es erneut.
 *
 * **Grenze:** gezaehlt wird nur bei verbundener Sitzung und eingeschalteter "Geschwindigkeit per GPS" — die Schaetzung kann zu niedrig sein, nie wesentlich hoeher als gefahren.
 */
@Singleton
class LogbookOdometer @Inject constructor(
    private val appPreferences: AppPreferences,
    log: VehicleLogRepository,
    @ApplicationScope scope: CoroutineScope,
) : FuelDistanceSink {

    private val state = MutableStateFlow(OdometerCounter())

    /** Der zuletzt zum Schreiben uebergebene (bzw. gelesene) Wert — nur fuer die Drosselung und [flush]. */
    @Volatile
    private var persisted: GpsOdometer? = null

    private val writeQueue = Channel<GpsOdometer>(Channel.CONFLATED)

    /** Der Zaehler zum hoechsten Stand im Logbuch; `null`, solange er nicht bereit ist oder das Logbuch keinen Eintrag hat. */
    val counter: Flow<GpsOdometer?> = state.map { it.counter }.distinctUntilChanged()

    init {
        scope.launch {
            for (c in writeQueue) {
                try {
                    appPreferences.setGpsOdometer(c.anchorKm, c.gpsKm)
                } catch (_: IOException) {
                    // Best effort: der Zaehler bleibt im Speicher, der naechste Schritt oder flush() versucht es erneut.
                }
            }
        }
        scope.launch {
            val stored = appPreferences.gpsOdometer.first()
            persisted = stored
            update { it.withStored(stored) }
        }
        scope.launch { log.maxOdometerKm.collect { max -> update { it.withLoggedMax(max) } } }
    }

    override fun addDistance(km: Float) {
        update { it.withDistance(km) }
    }

    override fun flush() {
        val current = state.value.counter ?: return
        if (current != persisted) persist(current)
    }

    /**
     * Stellt den Zaehler aus einem Paket wieder her und schreibt ihn sofort. Pruft selbst (der Schreiber faengt nur [IOException], ein `require` des Setters wuerde den Scope beenden):
     * Anker 0..[MAX_ODOMETER_KM], Kilometer endlich und >= 0, sonst Rueckkehr ohne Wirkung. Zum Rennen mit der Meldung des Logbuchs s. [OdometerCounter.withRestored].
     */
    fun restore(value: GpsOdometer) {
        if (value.anchorKm !in 0..MAX_ODOMETER_KM || !value.gpsKm.isFinite() || value.gpsKm < 0f) return
        state.update { it.withRestored(value) }
        persist(value)
    }

    /** Der Zaehler im Speicher (`null`, solange er nicht bereit ist oder das Logbuch keinen Eintrag hat). */
    fun current(): GpsOdometer? = state.value.counter

    private fun update(transform: (OdometerCounter) -> OdometerCounter) {
        val counter = state.updateAndGet(transform).counter ?: return
        if (shouldPersistOdometer(counter, persisted)) persist(counter)
    }

    private fun persist(counter: GpsOdometer) {
        persisted = counter
        writeQueue.trySend(counter)
    }
}
