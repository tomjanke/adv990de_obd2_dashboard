package de.adv990de.obd2.ui.tracks

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.TrackExporter
import de.adv990de.obd2.data.TrackRepository
import de.adv990de.obd2.data.trackFileName
import de.adv990de.obd2.track.TRACK_DRAW_POINTS
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackSummary
import de.adv990de.obd2.track.simplifyTrack
import de.adv990de.obd2.track.trackStats
import de.adv990de.obd2.ui.components.ExportFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Der Name des Navigationsarguments mit der Id des Tracks (s. `Screen.TrackDetail`). */
const val TRACK_ID_ARG = "id"


sealed interface TrackDetailState {
    data object Loading : TrackDetailState

    /** Es gibt keinen Track mit dieser Id (geloescht, oder ein ungueltiges Argument). */
    data object NotFound : TrackDetailState

    /** Die Punkte liessen sich nicht lesen. */
    data object Failed : TrackDetailState

    /**
     * [points] sind die Punkte der **Zeichnung** (hoechstens [TRACK_DRAW_POINTS]); [bounds] und die Zahlen der [summary] kommen aus allen Punkten. [bounds] ist `null` bei einem Track ohne Punkt.
     * Ein Schnappschuss: eine laufende Aufzeichnung waechst danach weiter, die Ansicht nicht.
     */
    data class Ready(val summary: TrackSummary, val points: List<TrackPoint>, val bounds: TrackBounds?) : TrackDetailState
}

@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val exporter: TrackExporter,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val trackId: Long? = savedStateHandle.get<Long>(TRACK_ID_ARG)

    private val _state = MutableStateFlow<TrackDetailState>(TrackDetailState.Loading)
    val state: StateFlow<TrackDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    // ── GPX-Export (Task 7) ───────────────────────────────────────────────────

    /** Die Rueckmeldung des Exports (Teilen-Menue, Fehler, „nichts zu exportieren“); die Seite zeigt sie ueber `ExportFeedbackEffects`. */
    val exportFeedback = ExportFeedback()

    private val _saved = MutableStateFlow(false)

    /** Wahr, nachdem „Speichern“ die Datei geschrieben hat; die Seite zeigt es einmal und ruft [consumeSaved]. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _exporting = MutableStateFlow(false)

    /** Wahr, solange ein Export laeuft: die Knoepfe sind dann aus, ein zweiter Tipp tut nichts (er schriebe dieselbe Datei noch einmal). */
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    fun consumeSaved() {
        _saved.value = false
    }

    /** Der Vorschlag fuer den Dateidialog: `track_<Name>_<UTC-Zeit>.gpx`; ohne geladenen Track der Ersatzname. */
    fun suggestedFileName(): String =
        trackFileName((_state.value as? TrackDetailState.Ready)?.summary?.name.orEmpty(), System.currentTimeMillis())

    /** „Teilen“: schreibt die GPX-Datei und gibt sie ueber [exportFeedback] dem Teilen-Menue. */
    fun share() = exportOnce { id -> exportFeedback.launchShare(viewModelScope) { exporter.exportToGpx(id) }.join() }

    /** „Speichern“ in die Datei [target], die der Nutzer mit dem Dateidialog gewaehlt hat. */
    fun saveTo(target: Uri) = exportOnce { id ->
        exportFeedback.launchSave(viewModelScope, onSaved = { _saved.value = true }) { exporter.saveGpx(target, id) }.join()
    }

    /**
     * Fuehrt einen Export hoechstens einmal zugleich aus und nur fuer einen geladenen Track. Ein Track ohne Punkt (laut Kennzahlen) meldet „nichts zu exportieren“, statt eine leere
     * Datei zu schreiben.
     */
    private fun exportOnce(block: suspend (Long) -> Unit) {
        val ready = _state.value as? TrackDetailState.Ready ?: return
        if (_exporting.value) return
        if (ready.summary.pointCount == 0) {
            exportFeedback.reportNothingToExport()
            return
        }
        _exporting.value = true
        viewModelScope.launch {
            try {
                block(ready.summary.id)
            } finally {
                _exporting.value = false
            }
        }
    }

    private suspend fun load() {
        val id = trackId
        if (id == null) {
            _state.value = TrackDetailState.NotFound
            return
        }
        try {
            val summary = repository.tracks.first().firstOrNull { it.id == id }
            if (summary == null) {
                _state.value = TrackDetailState.NotFound
                return
            }
            val all = repository.points(id)
            val (bounds, drawn) = withContext(Dispatchers.Default) { trackStats(all).bounds to simplifyTrack(all, TRACK_DRAW_POINTS) }
            _state.value = TrackDetailState.Ready(summary, drawn, bounds)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.value = TrackDetailState.Failed
        }
    }
}
