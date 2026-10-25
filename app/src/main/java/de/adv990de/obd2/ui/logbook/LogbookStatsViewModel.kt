package de.adv990de.obd2.ui.logbook

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.logbook.FluidReport
import de.adv990de.obd2.logbook.FuelReport
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.fluidReport
import de.adv990de.obd2.logbook.fuelReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Der Name des Navigationsarguments mit der Art (`logbook_stats/{type}`): der Name von [LogType.FUEL], [LogType.OIL] oder [LogType.COOLANT]. */
const val LOG_STATS_TYPE_ARG = "type"

/** Was die Detailseite der Auswertung zeigt: noch nichts ([Loading]), ein unbrauchbares Argument ([Invalid], die Seite geht zurueck) oder der Bericht der Art. */
sealed interface LogbookStatsState {
    data object Loading : LogbookStatsState
    data object Invalid : LogbookStatsState
    data class Fuel(val report: FuelReport) : LogbookStatsState
    data class Fluid(val type: LogType, val report: FluidReport) : LogbookStatsState
}

/**
 * Die Detailseite der Auswertung einer Art ([LOG_STATS_TYPE_ARG]): der Bericht ([fuelReport] bzw. [fluidReport]) aus allen Eintraegen des Repositorys, immer neu, wenn sich das Logbuch
 * aendert. **Grenze:** ein Eintrag im Rueckgaengig-Fenster der Liste (zehn Sekunden) zaehlt hier noch mit — das Fenster gehoert dem `LogbookViewModel`, diese Seite liest das Repository.
 */
@HiltViewModel
class LogbookStatsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: VehicleLogRepository,
) : ViewModel() {

    /** Die Art, oder `null` bei fehlendem oder unbrauchbarem Argument (nur Tanken, Oel und Kuehlfluessigkeit haben eine Auswertung). */
    val type: LogType? = savedStateHandle.get<String>(LOG_STATS_TYPE_ARG)
        ?.let { name -> LogType.entries.firstOrNull { it.name == name } }
        ?.takeIf { it == LogType.FUEL || it == LogType.OIL || it == LogType.COOLANT }

    val state: StateFlow<LogbookStatsState> = when (val type = type) {
        null -> MutableStateFlow(LogbookStatsState.Invalid)
        LogType.FUEL -> repository.entries.map<List<LogEntry>, LogbookStatsState> { LogbookStatsState.Fuel(fuelReport(it)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogbookStatsState.Loading)
        else -> repository.entries.map<List<LogEntry>, LogbookStatsState> { LogbookStatsState.Fluid(type, fluidReport(it, type)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogbookStatsState.Loading)
    }
}
