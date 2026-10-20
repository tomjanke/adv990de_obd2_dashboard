package de.adv990de.obd2.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppLogExporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.appLogExportFileName
import de.adv990de.obd2.data.db.entity.AppLogEntity
import de.adv990de.obd2.di.ApplicationScope
import de.adv990de.obd2.ui.components.UndoableDeletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppLogsViewModel @Inject constructor(
    private val appLogRepository: AppLogRepository,
    private val appLogExporter: AppLogExporter,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {

    /**
     * "Delete all" with undo. The key is the HIGHEST log id at press time: [AppLogRepository.deleteUpTo]
     * then removes everything up to it — also rows beyond the 1000 the list shows — but not the
     * lines written while the undo window is open. Runs on the app-wide scope so a delete that is
     * still inside its window survives this ViewModel (see [onCleared]).
     */
    private val deletion = UndoableDeletion<Long>(scope = appScope) { maxIds ->
        appLogRepository.deleteUpTo(maxIds.max())
    }

    /** What the user still sees: everything newer than the highest pending delete-all. */
    val logs: StateFlow<List<AppLogEntity>> =
        combine(appLogRepository.logs, deletion.pending) { all, pendingMaxIds ->
            val hideUpTo = pendingMaxIds.maxOrNull()
            if (hideUpTo == null) all else all.filter { it.id > hideUpTo }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Non-null while a delete can still be taken back — drives the "Rückgängig" snackbar. */
    val undoBatchId: StateFlow<Long?> = deletion.openBatchId

    private val _clearConfirmationVisible = MutableStateFlow(false)
    val clearConfirmationVisible: StateFlow<Boolean> = _clearConfirmationVisible.asStateFlow()

    /** Trash icon: ask first — but only if there is something to delete. */
    fun requestClear() {
        if (logs.value.isNotEmpty()) _clearConfirmationVisible.value = true
    }

    fun cancelClear() {
        _clearConfirmationVisible.value = false
    }

    /** Confirmed: hide everything now, delete for real after the undo window. */
    fun confirmClear() {
        _clearConfirmationVisible.value = false
        val maxId = logs.value.maxOfOrNull { it.id } ?: return
        deletion.schedule(setOf(maxId))
    }

    fun undoClear() = deletion.undo()

    // ── Export (Teilen oder Speichern), analog zu "Alle Fahrten exportieren" im Verlauf ──

    private val _exportDialogVisible = MutableStateFlow(false)

    /** The dialog "Log exportieren" (share or save); lives here so a rotation keeps it open. */
    val exportDialogVisible: StateFlow<Boolean> = _exportDialogVisible.asStateFlow()

    private val _shareUri = MutableStateFlow<Uri?>(null)

    /** Non-null once "Teilen" wrote the file: the screen hands it to the share chooser and calls [consumeShareUri]. */
    val shareUri: StateFlow<Uri?> = _shareUri.asStateFlow()

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    private val _saved = MutableStateFlow(false)

    /** True after the log was written into the file the user chose (the snackbar says so). */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _nothingToExport = MutableStateFlow(false)

    /** True when an export was asked for but no line is visible any more (all hidden by a delete-all that can still be undone). */
    val nothingToExport: StateFlow<Boolean> = _nothingToExport.asStateFlow()

    /** Top-bar export: ask how (share or save) — but only if there is something to export. */
    fun requestExport() {
        if (logs.value.isNotEmpty()) _exportDialogVisible.value = true
    }

    fun cancelExport() {
        _exportDialogVisible.value = false
    }

    /**
     * The id after which lines belong into the file: the highest id of a delete-all that can still be undone, taken at this moment — the lines
     * the user no longer sees stay out of the file, a line written after the delete stays in. `0` is the whole log.
     */
    private fun exportAfterId(): Long = deletion.pending.value.maxOrNull() ?: 0L

    /** "Teilen": the visible log in ONE CSV file, handed to the share chooser. */
    fun shareExport() {
        _exportDialogVisible.value = false
        if (logs.value.isEmpty()) {
            _nothingToExport.value = true
            return
        }
        val afterId = exportAfterId()
        viewModelScope.launch {
            _exportError.value = null
            try {
                _shareUri.value = appLogExporter.exportToCsv(afterId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _exportError.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** "Speichern": the same CSV, written into the file the user chose with the system dialog ([target]). */
    fun saveExportTo(target: Uri) {
        if (logs.value.isEmpty()) {
            _nothingToExport.value = true
            return
        }
        val afterId = exportAfterId()
        viewModelScope.launch {
            _exportError.value = null
            try {
                appLogExporter.saveCsv(target, afterId)
                _saved.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _exportError.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** The name the system file dialog proposes: `applog_<UTC time>.csv`. */
    fun exportFileName(): String = appLogExportFileName(System.currentTimeMillis())

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

    /** The undo snackbar goes away with the screen, so the delete becomes final right now. */
    override fun onCleared() {
        deletion.flush()
    }
}
