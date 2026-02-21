package de.adv990de.obd2.ui.history

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.data.db.entity.SessionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    val sessions: Flow<PagingData<SessionEntity>> = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false)
    ) { sessionRepository.pagingSource() }
        .flow
        .cachedIn(viewModelScope)

    private val _shareUri = MutableStateFlow<Uri?>(null)
    val shareUri: StateFlow<Uri?> = _shareUri

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError

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
}
