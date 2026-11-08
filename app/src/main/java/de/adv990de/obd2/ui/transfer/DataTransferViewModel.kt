package de.adv990de.obd2.ui.transfer

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.ExportCounts
import de.adv990de.obd2.data.ExportRequest
import de.adv990de.obd2.data.FileTooLargeException
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.TransferExporter
import de.adv990de.obd2.data.TransferImporter
import de.adv990de.obd2.obd.compactForLog
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.ExportFormat
import de.adv990de.obd2.transfer.NotAPackageException
import de.adv990de.obd2.transfer.PackageFormat
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.TransferFileException
import de.adv990de.obd2.transfer.TransferPreview
import de.adv990de.obd2.transfer.PartOutcome
import de.adv990de.obd2.transfer.TransferResult
import de.adv990de.obd2.transfer.csvPartOf
import de.adv990de.obd2.transfer.effectiveFormat
import de.adv990de.obd2.transfer.hasNews
import de.adv990de.obd2.tripimport.TripFileException
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.components.ExportFeedback
import kotlinx.coroutines.CancellationException
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

/** Die Namen der Navigationsargumente der Seite (`Screen.DataTransfer`); das ViewModel liest sie aus dem [SavedStateHandle]. */
const val TRANSFER_MODE_ARG = "mode"
const val TRANSFER_PART_ARG = "part"
const val TRANSFER_PICK_ARG = "pick"

enum class TransferMode { EXPORT, IMPORT }

/** Die zwei Schritte des Imports: erst die Vorschau (nichts geschrieben), erst „Importieren“ schreibt. */
sealed interface TransferImportState {
    data object Idle : TransferImportState
    data object Working : TransferImportState
    data class Preview(val preview: TransferPreview, val selected: Set<DataPart>) : TransferImportState
    data class Done(val result: TransferResult) : TransferImportState
    /** [fileName]: der Name der gewaehlten Datei, soweit das System ihn nennt (sonst `null`); die Meldung zeigt ihn an, damit eine falsch gewaehlte Datei auffaellt. */
    data class Failed(val text: UiText, val fileName: String? = null) : TransferImportState
}

/**
 * Der Zustand der Seite „Daten sichern und wiederherstellen“. Die Auswahl, das Format und die Mengen sind reine Zustaende; Export und Import laufen ueber [TransferExporter] und
 * [TransferImporter]. Die Mengen werden beim Anlegen und nach jedem Import gelesen und vor jedem Export frisch (P6, nicht live); ein Teil ohne Eintraege ist nicht waehlbar und faellt aus der
 * Auswahl, sobald die Mengen da sind. Der Start kommt aus den Navigationsargumenten: eine Abkuerzung (`part`) waehlt genau diese Art mit CSV als Wunschformat (P5), die Seite aus den
 * Einstellungen die Vorauswahl [DataPart.DEFAULT_EXPORT] als Paket.
 */
@HiltViewModel
class DataTransferViewModel @Inject constructor(
    private val exporter: TransferExporter,
    private val importer: TransferImporter,
    private val appLog: AppLogRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    /** Naehte fuer Tests: der Tag und die Uhr, die der Import fuer die Pruefung der Daten braucht. */
    internal var today: () -> LocalDate = { LocalDate.now() }
    internal var nowMs: () -> Long = System::currentTimeMillis

    private val startPart: DataPart? = savedState.get<String>(TRANSFER_PART_ARG)
        ?.let { name -> DataPart.entries.firstOrNull { it.name == name } }
        ?.takeIf { it in DataPart.AVAILABLE }

    private val _mode = MutableStateFlow(if (savedState.get<String>(TRANSFER_MODE_ARG) == "import") TransferMode.IMPORT else TransferMode.EXPORT)
    val mode: StateFlow<TransferMode> = _mode.asStateFlow()

    fun setMode(mode: TransferMode) {
        _mode.value = mode
    }

    private val _counts = MutableStateFlow<ExportCounts?>(null)

    /** `null`, bis die erste Abfrage da ist. */
    val counts: StateFlow<ExportCounts?> = _counts.asStateFlow()

    private val _selection = MutableStateFlow(startPart?.let { setOf(it) } ?: DataPart.DEFAULT_EXPORT)
    val selection: StateFlow<Set<DataPart>> = _selection.asStateFlow()

    private val _wantedFormat = MutableStateFlow(if (startPart != null) ExportFormat.CSV else ExportFormat.PACKAGE)

    /** Was der Nutzer will; [format] ist, was daraus wird (CSV nur bei genau einer Art). */
    val wantedFormat: StateFlow<ExportFormat> = _wantedFormat.asStateFlow()

    val format: StateFlow<ExportFormat> = combine(_selection, _wantedFormat) { parts, wanted -> effectiveFormat(parts, wanted) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, effectiveFormat(_selection.value, _wantedFormat.value))

    val csvAllowed: StateFlow<Boolean> = _selection.map { csvPartOf(it) != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, csvPartOf(_selection.value) != null)

    /** Es gibt etwas zu exportieren: die Auswahl hat mindestens einen Teil, der nicht leer ist. */
    val canExport: StateFlow<Boolean> = combine(_selection, _counts) { parts, counts -> parts.any { counts?.isEmpty(it) != true } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _selection.value.isNotEmpty())

    val exportFeedback = ExportFeedback()

    private val _saved = MutableStateFlow(false)

    /** Wahr, nachdem „Speichern“ die Datei geschrieben hat; die Seite zeigt es einmal und ruft [consumeSaved]. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _importState = MutableStateFlow<TransferImportState>(TransferImportState.Idle)
    val importState: StateFlow<TransferImportState> = _importState.asStateFlow()

    init {
        viewModelScope.launch { refreshCounts() }
    }

    /** Liest die Mengen neu; ein Lesefehler laesst den letzten Stand stehen (die Seite bleibt benutzbar). Teile ohne Eintraege fallen aus der Auswahl. */
    private suspend fun refreshCounts() {
        val fresh = try {
            exporter.counts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        _counts.value = fresh
        _selection.value = _selection.value.filterTo(linkedSetOf()) { !fresh.isEmpty(it) }
    }

    fun toggle(part: DataPart) {
        if (part !in DataPart.AVAILABLE || _counts.value?.isEmpty(part) == true) return
        val current = _selection.value
        _selection.value = if (part in current) current - part else current + part
    }

    fun setFormat(format: ExportFormat) {
        _wantedFormat.value = format
    }

    fun consumeSaved() {
        _saved.value = false
    }

    /** Die Anfrage mit frisch gelesenen Mengen ohne leere Teile, oder `null` (und die Meldung „nichts zu exportieren“), wenn nichts uebrig bleibt. */
    private suspend fun freshRequest(): ExportRequest? {
        refreshCounts()
        val parts = _selection.value.filterTo(linkedSetOf()) { _counts.value?.isEmpty(it) != true }
        if (parts.isEmpty()) {
            exportFeedback.reportNothingToExport()
            return null
        }
        return ExportRequest(parts, effectiveFormat(parts, _wantedFormat.value))
    }

    private val _exporting = MutableStateFlow(false)

    /** Wahr, solange ein Export laeuft (Mengen lesen, Datei schreiben); die Seite sperrt „Teilen“ und „Speichern“ so lange, ein zweiter Aufruf tut nichts. */
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /**
     * Fuehrt [block] als den einen laufenden Export aus: ein Aufruf, waehrend einer laeuft, tut nichts (ein Doppeltipp legte sonst zwei Dateien an und oeffnete das Teilen-Menue
     * zweimal). Das Flag wird vor dem Start gesetzt (der Aufruf kommt auf dem Main-Thread) und in jedem Fall wieder frei: Ende, Fehler, Abbruch, nichts zu exportieren.
     */
    private fun exportOnce(block: suspend () -> Unit) {
        if (_exporting.value) return
        _exporting.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _exporting.value = false
            }
        }
    }

    fun share() = exportOnce {
        val request = freshRequest() ?: return@exportOnce
        exportFeedback.launchShare(viewModelScope) { exporter.share(request) }.join()
    }

    fun saveTo(target: Uri) = exportOnce {
        val request = freshRequest() ?: return@exportOnce
        exportFeedback.launchSave(viewModelScope, onSaved = { _saved.value = true }) { exporter.save(target, request) }.join()
    }

    /** Der Name, den der Dateidialog vorschlaegt, passend zur aktuellen Wahl. */
    fun suggestedFileName(): String {
        val parts = _selection.value
        if (parts.isEmpty()) return PackageFormat.fileName(nowMs())
        return exporter.suggestedFileName(ExportRequest(parts, effectiveFormat(parts, _wantedFormat.value)))
    }

    // ── Import ──

    /** Der Name der zuletzt gewaehlten Datei (fuer die Fehlermeldung und das App-Log); `null`, wenn das System keinen nennt. */
    private var importFileName: String? = null

    /** Die gewaehlte Datei erkennen und lesen (nichts wird geschrieben); waehrend gearbeitet wird, zaehlt ein zweiter Aufruf nicht. */
    fun pickImport(source: Uri) {
        if (_importState.value is TransferImportState.Working) return
        _importState.value = TransferImportState.Working
        importFileName = null
        viewModelScope.launch {
            try {
                importFileName = importer.displayName(source)
                val preview = importer.inspect(source, nowMs(), today())
                val withNews = preview.parts.filterValues { it.hasNews }.keys
                _importState.value = TransferImportState.Preview(preview, withNews.toSet())
            } catch (e: CancellationException) {
                _importState.value = TransferImportState.Idle
                throw e
            } catch (e: Exception) {
                fail(e, "Import check failed")
            }
        }
    }

    /** Schaltet einen Teil der Vorschau an oder aus; ein Teil, den die Vorschau nicht enthaelt oder nicht lesen konnte, bleibt unberuehrt. */
    fun toggleImportPart(part: DataPart) {
        val state = _importState.value as? TransferImportState.Preview ?: return
        val plan = state.preview.parts[part] ?: return
        if (plan is PartPlan.Unreadable) return
        val selected = if (part in state.selected) state.selected - part else state.selected + part
        _importState.value = state.copy(selected = selected)
    }

    /** Bestaetigt die Vorschau und schreibt die gewaehlten Teile. Ohne Vorschau, mit leerer Auswahl oder waehrend gearbeitet wird, passiert nichts. */
    fun confirmImport() {
        val state = _importState.value as? TransferImportState.Preview ?: return
        if (state.selected.isEmpty()) return
        _importState.value = TransferImportState.Working
        viewModelScope.launch {
            try {
                val result = importer.apply(state.preview, state.selected)
                logFailedParts(result)
                _importState.value = TransferImportState.Done(result)
                refreshCounts()
            } catch (e: CancellationException) {
                _importState.value = TransferImportState.Idle
                throw e
            } catch (e: Exception) {
                fail(e, "Import failed")
            }
        }
    }

    /**
     * Ein Fehler beim Pruefen oder Schreiben: die Oberflaeche zeigt den Text samt Dateiname, das App-Log bekommt die Ursache (eine WARN-Zeile, [importFailureLogLine]). Der Zustand
     * wird zuerst gesetzt, das Schreiben ins Log kann warten. Ohne diese Zeile bliebe ein Fehler beim Pruefen der Datei am fertigen Release-Build unsichtbar.
     */
    private suspend fun fail(e: Exception, what: String) {
        _importState.value = TransferImportState.Failed(importFailureText(e), importFileName)
        appLog.log(LogLevel.WARN, importFailureLogLine(what, importFileName, e))
    }

    /** Die Oberflaeche nennt nur einen festen Satz; die Ursache eines gescheiterten Teils steht als eine WARN-Zeile je Teil im App-Log (Klassenname und Meldung, nichts sonst). */
    private suspend fun logFailedParts(result: TransferResult) {
        for ((part, outcome) in result.outcomes) {
            if (outcome !is PartOutcome.Failed) continue
            val cause = outcome.cause
            val detail = cause.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            appLog.log(LogLevel.WARN, "Import of part ${part.name} failed: ${cause.javaClass.simpleName}$detail")
        }
    }

    /** Schliesst Vorschau, Ergebnis oder Fehler und gibt die Plaene frei; waehrend gearbeitet wird, aendert es nichts. */
    fun dismissImport() {
        if (_importState.value !is TransferImportState.Working) _importState.value = TransferImportState.Idle
    }
}

/** Ein Fehler hat immer einen sichtbaren Text: bekannte Gruende haben ihren eigenen, alles andere die Meldung oder den Klassennamen. */
internal fun importFailureText(e: Exception): UiText = when (e) {
    is TransferFileException -> UiText.StringResource(
        when (e.kind) {
            TransferFileException.Kind.NOT_A_BACKUP -> R.string.transfer_error_not_a_backup
            TransferFileException.Kind.APP_LOG_NOT_IMPORTABLE -> R.string.transfer_error_applog_csv
            TransferFileException.Kind.UNRECOGNIZED -> R.string.transfer_error_unrecognized
        },
    )
    is NotAPackageException -> UiText.StringResource(R.string.transfer_error_not_a_backup)
    // Vor IllegalArgumentException: ein Fahrten-Export mit zu vielen Zeilen oder zerrissenen Fahrten ist ein Export, nur nicht lesbar; die Datei ohne Pflichtspalten bleibt "nicht erkannt".
    is TripFileException -> UiText.StringResource(
        when (e.kind) {
            TripFileException.Kind.NOT_AN_EXPORT -> R.string.transfer_error_unrecognized
            TripFileException.Kind.TOO_MANY_ROWS, TripFileException.Kind.SPLIT_TRIP -> R.string.transfer_error_trips_invalid
        },
    )
    is FileTooLargeException -> UiText.StringResource(R.string.transfer_error_too_large)
    // Jede uebrige IllegalArgumentException (ein kaputtes Anfuehrungszeichen, zu viele Zeilen im Logbuch, ein `require` im Code) ist "nicht lesbar", nicht "nicht erkannt": wer sagt, die Datei
    // sei unbekannt, obwohl der Fehler im Programm liegt, schickt den Nutzer auf die falsche Spur. Die Ursache steht im App-Log.
    is IllegalArgumentException -> UiText.StringResource(R.string.transfer_error_unreadable)
    is IOException, is SecurityException -> UiText.StringResource(R.string.transfer_error_unreadable)
    else -> UiText.DynamicString(e.message ?: e.javaClass.simpleName)
}

/** Die Log-Zeile eines gescheiterten Imports, eine Zeile: `Import check failed for 'name.zip': IllegalArgumentException: Meldung` (ohne Dateiname ohne den Teil `for '…'`; Name und Meldung gekuerzt). */
internal fun importFailureLogLine(what: String, fileName: String?, e: Exception): String {
    val file = fileName?.takeIf { it.isNotBlank() }?.let { " for '${it.compactForLog(MAX_LOGGED_FILE_NAME_CHARS)}'" }.orEmpty()
    val detail = e.message?.takeIf { it.isNotBlank() }?.let { ": ${it.compactForLog(MAX_LOGGED_MESSAGE_CHARS)}" }.orEmpty()
    return "$what$file: ${e.javaClass.simpleName}$detail"
}

private const val MAX_LOGGED_FILE_NAME_CHARS = 80
private const val MAX_LOGGED_MESSAGE_CHARS = 200
