package de.adv990de.obd2.ui.dashboardconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardConfigViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val obdRepository: ObdRepository
) : ViewModel() {

    /** True while a live OBD2 session is active. */
    val isConnected: StateFlow<Boolean> = obdRepository.isConnectedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val rpmBlinkThreshold: StateFlow<Int> = appPreferences.rpmBlinkThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setRpmBlinkThreshold(value: Int) {
        viewModelScope.launch { appPreferences.setRpmBlinkThreshold(value) }
    }
}
