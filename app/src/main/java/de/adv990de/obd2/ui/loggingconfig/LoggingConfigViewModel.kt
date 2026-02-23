package de.adv990de.obd2.ui.loggingconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoggingConfigViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val obdRepository: ObdRepository
) : ViewModel() {

    // Pre-sorted once; never changes at runtime.
    val allPids: List<PidDefinition> = PidRegistry.all.values.sortedBy { it.name }

    val loggedPidIds: StateFlow<Set<String>> = appPreferences.loggedPidIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.ALL_PID_IDS)

    val latestReadings: StateFlow<List<SensorValue>> = obdRepository.latestReadings

    fun togglePid(id: String) {
        viewModelScope.launch { appPreferences.toggleLoggedPid(id) }
    }

    fun selectAll() {
        viewModelScope.launch { appPreferences.setLoggedPids(AppPreferences.ALL_PID_IDS) }
    }

    fun deselectAll() {
        viewModelScope.launch { appPreferences.setLoggedPids(emptySet()) }
    }
}
