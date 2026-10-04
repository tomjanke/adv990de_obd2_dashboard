package de.adv990de.obd2.ui.freezeframe

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
class FreezeFrameViewModel @Inject constructor(private val repo: ObdRepository) : ViewModel() {
    private val _data = MutableStateFlow<Map<String, String>>(emptyMap())
    val data: StateFlow<Map<String, String>> = _data
    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** Without a connection nothing can be read — the screen then says "Nicht verbunden", not "nicht verfuegbar". */
    val isConnected: StateFlow<Boolean> = repo.isConnectedFlow

    /**
     * Counts [load] calls. The screen reloads on every connection change, so a read that was started
     * before the link dropped can still be in flight when the next [load] runs; its late result belongs
     * to a connection that no longer exists and is dropped (the adapter serialises the commands itself).
     */
    private var generation = 0

    fun load() {
        val mine = ++generation
        if (!repo.isConnectedFlow.value) {
            _data.value = emptyMap()
            _error.value = null
            _isLoading.value = false
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = repo.readFreezeFrame()
                if (mine == generation) _data.value = result
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (mine == generation) _error.value = ObdErrorMapper.toUiText(e)
            } finally {
                if (mine == generation) _isLoading.value = false
            }
        }
    }
}
