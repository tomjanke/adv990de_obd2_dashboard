package de.adv990de.obd2.track

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.TrackRepository
import de.adv990de.obd2.di.ApplicationScope
import de.adv990de.obd2.location.LocationSpeedProvider
import de.adv990de.obd2.location.positionUpdates
import de.adv990de.obd2.location.toPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import javax.inject.Singleton

/** Abstand der Positionen beim Nachfahren (eine je Sekunde, wie die Aufzeichnung). */
const val FOLLOW_MIN_INTERVAL_MS = 1_000L

/** Ein Fix, dessen Genauigkeit schlechter ist, wird ignoriert (er wuerde die Marke springen lassen); ohne Angabe zaehlt er. */
const val FOLLOW_MAX_ACCURACY_M = 100f

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

/** Was die Oberflaeche ueber die eigene Position weiss. */
sealed interface FollowReading {
    /** Die Standortberechtigung fehlt: die Seite bietet sie an. */
    data object NoPermission : FollowReading

    /** Es gibt noch keine Position (kein Fix, Anbieter abgeschaltet oder nicht erreichbar, Track nicht geladen). */
    data object Searching : FollowReading

    /**
     * Die Position auf dem Track. [speedKmh] ist die Geschwindigkeit des Fixes (`null`, wenn er keine traegt), [autoSpanM] die Spanne der Karte nach Geschwindigkeit ([AutoZoom], `null` in
     * Tests und Vorschauen: dann zoomt die Karte nicht selbst), [hud] das Roadbook (`null`, solange nichts anzuzeigen ist).
     */
    data class Following(
        val position: TrackPoint,
        val result: FollowResult,
        val speedKmh: Float? = null,
        val autoSpanM: Double? = null,
        val hud: RoadbookHud? = null,
    ) : FollowReading
}

/** Wem gefolgt wird: fuer den Knopf im Dashboard (gruen) und seinen Dialog. [name] ist `null`, solange der Track noch laedt. */
data class FollowInfo(val trackId: Long, val name: String?)

/**
 * Das Nachfahren eines Tracks als **Sitzung**, nur im Speicher (ein Prozessende beendet sie): Sie ueberlebt den Wechsel von der Kartenseite zum Dashboard, damit dort das Roadbook-HUD
 * erscheinen kann. **Keine Routenfuehrung:** Abstand, Strecke, Rest und die Richtungsaenderungen der Linie; kein Ton, keine Ansage.
 *
 * Die Position wird nur gelesen, solange jemand [reading] sammelt (`WhileSubscribed`) — das tun die Kartenseite und das Dashboard, **nur solange sie sichtbar sind**; ohne Sammler haelt der
 * Ortungsdienst an, und der Fortschritt auf der Linie bleibt erhalten. Eine Aufzeichnung und das Nachfahren laufen nicht zugleich (das prueft [de.adv990de.obd2.ui.tracks.TracksViewModel]).
 * **Positionen stehen in keinem Log.**
 */
@Singleton
class FollowSession @Inject constructor(
    private val repository: TrackRepository,
    private val location: LocationSpeedProvider,
    private val appPreferences: AppPreferences,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _trackId = MutableStateFlow<Long?>(null)
    private val _track = MutableStateFlow<FollowTrackState?>(null)
    private val _direction = MutableStateFlow(FollowDirection.FORWARD)
    private val _permission = MutableStateFlow(location.hasPermission())
    private var loadJob: Job? = null

    /** Der Track der Sitzung; `null`, wenn keine laeuft. */
    val track: StateFlow<FollowTrackState?> = _track.asStateFlow()

    val direction: StateFlow<FollowDirection> = _direction.asStateFlow()

    /** Wem gefolgt wird, solange der Track laedt oder bereit ist; sonst `null` (auch bei einem Track, dem sich nicht folgen laesst). */
    val info: StateFlow<FollowInfo?> = combine(_trackId, _track) { id, t ->
        when {
            id == null -> null
            t is FollowTrackState.Ready -> FollowInfo(id, t.name)
            t is FollowTrackState.Loading -> FollowInfo(id, null)
            else -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Auf welchem Dispatcher das Laden und die Erkennung der Richtungsaenderungen rechnen; nur eine Naht fuer Tests (dort der Test-Dispatcher, damit `runCurrent()` genuegt). */
    internal var computeDispatcher: CoroutineDispatcher = Dispatchers.Default

    @Volatile private var roadbookEnabled = true

    @Volatile private var roadbookMinTurnDeg = RoadbookSensitivity.STANDARD.minTurnDeg

    init {
        scope.launch { appPreferences.roadbookEnabled.collect { roadbookEnabled = it } }
        scope.launch { appPreferences.roadbookSensitivity.collect { roadbookMinTurnDeg = RoadbookSensitivity.fromLevel(it).minTurnDeg } }
    }

    /**
     * Beginnt die Sitzung fuer [trackId]. Laeuft sie schon fuer diesen Track, bleibt alles, wie es ist (Fortschritt, Richtung); ein anderer Track oder ein Track, der beim letzten Mal nicht
     * geladen werden konnte, beginnt neu in Richtung „wie aufgezeichnet“.
     */
    fun start(trackId: Long) {
        val state = _track.value
        if (_trackId.value == trackId && state != null && state !is FollowTrackState.NotFound && state !is FollowTrackState.Failed) return
        stop()
        _trackId.value = trackId
        _track.value = FollowTrackState.Loading
        loadJob = scope.launch { load(trackId) }
    }

    /** Beendet die Sitzung; sie ist danach nicht mehr da (das Dashboard zeigt den Knopf wieder grau, die Karte holt keine Position mehr). */
    fun stop() {
        loadJob?.cancel()
        loadJob = null
        _trackId.value = null
        _track.value = null
        _direction.value = FollowDirection.FORWARD
        prepared = null
    }

    fun setDirection(direction: FollowDirection) {
        _direction.value = direction
    }

    /** Die Oberflaeche ruft es, wenn sich die Berechtigung geaendert haben kann (nach dem Dialog, beim Zurueckkehren aus den Einstellungen). */
    fun refreshPermission() {
        _permission.value = location.hasPermission()
    }

    /**
     * Die eigene Position auf dem Track. Der Fluss laeuft nur, solange jemand sammelt (fuenf Sekunden Nachlauf fuer eine Drehung), und beginnt danach wieder mit [FollowReading.Searching];
     * der Fortschritt auf der Linie bleibt dabei erhalten. Ein Wechsel der Richtung, des Tracks oder der Berechtigung beginnt einen neuen Verfolger.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val reading: StateFlow<FollowReading> = combine(_track, _direction, _permission) { t, d, granted -> Triple(t, d, granted) }
        .flatMapLatest { (t, d, granted) -> readingFlow(t, d, granted) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), FollowReading.Searching)

    /** Verfolger und Roadbook einer Linie in einer Richtung; ueberleben ein Abmelden der Sammler (der Fortschritt geht nicht verloren). */
    private class Prepared(val points: List<TrackPoint>, val direction: FollowDirection, val tracker: FollowTracker, val roadbook: RoadbookTracker)

    @Volatile private var prepared: Prepared? = null

    private suspend fun preparedFor(ready: FollowTrackState.Ready, direction: FollowDirection): Prepared {
        prepared?.takeIf { it.points === ready.points && it.direction == direction }?.let { return it }
        val line = FollowLine.of(ready.points, direction)
        // Die Erkennung der Richtungsaenderungen laeuft einmal je Linie und Richtung, nicht auf dem Haupt-Thread.
        val maneuvers = withContext(computeDispatcher) { detectManeuvers(line) }
        return Prepared(ready.points, direction, FollowTracker(line), RoadbookTracker(maneuvers, line)).also { prepared = it }
    }

    private fun readingFlow(track: FollowTrackState?, direction: FollowDirection, granted: Boolean): Flow<FollowReading> {
        val ready = track as? FollowTrackState.Ready ?: return flowOf(FollowReading.Searching)
        if (!granted) return flowOf(FollowReading.NoPermission)
        return flow {
            emit(FollowReading.Searching)
            val p = preparedFor(ready, direction)
            val zoom = AutoZoom()
            location.positionUpdates(FOLLOW_MIN_INTERVAL_MS).collect { fix ->
                if (fix == null) {
                    emit(FollowReading.Searching)
                    return@collect
                }
                val accuracy = fix.accuracyM
                if (accuracy != null && accuracy > FOLLOW_MAX_ACCURACY_M) return@collect
                val point = fix.toPoint()
                val result = p.tracker.update(point) ?: return@collect
                val span = zoom.update(fix.speedKmh, fix.elapsedMs)
                val hud = if (roadbookEnabled) p.roadbook.update(result, point, fix.speedKmh, fix.elapsedMs, roadbookMinTurnDeg) else null
                emit(FollowReading.Following(point, result, fix.speedKmh, span, hud))
            }
        }
    }

    private suspend fun load(id: Long) {
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
            val (bounds, drawn) = withContext(computeDispatcher) { trackStats(all).bounds to simplifyTrack(all, TRACK_DRAW_POINTS) }
            _track.value = FollowTrackState.Ready(summary.name, all, drawn, bounds!!)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _track.value = FollowTrackState.Failed
        }
    }
}
