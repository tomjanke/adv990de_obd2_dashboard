package de.adv990de.obd2.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.ObdForegroundService
import de.adv990de.obd2.data.ObdRepository
import javax.inject.Inject

/**
 * Backs the "Beenden" item in [de.adv990de.obd2.ui.components.BottomNav]. [exitApp] is a plain
 * suspend fun rather than launching on [androidx.lifecycle.viewModelScope] itself (unlike the
 * otherwise identical [de.adv990de.obd2.ui.settings.SettingsViewModel.disconnect]): the caller needs
 * to await the disconnect before finishing the Activity, so it must own the coroutine.
 */
@HiltViewModel
class ExitAppViewModel @Inject constructor(
    private val obdRepository: ObdRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    suspend fun exitApp() {
        obdRepository.disconnect()
        appContext.stopService(ObdForegroundService.stopIntent(appContext))
    }
}
