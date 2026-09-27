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
 */
@HiltViewModel
class WeatherViewModel @Inject constructor(
    private val weatherRepository: WeatherRepository,
) : ViewModel() {

    val outsideTempC: StateFlow<Float?> = flow {
        while (true) {
            emit(weatherRepository.fetchCurrentTemperatureC())
            delay(POLL_INTERVAL_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
