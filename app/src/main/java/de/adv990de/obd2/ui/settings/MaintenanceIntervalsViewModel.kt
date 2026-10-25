package de.adv990de.obd2.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.seedBlocking
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.MAINTENANCE_TYPES
import de.adv990de.obd2.logbook.MaintenanceIntervalLimits
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Die Seite "Wartungsintervalle" (Einstellungen, Gruppe "Fahrzeug"): die vom Nutzer festgelegten Intervalle des Logbuchs in km je Art — **ohne Vorgabe**. */
@HiltViewModel
class MaintenanceIntervalsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
) : ViewModel() {

    /** Die festgelegten Intervalle; eine Art ohne Wert fehlt. Startwert synchron gelesen: die Zeilen zeigen nie kurz "nicht festgelegt". */
    val intervals: StateFlow<Map<LogType, Int>> = appPreferences.maintenanceIntervals.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        seedBlocking(emptyMap(), source = appPreferences.maintenanceIntervals),
    )

    /** Legt das Intervall der [type] in km fest; 0 loescht es. Eine unbrauchbare Eingabe (andere Art, ausserhalb von [MaintenanceIntervalLimits]) wird nicht geschrieben — das Feld prueft sie schon. */
    fun setInterval(type: LogType, km: Int) {
        if (type !in MAINTENANCE_TYPES) return
        if (km != 0 && km !in MaintenanceIntervalLimits.MIN_KM..MaintenanceIntervalLimits.MAX_KM) return
        viewModelScope.launch { appPreferences.setMaintenanceInterval(type, km) }
    }
}
