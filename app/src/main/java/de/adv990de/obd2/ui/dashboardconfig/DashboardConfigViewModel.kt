package de.adv990de.obd2.ui.dashboardconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardConfigViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    val selectedPidIds: StateFlow<Set<String>> = appPreferences.selectedPidIds
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppPreferences.DEFAULT_PIDS
        )

    fun togglePid(id: String) {
        viewModelScope.launch { appPreferences.togglePid(id) }
    }
}
