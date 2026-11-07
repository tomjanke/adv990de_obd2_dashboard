package de.adv990de.obd2.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.db.entity.AppLogEntity
import de.adv990de.obd2.di.ApplicationScope
import de.adv990de.obd2.ui.components.UndoableDeletion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppLogsViewModel @Inject constructor(
    private val appLogRepository: AppLogRepository,
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

    /** The undo snackbar goes away with the screen, so the delete becomes final right now. */
    override fun onCleared() {
        deletion.flush()
    }
}
