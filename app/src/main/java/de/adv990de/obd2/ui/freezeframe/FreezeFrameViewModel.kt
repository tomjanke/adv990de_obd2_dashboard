package de.adv990de.obd2.ui.freezeframe

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
class FreezeFrameViewModel @Inject constructor(private val repo: ObdRepository) : ViewModel() {
    private val _data = MutableStateFlow<Map<String, String>>(emptyMap())
    val data: StateFlow<Map<String, String>> = _data
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _data.value = repo.readFreezeFrame()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _isLoading.value = false
            }
        }
    }
}
