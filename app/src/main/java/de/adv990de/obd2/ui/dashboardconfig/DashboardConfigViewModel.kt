package de.adv990de.obd2.ui.dashboardconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.PidDefinition
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardConfigViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    /** Stable list — computed once so the Screen doesn't allocate on every recomposition. */
    val allPids: List<PidDefinition> = PidRegistry.all.values.toList()

    val selectedPidIds: StateFlow<Set<String>> = appPreferences.selectedPidIds
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppPreferences.DEFAULT_PIDS
        )

    fun togglePid(id: String) {
        // DataStore.edit retries its lambda optimistically on write conflicts, so rapid
        // concurrent togglePid calls are safe: each retry sees the committed state of the
        // previous write, preserving correct toggle semantics without explicit deduplication.
        viewModelScope.launch { appPreferences.togglePid(id) }
    }
}
