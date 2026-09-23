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
class GearSettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    val wheelCircumferenceMm: StateFlow<Float> = appPreferences.wheelCircumferenceMm
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM)

    val primaryRatio: StateFlow<Float> = appPreferences.primaryRatio
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.DEFAULT_PRIMARY_RATIO)

    val secondaryRatio: StateFlow<Float> = appPreferences.secondaryRatio
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.DEFAULT_SECONDARY_RATIO)

    val gearRatios: StateFlow<List<Float>> = appPreferences.gearRatios
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.DEFAULT_GEAR_RATIOS)

    fun setWheelCircumferenceMm(value: Float) {
        viewModelScope.launch { appPreferences.setWheelCircumferenceMm(value) }
    }

    fun setPrimaryRatio(value: Float) {
        viewModelScope.launch { appPreferences.setPrimaryRatio(value) }
    }

    fun setSecondaryRatio(value: Float) {
        viewModelScope.launch { appPreferences.setSecondaryRatio(value) }
    }

    fun setGearRatio(index: Int, value: Float) {
        viewModelScope.launch { appPreferences.setGearRatio(index, value) }
    }
}
