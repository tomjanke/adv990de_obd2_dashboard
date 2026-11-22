package de.adv990de.obd2.ui.tracks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.track.FollowDirection
import de.adv990de.obd2.track.FollowReading
import de.adv990de.obd2.track.FollowSession
import de.adv990de.obd2.track.FollowTrackState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Die Kartenseite des Nachfahrens: eine **duenne Huelle** um die [FollowSession] (sie lebt app-weit und ueberlebt das Verlassen der Seite). Das ViewModel beginnt die Sitzung fuer den Track
 * der Route — laeuft sie schon fuer genau diesen, bleibt sie, wie sie ist — und reicht Track, Richtung und Position durch. Es gibt **keine Routenfuehrung**; eine Positionsangabe steht nirgends im Log.
 */
@HiltViewModel
class TrackFollowViewModel @Inject constructor(
    private val session: FollowSession,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val trackId: Long? = savedStateHandle.get<Long>(TRACK_ID_ARG)

    init {
        trackId?.let(session::start)
    }

    /** Der Track der Sitzung; ohne gueltige Id der Route gibt es keinen ([FollowTrackState.NotFound]), vor dem Laden [FollowTrackState.Loading]. */
    val track: StateFlow<FollowTrackState> = session.track
        .map { it ?: if (trackId == null) FollowTrackState.NotFound else FollowTrackState.Loading }
        .stateIn(viewModelScope, SharingStarted.Eagerly, if (trackId == null) FollowTrackState.NotFound else FollowTrackState.Loading)

    val direction: StateFlow<FollowDirection> = session.direction

    val reading: StateFlow<FollowReading> = session.reading

    fun setDirection(direction: FollowDirection) = session.setDirection(direction)

    /** Die Seite ruft es, wenn sich die Berechtigung geaendert haben kann (nach dem Dialog, beim Zurueckkehren aus den Einstellungen). */
    fun refreshPermission() = session.refreshPermission()

    /** „Nachfahren beenden“: die Sitzung endet, das Dashboard zeigt den Knopf wieder grau. */
    fun stop() = session.stop()
}
