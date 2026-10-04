package de.adv990de.obd2.ui.dtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.DtcHistoryEntry
import de.adv990de.obd2.data.DtcHistoryRepository
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.di.ApplicationScope
import de.adv990de.obd2.obd.ObdErrorMapper
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.ui.UiText
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

data class DtcUiState(
    val dtcs: List<DtcEntry> = emptyList(),
    val isLoading: Boolean = false,
    /** Why the read failed. Always a visible text — a failure without one must not look like "no faults". */
    val error: UiText? = null
)

/** One row of the history view: the recorded code plus whether the vehicle reports it right now. */
data class DtcHistoryItem(val entry: DtcHistoryEntry, val isActive: Boolean)

@HiltViewModel
class DtcViewModel @Inject constructor(
    private val obdRepository: ObdRepository,
    private val dtcHistoryRepository: DtcHistoryRepository,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DtcUiState())

    /** The live list of the last successful read ("Aktuell"). */
    val uiState: StateFlow<DtcUiState> = _uiState

    /** Without a connection nothing can be read — the page then says "Nicht verbunden" instead of a wrong "all clear". */
    val isConnected: StateFlow<Boolean> = obdRepository.isConnectedFlow

    /**
     * The shared local-delete contract (see [UndoableDeletion]): an entry is hidden at once and deleted
     * for real after the undo window. The key is the fault code — one for the row button and the swipe,
     * all visible ones for "delete all". Runs on the app-wide scope so a delete that is still inside its
     * window survives this ViewModel (see [onCleared]). Only the LOCAL record is deleted; the vehicle's
     * fault memory is never touched (the app is read-only towards the vehicle).
     */
    private val deletion = UndoableDeletion<String>(scope = appScope) { codes ->
        dtcHistoryRepository.delete(codes)
    }

    /** Codes of the last successful live read; null until one happened, after a failed read, or without a connection. */
    private val liveCodes = MutableStateFlow<Set<String>?>(null)

    /** The recorded codes the user still sees, newest first; "active" = in the last live read while connected. */
    val history: StateFlow<List<DtcHistoryItem>> =
        combine(dtcHistoryRepository.history, deletion.pending, liveCodes, isConnected) { entries, pending, live, connected ->
            entries.filter { it.code !in pending }
                .map { DtcHistoryItem(it, isActive = connected && live != null && it.code in live) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Non-null while a delete can still be taken back — drives the "Rückgängig" snackbar. */
    val undoBatchId: StateFlow<Long?> = deletion.openBatchId

    private val _undoCount = MutableStateFlow(0)

    /** How many entries the undoable batch holds — the snackbar says "1 Eintrag" or "n Einträge". */
    val undoCount: StateFlow<Int> = _undoCount.asStateFlow()

    private val _deleteAllConfirmationVisible = MutableStateFlow(false)
    val deleteAllConfirmationVisible: StateFlow<Boolean> = _deleteAllConfirmationVisible.asStateFlow()

    /**
     * Reads the fault codes (modes 03 and 07 — read-only) and records them in the local history.
     * Without a connection nothing is read: [ObdRepository.readDtcs] would answer an empty list, which
     * looks like "no faults" — exactly what must not be claimed while the vehicle is not even asked.
     */
    fun refresh() {
        viewModelScope.launch {
            if (!obdRepository.isConnectedFlow.value) {
                _uiState.value = DtcUiState()
                liveCodes.value = null
                return@launch
            }
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val dtcs = obdRepository.readDtcs()
                _uiState.value = DtcUiState(dtcs = dtcs)
                liveCodes.value = dtcs.mapTo(HashSet()) { it.code }
                dtcHistoryRepository.record(dtcs)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.value = DtcUiState(error = ObdErrorMapper.toUiText(e))
                liveCodes.value = null
            }
        }
    }

    /** Row trash button or swipe: no dialog, the snackbar offers undo. */
    fun deleteEntry(code: String) {
        _undoCount.value = 1
        deletion.schedule(setOf(code))
    }

    /** Top-bar trash: ask first — but only if there is something to delete. */
    fun requestDeleteAll() {
        if (history.value.isNotEmpty()) _deleteAllConfirmationVisible.value = true
    }

    fun cancelDeleteAll() {
        _deleteAllConfirmationVisible.value = false
    }

    /**
     * Confirmed: hide every entry the user sees now, delete exactly those after the undo window. The
     * codes are taken at this moment, so a code recorded inside the window is not swept up — it was never
     * part of what the user confirmed. ([history] already leaves out the pending ones.)
     */
    fun confirmDeleteAll() {
        _deleteAllConfirmationVisible.value = false
        val pending = deletion.pending.value
        val codes = history.value.map { it.entry.code }.filter { it !in pending }
        if (codes.isEmpty()) return
        _undoCount.value = codes.size
        deletion.schedule(codes.toSet())
    }

    fun undoDelete() = deletion.undo()

    /** The undo snackbar goes away with the screen, so an open delete becomes final right now. */
    override fun onCleared() {
        deletion.flush()
    }
}
