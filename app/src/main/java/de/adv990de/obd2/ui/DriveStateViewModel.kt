package de.adv990de.obd2.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DriveStateViewModel @Inject constructor(
    private val obdRepository: ObdRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    val hideNavOnDrive: StateFlow<Boolean> = appPreferences.hideNavOnDrive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isNavHidden: StateFlow<Boolean> = combine(
        obdRepository.isMovingFlow,
        appPreferences.hideNavOnDrive
    ) { moving, pref -> moving && pref }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleHideNavOnDrive() {
        viewModelScope.launch { appPreferences.setHideNavOnDrive(!hideNavOnDrive.value) }
    }
}
