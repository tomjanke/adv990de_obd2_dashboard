package de.adv990de.obd2.ui.settings

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
class RecordingSettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    val logOnlyDtc: StateFlow<Boolean> = appPreferences.logOnlyDtcSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleLogOnlyDtc() {
        viewModelScope.launch { appPreferences.setLogOnlyDtcSessions(!logOnlyDtc.value) }
    }
}
