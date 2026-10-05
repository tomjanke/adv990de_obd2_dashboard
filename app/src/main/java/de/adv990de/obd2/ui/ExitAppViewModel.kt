package de.adv990de.obd2.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.ObdServiceController
import javax.inject.Inject

/**
 * Backs the "Beenden" item in [de.adv990de.obd2.ui.components.BottomNav]. [exitApp] is a plain
 * suspend fun rather than launching on [androidx.lifecycle.viewModelScope] itself (unlike the
 * otherwise identical [de.adv990de.obd2.ui.settings.SettingsViewModel.disconnect]): the caller needs
 * to await the disconnect before finishing the Activity, so it must own the coroutine.
 */
@HiltViewModel
class ExitAppViewModel @Inject constructor(
    private val serviceController: ObdServiceController,
) : ViewModel() {

    suspend fun exitApp() = serviceController.endSession()
}
