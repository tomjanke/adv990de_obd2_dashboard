package de.adv990de.obd2.ui.tracks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.TrackRepository
import de.adv990de.obd2.location.LocationSpeedProvider
import de.adv990de.obd2.location.positionUpdates
import de.adv990de.obd2.location.toPoint
import de.adv990de.obd2.track.FollowDirection
import de.adv990de.obd2.track.FollowLine
import de.adv990de.obd2.track.FollowResult
import de.adv990de.obd2.track.FollowTracker
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.simplifyTrack
import de.adv990de.obd2.track.trackStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Abstand der Positionen beim Nachfahren (eine je Sekunde, wie die Aufzeichnung). */
const val FOLLOW_MIN_INTERVAL_MS = 1_000L

/** Ein Fix, dessen Genauigkeit schlechter ist, wird ignoriert (er wuerde die Marke springen lassen); ohne Angabe zaehlt er. */
const val FOLLOW_MAX_ACCURACY_M = 100f

/** Der Schluessel der gewaehlten Richtung im `SavedStateHandle` (uebersteht eine Drehung und das Beenden des Prozesses). */
internal const val FOLLOW_REVERSE_KEY = "follow_reverse"

sealed interface FollowTrackState {
    data object Loading : FollowTrackState

    /** Es gibt keinen Track mit dieser Id (geloescht, oder ein ungueltiges Argument). */
    data object NotFound : FollowTrackState

    /** Die Punkte liessen sich nicht lesen. */
    data object Failed : FollowTrackState

    /** Der Track hat keine zwei verschiedenen Punkte: ihm laesst sich nicht folgen. */
    data class TooShort(val name: String) : FollowTrackState

    /**
     * [points] sind **alle** Punkte (die Linie, der gefolgt wird), [drawn] die ausgeduennten der Zeichnung (hoechstens [TRACK_DRAW_POINTS]), [bounds] das Rechteck aller Punkte.
     * Ein Schnappschuss: eine laufende Aufzeichnung waechst danach weiter, das Nachfahren nicht.
     */
    data class Ready(val name: String, val points: List<TrackPoint>, val drawn: List<TrackPoint>, val bounds: TrackBounds) : FollowTrackState
}

/** Was die Seite ueber die eigene Position weiss. */
sealed interface FollowReading {
    /** Die Standortberechtigung fehlt: die Seite bietet sie an. */
    data object NoPermission : FollowReading

    /** Es gibt noch keine Position (kein Fix, Anbieter abgeschaltet oder nicht erreichbar, Track nicht geladen). */
    data object Searching : FollowReading

    data class Following(val position: TrackPoint, val result: FollowResult) : FollowReading
}

/**
 * Das Nachfahren eines Tracks: die eigene Position (ein eigener Listener, **nur solange die Seite sichtbar ist**, `WhileSubscribed`) gegen die Linie des Tracks, in der gewaehlten Richtung.
 * Es gibt **keine Routenfuehrung**: das ViewModel zeigt Abstand, Strecke, Rest und Richtung, mehr nicht. Eine Positionsangabe steht nirgends im Log.
 */
@HiltViewModel
class TrackFollowViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val location: LocationSpeedProvider,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val trackId: Long? = savedStateHandle.get<Long>(TRACK_ID_ARG)

    private val _track = MutableStateFlow<FollowTrackState>(FollowTrackState.Loading)
    val track: StateFlow<FollowTrackState> = _track.asStateFlow()

    private val _direction = MutableStateFlow(if (savedStateHandle.get<Boolean>(FOLLOW_REVERSE_KEY) == true) FollowDirection.REVERSE else FollowDirection.FORWARD)
    val direction: StateFlow<FollowDirection> = _direction.asStateFlow()

    private val _permission = MutableStateFlow(location.hasPermission())

    init {
        viewModelScope.launch { load() }
    }

    fun setDirection(direction: FollowDirection) {
        _direction.value = direction
        savedStateHandle[FOLLOW_REVERSE_KEY] = direction == FollowDirection.REVERSE
    }

    /** Die Seite ruft es, wenn sich die Berechtigung geaendert haben kann (nach dem Dialog, beim Zurueckkehren aus den Einstellungen). */
    fun refreshPermission() {
        _permission.value = location.hasPermission()
    }

    /**
     * Die eigene Position auf dem Track. Der Fluss laeuft nur, solange jemand sammelt (fuenf Sekunden Nachlauf fuer eine Drehung), und beginnt danach wieder mit [FollowReading.Searching].
     * Ein Wechsel der Richtung, des Tracks oder der Berechtigung beginnt einen neuen Verfolger.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val reading: StateFlow<FollowReading> = combine(_track, _direction, _permission) { t, d, granted -> Triple(t, d, granted) }
        .flatMapLatest { (t, d, granted) -> readingFlow(t, d, granted) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), FollowReading.Searching)

    private fun readingFlow(track: FollowTrackState, direction: FollowDirection, granted: Boolean): Flow<FollowReading> {
        val ready = track as? FollowTrackState.Ready ?: return flowOf(FollowReading.Searching)
        if (!granted) return flowOf(FollowReading.NoPermission)
        return flow {
            val tracker = FollowTracker(FollowLine.of(ready.points, direction))
            emit(FollowReading.Searching)
            location.positionUpdates(FOLLOW_MIN_INTERVAL_MS).collect { fix ->
                if (fix == null) {
                    emit(FollowReading.Searching)
                    return@collect
                }
                val accuracy = fix.accuracyM
                if (accuracy != null && accuracy > FOLLOW_MAX_ACCURACY_M) return@collect
                val point = fix.toPoint()
                val result = tracker.update(point) ?: return@collect
                emit(FollowReading.Following(point, result))
            }
        }
    }

    private suspend fun load() {
        val id = trackId
        if (id == null) {
            _track.value = FollowTrackState.NotFound
            return
        }
        try {
            val summary = repository.tracks.first().firstOrNull { it.id == id }
            if (summary == null) {
                _track.value = FollowTrackState.NotFound
                return
            }
            val all = repository.points(id)
            if (!FollowLine.of(all, FollowDirection.FORWARD).canFollow) {
                _track.value = FollowTrackState.TooShort(summary.name)
                return
            }
            val (bounds, drawn) = withContext(Dispatchers.Default) { trackStats(all).bounds to simplifyTrack(all, TRACK_DRAW_POINTS) }
            _track.value = FollowTrackState.Ready(summary.name, all, drawn, bounds!!)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _track.value = FollowTrackState.Failed
        }
    }
}
