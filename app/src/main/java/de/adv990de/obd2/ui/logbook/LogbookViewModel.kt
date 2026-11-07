package de.adv990de.obd2.ui.logbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogbookOdometer
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.di.ApplicationScope
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogFilter
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.LogbookInsights
import de.adv990de.obd2.logbook.MAINTENANCE_TYPES
import de.adv990de.obd2.logbook.MaintenanceIntervalLimits
import de.adv990de.obd2.logbook.MaintenanceRow
import de.adv990de.obd2.logbook.estimateKm
import de.adv990de.obd2.logbook.gpsShareKm
import de.adv990de.obd2.logbook.maintenanceRows
import de.adv990de.obd2.logbook.logbookInsights
import de.adv990de.obd2.ui.components.UndoableDeletion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Die Uebersicht der Wartung: [rows] (je Art das Intervall des Nutzers und die Faelligkeit), [loggedKm] (hoechster Stand im Logbuch), [estimatedKm] (er plus die GPS-Kilometer seit diesem Stand,
 * `null` ohne Eintrag) und [gpsShareKm] (wie viele Kilometer die Schaetzung ueber dem Logbuch-Stand liegt; 0 = es steht der Logbuch-Stand, ohne "ca.").
 */
data class MaintenanceState(val rows: List<MaintenanceRow>, val loggedKm: Int?, val estimatedKm: Int?, val gpsShareKm: Int)

/**
 * Die Logbuch-Liste: Filter, Loeschen nach dem Loesch-Vertrag (Papierkorb oder Wischen, ohne Dialog, nur Rueckgaengig), die Auswertung und die Wartungs-Uebersicht. Export und
 * Import des Logbuchs laufen ueber die Seite „Daten sichern und wiederherstellen“, die Symbole der Kopfleiste fuehren nur dorthin.
 */
@HiltViewModel
class LogbookViewModel @Inject constructor(
    private val repository: VehicleLogRepository,
    private val appPreferences: AppPreferences,
    odometer: LogbookOdometer,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {

    /** Loeschen mit Rueckgaengig; laeuft im app-weiten Scope, damit ein Loeschen im Fenster das ViewModel ueberlebt (s. [onCleared]). */
    private val deletion = UndoableDeletion<Long>(scope = appScope) { ids -> repository.delete(ids) }

    /** Alles, was der Nutzer sieht, ungefiltert: ohne die Eintraege im Rueckgaengig-Fenster. */
    private val visible: StateFlow<List<LogEntry>> =
        combine(repository.entries, deletion.pending) { all, pending -> if (pending.isEmpty()) all else all.filter { it.id !in pending } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow(LogFilter.All)
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    /** Die Liste, neueste zuerst (die Reihenfolge des Repositorys), nach dem Filter. */
    val entries: StateFlow<List<LogEntry>> =
        combine(visible, _filter) { all, filter -> all.filter { filter.matches(it.type) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Gibt es ueberhaupt einen sichtbaren Eintrag (egal welcher Filter)? Unterscheidet den leeren Filter vom leeren Logbuch. */
    val hasEntries: StateFlow<Boolean> = visible.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Die Auswertung (Verbrauch, Wartung, Wert je Zeile) aus **allem**, was der Nutzer sieht — nicht vom Filter eingeengt (die Karten zeigen das ganze Logbuch) und ohne die
     * Eintraege im Rueckgaengig-Fenster. Die Rechnung ist rein und fuer einige hundert Eintraege billig (Millisekunden).
     */
    val insights: StateFlow<LogbookInsights> =
        visible.map { logbookInsights(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), logbookInsights(emptyList()))

    /**
     * Die Wartungs-Uebersicht mit den Intervallen des Nutzers (ohne Vorgabe: leer, bis er eines festlegt) und dem **geschaetzten** Stand: der hoechste Logbuch-Stand plus die GPS-Kilometer
     * seit diesem Stand ([LogbookOdometer], gehoert zu genau diesem Stand). Wie die Auswertung aus allem, was der Nutzer sieht — ohne Eintraege im Rueckgaengig-Fenster.
     */
    val maintenance: StateFlow<MaintenanceState> =
        combine(insights, appPreferences.maintenanceIntervals, odometer.counter) { insights, intervals, counter ->
            val logged = insights.maintenance.firstOrNull()?.currentKm
            val estimated = estimateKm(logged, counter)
            MaintenanceState(maintenanceRows(insights.maintenance, intervals, estimated), logged, estimated, gpsShareKm(logged, counter))
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MaintenanceState(maintenanceRows(logbookInsights(emptyList()).maintenance, emptyMap(), null), null, null, 0),
        )

    /** Legt das Intervall der [type] in km fest; 0 loescht es. Eine unbrauchbare Eingabe (andere Art, ausserhalb von [MaintenanceIntervalLimits]) wird nicht geschrieben — das Feld prueft sie schon. */
    fun setInterval(type: LogType, km: Int) {
        if (type !in MAINTENANCE_TYPES) return
        if (km != 0 && km !in MaintenanceIntervalLimits.MIN_KM..MaintenanceIntervalLimits.MAX_KM) return
        viewModelScope.launch { appPreferences.setMaintenanceInterval(type, km) }
    }

    fun setFilter(filter: LogFilter) {
        _filter.value = filter
    }

    // ── Loeschen ──

    /** Nicht null, solange ein Loeschen noch zurueckgenommen werden kann — treibt die "Rueckgaengig"-Snackbar. */
    val undoBatchId: StateFlow<Long?> = deletion.openBatchId

    /** Blendet den Eintrag sofort aus und loescht ihn nach dem Rueckgaengig-Fenster wirklich. */
    fun delete(id: Long) = deletion.schedule(setOf(id))

    fun undoDelete() = deletion.undo()

    /** Die Snackbar "Rueckgaengig" verschwindet mit der Seite, also wird das Loeschen jetzt endgueltig. */
    override fun onCleared() {
        deletion.flush()
    }
}
