package de.adv990de.obd2.ui.vehicleinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.ObdRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VehicleInfoViewModel @Inject constructor(private val repo: ObdRepository) : ViewModel() {
    private val _vin = MutableStateFlow("")
    val vin: StateFlow<String> = _vin
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun load() {
        viewModelScope.launch {
            _error.value = null
            try {
                _vin.value = repo.readVin() ?: ""
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = e.message ?: e.javaClass.simpleName
            }
        }
    }
}
