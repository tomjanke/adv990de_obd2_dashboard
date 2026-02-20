package de.adv990de.obd2.ui.dtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.model.DtcEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DtcUiState(
    val dtcs: List<DtcEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DtcViewModel @Inject constructor(private val obdRepository: ObdRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(DtcUiState())
    val uiState: StateFlow<DtcUiState> = _uiState

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val dtcs = obdRepository.readDtcs()
                _uiState.value = DtcUiState(dtcs = dtcs)
            } catch (e: Exception) {
                _uiState.value = DtcUiState(error = e.message)
            }
        }
    }
}
