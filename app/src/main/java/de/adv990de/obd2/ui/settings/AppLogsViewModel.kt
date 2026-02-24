package de.adv990de.obd2.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.db.entity.AppLogEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppLogsViewModel @Inject constructor(
    private val appLogRepository: AppLogRepository
) : ViewModel() {

    init {
        viewModelScope.launch { appLogRepository.pruneOlderThan() }
    }

    val logs: StateFlow<List<AppLogEntity>> = appLogRepository.logs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearAll() {
        viewModelScope.launch { appLogRepository.clearAll() }
    }
}
