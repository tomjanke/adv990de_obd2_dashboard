package de.adv990de.obd2.ui.history

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.di.ApplicationScope
import de.adv990de.obd2.ui.components.UndoableDeletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {

    /**
     * The shared local-delete contract (see [UndoableDeletion]): a trip is hidden at once, deleted for
     * real after the undo window. The key is the session id — one trip for the row button and the
     * swipe, all finished trips for "delete all". Runs on the app-wide scope so a delete that is
     * still inside its window survives this ViewModel (see [onCleared]).
     */
    private val deletion = UndoableDeletion<Long>(scope = appScope) { ids ->
        sessionRepository.deleteSessions(ids)
    }

    private val storedSessions: Flow<PagingData<SessionEntity>> = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false)
    ) { sessionRepository.pagingSource() }
        .flow
        .cachedIn(viewModelScope)

    /** The paged list minus the trips whose delete is still undoable — filtered AFTER `cachedIn`. */
    val sessions: Flow<PagingData<SessionEntity>> =
        combine(storedSessions, deletion.pending) { data, pending ->
            if (pending.isEmpty()) data else data.filter { it.id !in pending }
        }

    /** The trip that is being recorded: it shows a badge instead of the delete button and is never deleted. */
    val activeSessionId: StateFlow<Long?> = sessionRepository.activeSessionId

    /** Finished trips the user still sees — what "delete all" would touch. */
    private val finishedSessionIds: StateFlow<List<Long>> =
        combine(sessionRepository.sessionIds, activeSessionId, deletion.pending) { ids, active, pending ->
            ids.filter { it != active && it !in pending }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Enables the top-bar trash; false while the list is empty, only the recording exists, or everything is pending. */
    val canDeleteAll: StateFlow<Boolean> =
        finishedSessionIds.map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Non-null while a delete can still be taken back — drives the "Rückgängig" snackbar. */
    val undoBatchId: StateFlow<Long?> = deletion.openBatchId

    private val _undoCount = MutableStateFlow(0)

    /** How many trips the undoable batch holds — the snackbar says "1 Fahrt" or "n Fahrten". */
    val undoCount: StateFlow<Int> = _undoCount.asStateFlow()

    private val _deleteAllConfirmationVisible = MutableStateFlow(false)
    val deleteAllConfirmationVisible: StateFlow<Boolean> = _deleteAllConfirmationVisible.asStateFlow()

    private val _shareUri = MutableStateFlow<Uri?>(null)
    val shareUri: StateFlow<Uri?> = _shareUri

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError

    /** Row trash button or swipe: no dialog, the snackbar offers undo. The recording is refused. */
    fun deleteSession(session: SessionEntity) {
        if (session.id == activeSessionId.value) return
        _undoCount.value = 1
        deletion.schedule(setOf(session.id))
    }

    /** Top-bar trash: ask first — but only if there is something to delete. */
    fun requestDeleteAll() {
        if (canDeleteAll.value) _deleteAllConfirmationVisible.value = true
    }

    fun cancelDeleteAll() {
        _deleteAllConfirmationVisible.value = false
    }

    /**
     * Confirmed: hide every FINISHED trip now, delete exactly those after the undo window. The ids are
     * taken at this moment, so a trip that ends inside the window is never swept up. [finishedSessionIds]
     * trails the pending set by one emission, hence the explicit filter: a trip deleted a split second
     * ago must not be counted (or scheduled) again.
     */
    fun confirmDeleteAll() {
        _deleteAllConfirmationVisible.value = false
        val pending = deletion.pending.value
        val ids = finishedSessionIds.value.filter { it !in pending }
        if (ids.isEmpty()) return
        _undoCount.value = ids.size
        deletion.schedule(ids.toSet())
    }

    fun undoDelete() = deletion.undo()

    fun export(sessionId: Long) {
        viewModelScope.launch {
            _exportError.value = null
            try {
                _shareUri.value = sessionRepository.exportToCsv(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _exportError.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun consumeShareUri() {
        _shareUri.value = null
    }

    fun consumeExportError() {
        _exportError.value = null
    }

    /** The undo snackbar goes away with the screen, so an open delete becomes final right now. */
    override fun onCleared() {
        deletion.flush()
    }
}
