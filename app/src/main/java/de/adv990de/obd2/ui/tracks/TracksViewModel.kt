package de.adv990de.obd2.ui.tracks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.TrackRepository
import de.adv990de.obd2.di.ApplicationScope
import de.adv990de.obd2.track.RecordingState
import de.adv990de.obd2.track.StartResult
import de.adv990de.obd2.track.TrackRecorder
import de.adv990de.obd2.track.TrackSummary
import de.adv990de.obd2.track.normalizeTrackName
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

/**
 * Was die Liste zeigt: die Tracks ([tracks] ist `null`, solange die Datenbank noch nichts gemeldet hat — "leer" waere eine Aussage) ohne die laufende Aufzeichnung, und der Zustand der
 * Aufzeichnung selbst ([recording] traegt Punkte und Strecke live; die Zeile der Liste zu ihr gibt es nicht, sie steht oben als eigene Karte).
 */
data class TracksUiState(val tracks: List<TrackSummary>?, val recording: RecordingState)

/** Eine Rueckmeldung, die die Seite einmal zeigt (Snackbar). Der Text steht in der Oberflaeche, nicht hier. */
enum class TracksMessage { NoPermission, NoLocationService, StartFailed, StopFailed, RenameFailed }

@HiltViewModel
class TracksViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val recorder: TrackRecorder,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    /** Loeschen mit Rueckgaengig; laeuft im app-weiten Scope, damit ein Loeschen im Fenster das ViewModel ueberlebt (s. [onCleared]). */
    private val deletion = UndoableDeletion<Long>(scope = appScope) { ids -> repository.delete(ids) }

    val uiState: StateFlow<TracksUiState> =
        combine(repository.tracks, deletion.pending, recorder.state) { all, pending, recording ->
            TracksUiState(tracks = all.filter { !it.running && it.id !in pending }, recording = recording)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TracksUiState(null, recorder.state.value))

    /** Nur der Zustand der Aufzeichnung — fuer das Dashboard, das die Liste der Tracks nicht braucht (und sie so nicht beobachtet). */
    val recording: StateFlow<RecordingState> = recorder.state

    private val _message = MutableStateFlow<TracksMessage?>(null)
    val message: StateFlow<TracksMessage?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** Die Id des Loeschens, das noch zurueckgenommen werden kann (fuer die Snackbar), sonst `null`. */
    val undoBatchId: StateFlow<Long?> = deletion.openBatchId

    /** Blendet den Track aus und loescht ihn nach dem Rueckgaengig-Fenster. **Die laufende Aufzeichnung nie.** */
    fun delete(id: Long) {
        if (id == repository.activeTrackId) return
        deletion.schedule(setOf(id))
    }

    fun undoDelete() = deletion.undo()

    /** Benennt um; ein leerer Name wird nicht gespeichert (ein Track braucht einen Namen). */
    fun rename(id: Long, rawName: String) {
        val name = normalizeTrackName(rawName) ?: return
        viewModelScope.launch {
            try {
                repository.rename(id, name)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = TracksMessage.RenameFailed
            }
        }
    }

    /** Beginnt eine Aufzeichnung ([name] `null` = der Recorder benennt sie nach dem Beginn). Laeuft im app-weiten Scope: ein Verlassen der Seite bricht ein Anlegen nicht in der Mitte ab. */
    fun startRecording(name: String?) {
        appScope.launch {
            try {
                when (recorder.start(name)) {
                    StartResult.Started, StartResult.AlreadyRunning -> Unit
                    StartResult.NoPermission -> _message.value = TracksMessage.NoPermission
                    StartResult.NoLocationService -> _message.value = TracksMessage.NoLocationService
                    StartResult.Failed -> _message.value = TracksMessage.StartFailed
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = TracksMessage.StartFailed
            }
        }
    }

    /** Beendet die Aufzeichnung (der Recorder schreibt den Rest und schliesst die Spur ab; das laeuft auch bei abgebrochenem Aufrufer zu Ende). */
    fun stopRecording() {
        appScope.launch {
            try {
                recorder.stop()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = TracksMessage.StopFailed
            }
        }
    }

    /** Verlaesst der Nutzer die Seite, ist die Rueckgaengig-Anzeige weg: ein offenes Loeschen gilt sofort. */
    override fun onCleared() {
        deletion.flush()
    }
}
