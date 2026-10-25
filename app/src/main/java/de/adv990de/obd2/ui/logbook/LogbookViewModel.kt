package de.adv990de.obd2.ui.logbook

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.FileTooLargeException
import de.adv990de.obd2.data.LogbookOdometer
import de.adv990de.obd2.data.LogbookExporter
import de.adv990de.obd2.data.LogbookImporter
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.di.ApplicationScope
import de.adv990de.obd2.logbook.ImportPlan
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
import de.adv990de.obd2.logbook.logbookFileName
import de.adv990de.obd2.logbook.logbookInsights
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.components.UndoableDeletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject

/** Wo ein Import steht: nichts ([Idle]), lesen oder schreiben ([Working]), die Vorschau wartet auf die Bestaetigung ([Preview]), fertig ([Done]) oder gescheitert ([Failed]). */
sealed interface ImportState {
    data object Idle : ImportState
    data object Working : ImportState
    data class Preview(val plan: ImportPlan) : ImportState
    data class Done(val count: Int) : ImportState
    data class Failed(val text: UiText) : ImportState
}

/**
 * Die Uebersicht der Wartung: [rows] (je Art das Intervall des Nutzers und die Faelligkeit), [loggedKm] (hoechster Stand im Logbuch), [estimatedKm] (er plus die GPS-Kilometer seit diesem Stand,
 * `null` ohne Eintrag) und [gpsShareKm] (wie viele Kilometer die Schaetzung ueber dem Logbuch-Stand liegt; 0 = es steht der Logbuch-Stand, ohne "ca.").
 */
data class MaintenanceState(val rows: List<MaintenanceRow>, val loggedKm: Int?, val estimatedKm: Int?, val gpsShareKm: Int)

/**
 * Die Logbuch-Liste: Filter, Loeschen nach dem Loesch-Vertrag (Papierkorb oder Wischen, ohne Dialog, nur Rueckgaengig), Export (Teilen oder Speichern) und Import
 * (Datei lesen, Vorschau, erst nach der Bestaetigung schreiben). Der Export enthaelt das **ganze** Logbuch ohne die Eintraege im Rueckgaengig-Fenster — der Filter
 * gilt nur fuer die Anzeige, die Sicherung soll vollstaendig sein.
 */
@HiltViewModel
class LogbookViewModel @Inject constructor(
    private val repository: VehicleLogRepository,
    private val exporter: LogbookExporter,
    private val importer: LogbookImporter,
    private val appPreferences: AppPreferences,
    odometer: LogbookOdometer,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {

    /** "Heute" fuer die Pruefung beim Import (Datum nicht in der Zukunft); nur Tests setzen es. */
    internal var today: () -> LocalDate = { LocalDate.now() }

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

    // ── Export (Teilen oder Speichern), wie bei den Fahrten und dem App-Log ──

    private val _exportDialogVisible = MutableStateFlow(false)

    /** Der Dialog "Logbuch exportieren" (Teilen oder Speichern); liegt hier, damit eine Drehung ihn offen laesst. */
    val exportDialogVisible: StateFlow<Boolean> = _exportDialogVisible.asStateFlow()

    private val _shareUri = MutableStateFlow<Uri?>(null)

    /** Nicht null, sobald "Teilen" die Datei geschrieben hat: die Oberflaeche reicht sie dem Teilen-Menue und ruft [consumeShareUri]. */
    val shareUri: StateFlow<Uri?> = _shareUri.asStateFlow()

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    private val _saved = MutableStateFlow(false)

    /** Wahr, nachdem das Logbuch in die vom Nutzer gewaehlte Datei geschrieben wurde (die Snackbar sagt es). */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _nothingToExport = MutableStateFlow(false)

    /** Wahr, wenn exportiert werden sollte, aber nichts mehr sichtbar ist (alles im Rueckgaengig-Fenster ausgeblendet). */
    val nothingToExport: StateFlow<Boolean> = _nothingToExport.asStateFlow()

    /** Export-Symbol der Kopfleiste: erst fragen, wie — aber nur, wenn es etwas zu exportieren gibt. */
    fun requestExport() {
        if (visible.value.isNotEmpty()) _exportDialogVisible.value = true
    }

    fun cancelExport() {
        _exportDialogVisible.value = false
    }

    /** "Teilen": das ganze sichtbare Logbuch in EINER CSV-Datei, dem Teilen-Menue uebergeben. */
    fun shareExport() {
        _exportDialogVisible.value = false
        if (visible.value.isEmpty()) {
            _nothingToExport.value = true
            return
        }
        val hidden = deletion.pending.value
        viewModelScope.launch {
            _exportError.value = null
            try {
                _shareUri.value = exporter.exportToCsv(hidden)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _exportError.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** "Speichern": dieselbe CSV, in die Datei geschrieben, die der Nutzer mit dem Dateidialog gewaehlt hat ([target]). */
    fun saveExportTo(target: Uri) {
        if (visible.value.isEmpty()) {
            _nothingToExport.value = true
            return
        }
        val hidden = deletion.pending.value
        viewModelScope.launch {
            _exportError.value = null
            try {
                exporter.saveCsv(target, hidden)
                _saved.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _exportError.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Der Name, den der Dateidialog des Systems vorschlaegt: `logbook_<UTC-Zeit>.csv`. */
    fun exportFileName(): String = logbookFileName(System.currentTimeMillis())

    fun consumeShareUri() {
        _shareUri.value = null
    }

    fun consumeExportError() {
        _exportError.value = null
    }

    fun consumeSaved() {
        _saved.value = false
    }

    fun consumeNothingToExport() {
        _nothingToExport.value = false
    }

    // ── Import ──

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    /** Import-Symbol: die gewaehlte Datei lesen und pruefen, daraus die Vorschau machen — es wird nichts geschrieben. Waehrend gearbeitet wird, zaehlt ein zweiter Aufruf nicht. */
    fun pickImport(source: Uri) {
        if (_importState.value is ImportState.Working) return
        _importState.value = ImportState.Working
        viewModelScope.launch {
            try {
                _importState.value = ImportState.Preview(importer.read(source, today()))
            } catch (e: CancellationException) {
                _importState.value = ImportState.Idle
                throw e
            } catch (e: Exception) {
                _importState.value = ImportState.Failed(readFailureText(e))
            }
        }
    }

    /** Bestaetigt die Vorschau: schreibt den Plan in einer Transaktion. Ohne Vorschau, oder wenn schon gearbeitet wird, passiert nichts (kein doppeltes Schreiben). */
    fun confirmImport() {
        val preview = _importState.value as? ImportState.Preview ?: return
        _importState.value = ImportState.Working
        viewModelScope.launch {
            try {
                _importState.value = ImportState.Done(importer.apply(preview.plan))
            } catch (e: CancellationException) {
                _importState.value = ImportState.Idle
                throw e
            } catch (e: Exception) {
                _importState.value = ImportState.Failed(UiText.StringResource(R.string.logbook_import_error_write))
            }
        }
    }

    /** Schliesst Vorschau, Ergebnis oder Fehler; waehrend gearbeitet wird, aendert es nichts. */
    fun dismissImport() {
        if (_importState.value !is ImportState.Working) _importState.value = ImportState.Idle
    }

    /** Ein Fehler hat immer einen sichtbaren Text: bekannte Gruende haben ihren eigenen, alles andere die Meldung oder den Klassennamen. */
    private fun readFailureText(e: Exception): UiText = when (e) {
        is IllegalArgumentException -> UiText.StringResource(R.string.logbook_import_error_format)
        is FileTooLargeException -> UiText.StringResource(R.string.logbook_import_error_too_large)
        is IOException, is SecurityException -> UiText.StringResource(R.string.logbook_import_error_unreadable)
        else -> UiText.DynamicString(e.message ?: e.javaClass.simpleName)
    }

    /** Die Snackbar "Rueckgaengig" verschwindet mit der Seite, also wird das Loeschen jetzt endgueltig. */
    override fun onCleared() {
        deletion.flush()
    }
}
