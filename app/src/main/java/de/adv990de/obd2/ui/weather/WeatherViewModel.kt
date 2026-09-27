package de.adv990de.obd2.ui.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.weather.WeatherRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val POLL_INTERVAL_MS = 60_000L

@HiltViewModel
class WeatherViewModel @Inject constructor(
    private val weatherRepository: WeatherRepository,
) : ViewModel() {

    private val _outsideTempC = MutableStateFlow<Float?>(null)
    val outsideTempC: StateFlow<Float?> = _outsideTempC.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                _outsideTempC.value = weatherRepository.fetchCurrentTemperatureC()
                delay(POLL_INTERVAL_MS)
            }
        }
    }
}
