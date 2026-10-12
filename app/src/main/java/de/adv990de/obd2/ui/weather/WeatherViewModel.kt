package de.adv990de.obd2.ui.weather

import android.os.SystemClock
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
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

private const val POLL_INTERVAL_MS = 60_000L

/**
 * Produktions-Uhr fuer das 30-Minuten-Haltefenster ([OUTSIDE_TEMP_HOLD]) — `SystemClock.
 * elapsedRealtimeNanos()` statt [TimeSource.Monotonic]. Letzteres ist `System.nanoTime()`, das laut
 * Androids `SystemClock`-Dokumentation waehrend Tiefschlaf pausiert. [WeatherViewModel] ist
 * Activity-gebunden und ueberlebt genau solche Pausen (z. B. Handy gesperrt in der Tasche waehrend
 * eines laengeren Stopps): Eine reale Mehrstunden-Pause mit schlafendem Geraet wuerde auf der
 * monotonen Uhr kaum vergehen, sodass ein davor gehaltener Wert die 30-Minuten-Grenze weit
 * ueberdauern koennte — das Gegenteil dessen, was die Haltelogik garantieren soll.
 *
 * Dieselbe Begruendung wie `DashboardViewModel`s Oel-Balken-Timer ("Bewusst nicht uptimeMillis():
 * elapsedRealtime zaehlt Tiefschlaf mit"), hier nur als [TimeSource] statt `() -> Long`, weil
 * [holdOutsideTemp] mit `Duration` statt Millisekunden arbeitet. NICHT die Begruendung des
 * OBD-Watchdogs (`ObdRepository.livePids`): dessen [TimeSource.Monotonic] bleibt fuer seinen eigenen
 * Anwendungsfall richtig — er misst Luecken nur waehrend aktiven Pollings, nie ueber ein
 * hintergrundgeschicktes Activity/App hinweg, also nie ueber einen Tiefschlaf von realer Dauer.
 */
internal val elapsedRealtimeSource: TimeSource = timeSourceFrom { SystemClock.elapsedRealtimeNanos() }

/**
 * Eine [TimeSource] auf einer beliebigen Nanosekunden-Uhr [readNanos]. Eigene Funktion, damit Tests die Uhr vorgeben koennen
 * (die echte, [elapsedRealtimeSource], braucht Android und hat einen Geraetetest): geprueft ist, dass die Zeitquelle AUSSCHLIESSLICH
 * die gelieferte Uhr liest und die verstrichene Zeit korrekt daraus bildet.
 */
internal fun timeSourceFrom(readNanos: () -> Long): TimeSource = object : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    override fun read(): Long = readNanos()
}

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
 * [OUTSIDE_TEMP_HOLD] stehen (s. [holdOutsideTemp]), gemessen auf einer [TimeSource] — der
 * Parameter existiert nur, damit Tests die virtuelle Uhr von kotlinx-coroutines-test
 * (`testTimeSource`) einsetzen koennen, genau wie bei `ObdRepository.livePids(timeSource = ...)`;
 * das ist die einzige Gemeinsamkeit mit dem OBD-Watchdog. Hilt nutzt den `@Inject`-Konstruktor mit
 * [elapsedRealtimeSource] — bewusst NICHT [TimeSource.Monotonic] wie der OBD-Watchdog, s. dessen
 * eigenes KDoc oben fuer die Begruendung.
 */
@HiltViewModel
class WeatherViewModel internal constructor(
    private val weatherRepository: WeatherRepository,
    timeSource: TimeSource,
) : ViewModel() {

    @Inject
    constructor(weatherRepository: WeatherRepository) : this(weatherRepository, elapsedRealtimeSource)

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
