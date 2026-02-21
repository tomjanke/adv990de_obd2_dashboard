package de.adv990de.obd2.ui.dashboardconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.PidDefinition
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardConfigViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val obdRepository: ObdRepository
) : ViewModel() {

    // Pre-sorted once; the StateFlow below filters without re-sorting on each emission.
    private val _allPidsSorted: List<PidDefinition> = PidRegistry.all.values.sortedBy { it.name }

    /**
     * Alphabetically-sorted list of PIDs available for selection.
     * Null [supportedPidIds] (no connection / discovery failed) → full registry shown
     * so the user can pre-configure before connecting. After discovery, filtered to
     * only ECU-supported PIDs.
     *
     * Note: [selectedPidIds] in DataStore may contain IDs absent from the current
     * [allPids] (selected while disconnected, not supported by this ECU). Those PIDs
     * remain in DataStore but are invisible in the UI and silently skipped in livePids.
     */
    val allPids: StateFlow<List<PidDefinition>> = obdRepository.supportedPidIds
        .map { supported ->
            if (supported.isNullOrEmpty()) _allPidsSorted
            else _allPidsSorted.filter { it.id in supported }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _allPidsSorted)

    // ifEmpty guards against a DataStore wipe leaving an empty set, which would let the user
    // appear to have no PIDs selected while DashboardViewModel silently shows the defaults.
    val selectedPidIds: StateFlow<Set<String>> = appPreferences.selectedPidIds
        .map { ids -> ids.ifEmpty { AppPreferences.DEFAULT_PIDS } }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppPreferences.DEFAULT_PIDS
        )

    /** True while a live OBD2 session is active. Toggling a PID while connected restarts it. */
    val isConnected: StateFlow<Boolean> = obdRepository.isConnectedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun togglePid(id: String) {
        // DataStore.edit retries its lambda optimistically on write conflicts, so rapid
        // concurrent togglePid calls are safe: each retry sees the committed state of the
        // previous write, preserving correct toggle semantics without explicit deduplication.
        viewModelScope.launch { appPreferences.togglePid(id) }
    }
}
