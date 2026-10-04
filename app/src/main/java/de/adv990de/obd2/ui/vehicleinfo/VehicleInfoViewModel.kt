package de.adv990de.obd2.ui.vehicleinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.ObdErrorMapper
import de.adv990de.obd2.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VehicleInfoViewModel @Inject constructor(private val repo: ObdRepository) : ViewModel() {
    private val _vin = MutableStateFlow("")
    val vin: StateFlow<String> = _vin
    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** Without a connection nothing can be read — the screen then says "Nicht verbunden", not "keine FIN verfuegbar". */
    val isConnected: StateFlow<Boolean> = repo.isConnectedFlow

    /** Counts [load] calls; a late result of an older call (started before the link dropped) is dropped. */
    private var generation = 0

    fun load() {
        val mine = ++generation
        if (!repo.isConnectedFlow.value) {
            _vin.value = ""
            _error.value = null
            _isLoading.value = false
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = repo.readVin() ?: ""
                if (mine == generation) _vin.value = result
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (mine == generation) _error.value = ObdErrorMapper.toUiText(e)
            } finally {
                if (mine == generation) _isLoading.value = false
            }
        }
    }
}
