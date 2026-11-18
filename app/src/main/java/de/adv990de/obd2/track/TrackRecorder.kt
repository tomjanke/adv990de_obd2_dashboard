package de.adv990de.obd2.track

import android.os.SystemClock
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.TrackRepository
import de.adv990de.obd2.di.ApplicationScope
import de.adv990de.obd2.location.LocationSpeedProvider
import de.adv990de.obd2.location.TrackFix
import de.adv990de.obd2.location.toPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Was die Aufzeichnung gerade tut. */
sealed interface RecordingState {
    data object Idle : RecordingState

    /** Zwischen dem Tippen auf „Start“ und der angelegten Spur. */
    data object Starting : RecordingState

    /**
     * Eine Aufzeichnung laeuft. [points] und [distanceM] zaehlen die angenommenen Punkte (auch solche, die noch nicht geschrieben sind). [signal] ist falsch, solange der Anbieter abgeschaltet ist oder
     * noch kein Fix kam; [writeFailed] ist wahr, solange ein Schreibversuch gescheitert ist — die Punkte werden erneut versucht, die Anzeige laeuft weiter.
     */
    data class Recording(
        val trackId: Long,
        val points: Int,
        val distanceM: Double,
        val startedMs: Long,
        val signal: Boolean,
        val writeFailed: Boolean,
    ) : RecordingState
}

/** Ergebnis von [TrackRecorder.start]. Nur bei [Started] gibt es eine Spur. */
enum class StartResult { Started, AlreadyRunning, NoPermission, NoLocationService, Failed }

/**
 * Zeichnet eine Strecke auf: Fixe vom Ortungsdienst, durch den [TrackPointFilter], gedrosselt in die Datenbank (alle [FLUSH_POINTS] Punkte oder nach [FLUSH_INTERVAL_MS], beim Stopp der Rest).
 * Laeuft im `@ApplicationScope` — es braucht weder eine Oberflaeche noch eine OBD-Verbindung.
 *
 * - **Reihenfolge beim Start:** erst die Quelle anmelden, dann die Spur anlegen. Gibt es keine Quelle, entsteht keine leere Spur; die Fixe zwischen Anmelden und Anlegen warten im Kanal.
 * - **Aufzeichnen ist Beiwerk:** ein Schreibfehler beendet nichts. Er wird **einmal** gemeldet (App-Log ohne Position, nur der Klassenname der Ursache) und zeigt sich im Zustand; die Punkte
 *   bleiben im Speicher (hoechstens [maxPendingPoints], die aeltesten fallen zuerst) und werden nach [FLUSH_INTERVAL_MS] erneut geschrieben — `appendPoints` ist alles oder nichts, ein
 *   Wiederholen schreibt nie doppelt.
 * - **Stopp ist unabbrechbar:** wer die Aufzeichnung beendet, bekommt eine abgeschlossene Spur, auch wenn der Aufrufer weg ist (sonst bliebe sie „laufend“ und nicht loeschbar).
 * - **Ein Prozessende** verliert hoechstens die Punkte der letzten 15 s; `TrackRepository.recoverUnfinished` schliesst die Spur beim naechsten Start ab.
 * - **Ein Filter je Aufzeichnung:** der erste Fix jeder Aufzeichnung zaehlt wieder.
 *
 * Positionen stehen nirgends im Protokoll.
 */
@Singleton
class TrackRecorder @Inject constructor(
    private val location: LocationSpeedProvider,
    private val repository: TrackRepository,
    private val appLog: AppLogRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Monotone Uhr (Altern, Drosselung); Tests setzen sie (Konvention wie `TerrainSource.nowMillis`, bewusst kein Konstruktor-Parameter: Hilt). */
    internal var elapsedMs: () -> Long = { SystemClock.elapsedRealtime() }

    /** Wanduhr fuer Beginn, Ende und den Standardnamen. */
    internal var wallMs: () -> Long = { System.currentTimeMillis() }

    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    internal var maxPendingPoints: Int = MAX_PENDING_POINTS

    private val mutableState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = mutableState.asStateFlow()

    private val mutex = Mutex()
    private var session: Session? = null

    /** Der Zustand einer laufenden Aufzeichnung; nur die Verarbeitungs-Coroutine und [start]/[stop] (unter dem Mutex) fassen ihn an. */
    private class Session(val trackId: Long, val startedMs: Long, startElapsedMs: Long, val channel: Channel<TrackFix?>, val stopSource: () -> Unit) {
        val filter = TrackPointFilter()
        val buffer = ArrayList<TrackPoint>()
        var lastFlushAt = startElapsedMs
        var signal = false
        var failing = false
        var reported = false
        var points = 0
        var distanceM = 0.0
        var last: TrackPoint? = null
        lateinit var job: Job
    }

    /**
     * Beginnt eine Aufzeichnung. [name] ohne Inhalt heisst [defaultTrackName]. Ohne Berechtigung oder ohne Quelle entsteht nichts; ein zweiter Aufruf waehrend der Aufzeichnung tut nichts.
     */
    suspend fun start(name: String?): StartResult = mutex.withLock {
        if (session != null) return StartResult.AlreadyRunning
        if (!location.hasPermission()) return StartResult.NoPermission
        mutableState.value = RecordingState.Starting
        val channel = Channel<TrackFix?>(Channel.UNLIMITED)
        val stopSource = location.startPositionUpdates(FIX_INTERVAL_MS) { channel.trySend(it) }
        if (stopSource == null) {
            channel.close()
            mutableState.value = RecordingState.Idle
            return StartResult.NoLocationService
        }
        val startedMs = wallMs()
        val trackId = try {
            repository.create(
                name = normalizeTrackName(name) ?: defaultTrackName(startedMs, zone()),
                source = TrackSource.RECORDED,
                startMs = startedMs,
                createdMs = startedMs,
            )
        } catch (e: Exception) {
            stopSource()
            channel.close()
            mutableState.value = RecordingState.Idle
            if (e is CancellationException) throw e
            appLog.log(LogLevel.WARN, "Track recording could not start (${e::class.simpleName})")
            return StartResult.Failed
        }
        val s = Session(trackId, startedMs, elapsedMs(), channel, stopSource)
        session = s
        s.job = scope.launch { process(s) }
        publish(s)
        appLog.log(LogLevel.INFO, "Track recording started")
        StartResult.Started
    }

    /** Beendet die Aufzeichnung: schreibt den Rest, schliesst die Spur ab, meldet die Quelle ab. Ohne Aufzeichnung geschieht nichts. Laeuft auch dann zu Ende, wenn der Aufrufer abgebrochen wird. */
    suspend fun stop() = withContext(NonCancellable) {
        mutex.withLock {
            val s = session ?: return@withLock
            s.stopSource()
            s.channel.close()
            s.job.join()
            finish(s)
            session = null
            mutableState.value = RecordingState.Idle
        }
    }

    /** Die Verarbeitung einer Aufzeichnung: Fixe aus dem Kanal, zwischen zwei Fixen die Zeitgrenze des Schreibens im Blick. Endet, wenn der Kanal geschlossen und leer ist. */
    private suspend fun process(s: Session) {
        while (true) {
            val wait = if (s.buffer.isEmpty()) null else maxOf(1L, s.lastFlushAt + FLUSH_INTERVAL_MS - elapsedMs())
            val result = next(s, wait)
            when {
                result == null -> flush(s)
                result.isClosed -> break
                else -> handle(s, result.getOrNull())
            }
        }
        flush(s)
        if (s.buffer.isNotEmpty()) appLog.log(LogLevel.WARN, "Track recording ended with ${s.buffer.size} unsaved points")
    }

    /**
     * Der naechste Eintrag des Kanals, oder `null`, wenn [waitMs] ohne einen verstrichen sind. **`select` statt `withTimeoutOrNull`:** das Zeitlimit darf ein schon empfangenes Element verwerfen, wenn
     * es genau mit dem Eintreffen zusammenfaellt (der Punkt waere weg); `select` waehlt genau eine Klausel, ein bereitliegender Fix gewinnt, die Frist ist dann in der naechsten Runde dran.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun next(s: Session, waitMs: Long?): ChannelResult<TrackFix?>? =
        if (waitMs == null) {
            s.channel.receiveCatching()
        } else {
            select {
                s.channel.onReceiveCatching { it }
                onTimeout(waitMs) { null }
            }
        }

    private suspend fun handle(s: Session, fix: TrackFix?) {
        if (fix == null) {
            s.signal = false
            publish(s)
            return
        }
        s.signal = true
        val point = fix.toPoint()
        if (s.filter.accept(point, fix.elapsedMs)) {
            s.last?.let { s.distanceM += distanceM(it, point) }
            s.last = point
            s.points++
            s.buffer += point
            val excess = s.buffer.size - maxPendingPoints
            if (excess > 0) s.buffer.subList(0, excess).clear()
        }
        publish(s)
        // Nach einem Fehler nur noch nach der Zeit (sonst schluege jeder weitere Punkt einen neuen Versuch an), sonst auch bei einem vollen Block.
        if ((s.buffer.size >= FLUSH_POINTS && !s.failing) || elapsedMs() - s.lastFlushAt >= FLUSH_INTERVAL_MS) flush(s)
    }

    private suspend fun flush(s: Session) {
        if (s.buffer.isEmpty()) return
        s.lastFlushAt = elapsedMs()
        try {
            repository.appendPoints(s.trackId, s.buffer.toList())
            s.buffer.clear()
            if (s.failing) {
                s.failing = false
                publish(s)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!s.failing) {
                s.failing = true
                publish(s)
            }
            if (!s.reported) {
                s.reported = true
                appLog.log(LogLevel.WARN, "Track recording: writing points failed (${e::class.simpleName})")
            }
        }
    }

    /** Schliesst die Spur ab. Die Kennzahlen kommen aus den gespeicherten Punkten; lassen die sich nicht lesen, gilt die mitgezaehlte Strecke (ohne Hoehen). Ein Fehler hier laesst die Spur offen — `recoverUnfinished` holt sie. */
    private suspend fun finish(s: Session) {
        val stats = try {
            trackStats(repository.points(s.trackId))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            appLog.log(LogLevel.WARN, "Track recording: reading the points back failed (${e::class.simpleName})")
            TrackStats(distanceM = s.distanceM, durationMs = null, ascentM = null, descentM = null, bounds = null)
        }
        try {
            repository.finish(s.trackId, wallMs(), stats)
            appLog.log(LogLevel.INFO, "Track recording stopped: ${s.points} points")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            appLog.log(LogLevel.WARN, "Track recording: finishing the track failed (${e::class.simpleName})")
        }
    }

    private fun publish(s: Session) {
        mutableState.value = RecordingState.Recording(
            trackId = s.trackId, points = s.points, distanceM = s.distanceM, startedMs = s.startedMs, signal = s.signal, writeFailed = s.failing,
        )
    }

    internal companion object {
        /** Abstand der Fixe (die Strecke braucht keine 5 Hz). */
        const val FIX_INTERVAL_MS = 1_000L

        /** So viele angenommene Punkte machen einen Schreibblock voll. */
        const val FLUSH_POINTS = 10

        /** So lange hoechstens bleibt ein Punkt im Speicher (ein Prozessende verliert hoechstens so viel). */
        const val FLUSH_INTERVAL_MS = 15_000L

        /** Haelt ein Schreibfehler an, wachsen die Punkte im Speicher bis hierher (rund 90 Minuten bei 1 Hz), dann fallen die aeltesten. */
        const val MAX_PENDING_POINTS = 5_000
    }
}
