package de.adv990de.obd2.ui.tracks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.TrackRepository
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackSummary
import de.adv990de.obd2.track.simplifyTrack
import de.adv990de.obd2.track.trackStats
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

/** So viele Punkte zeichnet die Ansicht hoechstens; ein Track mit mehr wird fuer die Zeichnung ausgeduennt (Kennzahlen und Export rechnen mit allen). */
const val TRACK_DRAW_POINTS = 2_000

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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val trackId: Long? = savedStateHandle.get<Long>(TRACK_ID_ARG)

    private val _state = MutableStateFlow<TrackDetailState>(TrackDetailState.Loading)
    val state: StateFlow<TrackDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
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
