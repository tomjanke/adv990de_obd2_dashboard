package de.adv990de.obd2.ui.tracks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.track.FollowInfo
import de.adv990de.obd2.track.FollowReading
import de.adv990de.obd2.track.FollowSession
import de.adv990de.obd2.track.FollowStatus
import de.adv990de.obd2.track.FollowStatusTracker
import de.adv990de.obd2.track.RecordingState
import de.adv990de.obd2.track.RoadbookHud
import de.adv990de.obd2.track.TrackRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Was Dashboard und Track-Ansicht vom Nachfahren wissen muessen, ohne die Karte zu kennen: wem gefolgt wird (Knopf, Dialog), das Roadbook-HUD und ob gerade aufgezeichnet wird (dann
 * laesst sich kein Track nachfahren). Das HUD laeuft nur, solange es jemand sammelt — das Dashboard, **solange es sichtbar ist**.
 */
@HiltViewModel
class FollowStatusViewModel @Inject constructor(
    private val session: FollowSession,
    recorder: TrackRecorder,
) : ViewModel() {

    val info: StateFlow<FollowInfo?> = session.info

    /** Das Roadbook-HUD, solange eines anzuzeigen ist. Das Sammeln startet den Ortungsdienst, wenn eine Sitzung laeuft (und haelt ihn nach dem Abmelden fuenf Sekunden). */
    val hud: StateFlow<RoadbookHud?> = session.reading
        .map { (it as? FollowReading.Following)?.hud }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Die Statuszeile (Mitte der Ambient-Statuszeile): `null` ohne Sitzung, sonst „auf dem Track“, „abseits“ oder der Grund, warum es noch keine Position gibt. Ein neuer Track beginnt ohne die
     * Erinnerung an den alten (Totzone, s. [FollowStatusTracker]); der Name, der nach dem Laden eintrifft, setzt nichts zurueck. Wie das HUD laeuft die Position nur, solange jemand sammelt.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val status: StateFlow<FollowStatus?> = session.info
        .distinctUntilChangedBy { it?.trackId }
        .flatMapLatest { info ->
            if (info == null) {
                flowOf(null)
            } else {
                val tracker = FollowStatusTracker()
                session.reading.map { tracker.update(it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Laeuft oder beginnt gerade eine Aufzeichnung? Dann laesst sich kein Track nachfahren. */
    val recordingActive: StateFlow<Boolean> = recorder.state
        .map { it is RecordingState.Recording || it is RecordingState.Starting }
        .stateIn(viewModelScope, SharingStarted.Eagerly, recorder.state.value.let { it is RecordingState.Recording || it is RecordingState.Starting })

    fun stop() = session.stop()
}
