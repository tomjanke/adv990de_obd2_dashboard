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
class DisplaySettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    val useImperial: StateFlow<Boolean> = appPreferences.useImperial
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val theme: StateFlow<String> = appPreferences.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    fun toggleUseImperial() {
        viewModelScope.launch { appPreferences.setUseImperial(!useImperial.value) }
    }

    fun setTheme(value: String) {
        viewModelScope.launch { appPreferences.setTheme(value) }
    }
}
