package de.adv990de.obd2.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.crash.CrashReportingController
import de.adv990de.obd2.crash.CrashReportingStatus
import de.adv990de.obd2.crash.crashReportingStatus
import de.adv990de.obd2.data.AppPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Der Schalter "Absturzberichte senden" und die Statuszeile der App-Info (QS B33). Die Wirkung selbst hat der
 * `CrashReportingController` (er folgt der Preference); hier wird nur gelesen und geschrieben.
 */
@HiltViewModel
class CrashReportingSettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    controller: CrashReportingController,
) : ViewModel() {

    /** Ob diese Version einen DSN eingebaut hat. Ohne ihn gibt es nichts einzuschalten — die Zeile erscheint nicht. */
    val available: Boolean = controller.isAvailable

    val enabled: StateFlow<Boolean> = appPreferences.crashReportsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val status: StateFlow<CrashReportingStatus> = appPreferences.crashReportsEnabled
        .map { crashReportingStatus(available, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), crashReportingStatus(available, true))

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { appPreferences.setCrashReportsEnabled(value) }
    }
}
