package de.adv990de.obd2.ui.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.weather.WeatherRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.time.TimeSource

private const val POLL_INTERVAL_MS = 60_000L

/**
 * Aussentemperatur fuer die `AmbientStatusRow`, einmal pro Minute abgefragt — aber nur, solange
 * jemand [outsideTempC] sammelt. Kalter Flow + `WhileSubscribed(5_000)` (dieselbe Form wie die
 * uebrigen StateFlows der App): Ist die Zeile nicht zu sehen (Hauptmenue sichtbar, App im
 * Hintergrund), laeuft keine Abfrage und es verlassen keine Koordinaten das Geraet. `BottomNav`
 * sammelt deshalb erst im Zweig, der die Zeile tatsaechlich zeigt.
 *
 * Die 5 s Karenz ueberbruecken kurze Unterbrechungen (z. B. den `AnimatedContent`-Wechsel) ohne
 * Neustart; nach laengerer Pause beginnt der Takt mit einem sofortigen Abruf neu.
 *
 * Ein fehlgeschlagener Abruf leert die Anzeige nicht: der letzte gute Wert bleibt bis zu
 * [OUTSIDE_TEMP_HOLD] stehen (s. [holdOutsideTemp]). Gemessen auf der monotonen [TimeSource] — der
 * Parameter existiert nur, damit Tests die virtuelle Uhr von kotlinx-coroutines-test
 * (`testTimeSource`) einsetzen koennen, genau wie bei `ObdRepository.livePids(timeSource = ...)`;
 * Hilt nutzt den `@Inject`-Konstruktor mit [TimeSource.Monotonic].
 */
@HiltViewModel
class WeatherViewModel internal constructor(
    private val weatherRepository: WeatherRepository,
    timeSource: TimeSource,
) : ViewModel() {

    @Inject
    constructor(weatherRepository: WeatherRepository) : this(weatherRepository, TimeSource.Monotonic)

    /** Nullpunkt der Zeitachse von [HeldOutsideTemp.refreshedAt]. */
    private val clockOrigin = timeSource.markNow()

    /**
     * Haelt den letzten guten Wert ueber Neustarts des Pollings hinweg (`WhileSubscribed` beendet
     * und startet den Flow-Block neu, die ViewModel-Property bleibt). Nur im Flow-Block gelesen und
     * geschrieben: `stateIn` betreibt hoechstens eine Upstream-Sammlung zugleich, auf
     * `viewModelScope` (Main) — der Abruf selbst wechselt intern auf IO und kehrt zurueck.
     */
    private var held: HeldOutsideTemp? = null

    val outsideTempC: StateFlow<Float?> = flow {
        // (Neu-)Start: erst pruefen, ob der gecachte Wert noch gezeigt werden darf. Der StateFlow
        // behaelt seinen letzten Wert ueber die Pause hinweg; ohne diese Pruefung stuende z. B. ein
        // zwei Stunden alter Wert bis zum Ende des ersten neuen Abrufs weiter da.
        emit(update(fetchedC = null))
        while (true) {
            val fetchedC = weatherRepository.fetchCurrentTemperatureC()
            emit(update(fetchedC))
            delay(POLL_INTERVAL_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun update(fetchedC: Float?): Float? {
        held = holdOutsideTemp(fetchedC = fetchedC, now = clockOrigin.elapsedNow(), held = held)
        return held?.tempC
    }
}
