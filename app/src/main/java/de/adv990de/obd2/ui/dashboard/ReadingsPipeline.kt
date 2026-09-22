package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.location.GpsSpeedSource
import de.adv990de.obd2.obd.ObdErrorMapper
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Was der Messwert-Strom dem ViewModel meldet. */
sealed interface SessionEvent {
    /** Ein vollstaendiger Abfragezyklus, bereits um die GPS-Geschwindigkeit ergaenzt. */
    data class Values(val values: List<SensorValue>) : SessionEvent

    /** Keine Verbindung — es fliessen keine Werte. */
    data object Idle : SessionEvent

    /** Die Verbindung ist waehrend der Fahrt abgerissen; der Koordinator soll uebernehmen. */
    data object ConnectionLost : SessionEvent

    /** Die Sitzung ist an einem anderen Fehler gescheitert. */
    data class Failed(val error: UiText) : SessionEvent
}

/**
 * Der Messwert-Pfad: Auswahl der abzufragenden PIDs, Aufzeichnung der Sitzung, Zusammenfuehrung
 * der Geschwindigkeitsquellen, Fahrterkennung.
 *
 * Herausgeloest aus `DashboardViewModel`. Was hier nicht mehr steht, ist Absicht: Der
 * Verbindungsaufbau gehoert in den [ConnectionCoordinator], und wie ein Zyklus in der Oberflaeche
 * aussieht — Sparklines, Statusfarben, Kachelbelegung — entscheidet das ViewModel.
 */
class ReadingsPipeline @Inject constructor(
    private val obdRepository: ObdRepository,
    private val sessionRepository: SessionRepository,
    private val appPreferences: AppPreferences,
    private val appLogRepository: AppLogRepository,
    private val crashReporter: CrashReporter,
    private val gpsSpeedSource: GpsSpeedSource,
) {

    /**
     * GPS-Geschwindigkeit in km/h, null wenn das Feature aus ist, keine Berechtigung vorliegt oder
     * kein Fix mit Geschwindigkeit ansteht.
     *
     * `Eagerly` ist hier Pflicht, nicht Geschmack: Der Wert wird ausschliesslich ueber
     * [StateFlow.value] gelesen, und ein `WhileSubscribed`-Flow ohne Sammler startet seinen
     * Upstream nie — `value` bliebe fuer immer auf dem Initialwert null stehen. Genau dieser Fehler
     * liess die GPS-Geschwindigkeit bis 1.10.1 nie im Dashboard ankommen.
     *
     * Der `flatMapLatest` sorgt dafuer, dass der GPS-Empfaenger trotzdem nur laeuft, solange der
     * Schalter an ist.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun gpsSpeedKmh(scope: CoroutineScope): StateFlow<Float?> = appPreferences.useGpsSpeed
        .flatMapLatest { enabled -> if (enabled) gpsSpeedSource.speedKmh else flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Der Ereignisstrom einer Sitzung. Kalt: Er laeuft erst, wenn jemand sammelt, und endet mit
     * dem [scope], der die GPS-Quelle traegt.
     *
     * Hinweis: Eine Slot-Aenderung waehrend der Fahrt beendet die laufende Sitzung und beginnt eine
     * neue. Das ist gewollt — die Kachelbelegung liegt unter den Einstellungen und wird zwischen
     * Fahrten geaendert.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun events(scope: CoroutineScope): Flow<SessionEvent> {
        val gpsSpeed = gpsSpeedKmh(scope)
        return combine(
            obdRepository.isConnectedFlow,
            appPreferences.slotPids,
        ) { connected, slotIds -> connected to slotIds }
            .flatMapLatest { (connected, slotIds) ->
                if (!connected) flowOf(SessionEvent.Idle) else runSession(slotIds, gpsSpeed)
            }
    }

    private fun runSession(slotIds: List<String>, gpsSpeed: StateFlow<Float?>): Flow<SessionEvent> = flow {
        val selectedPids = selectPids(slotIds)

        // Aufzeichnungs-Einstellungen einmal pro Sitzung lesen, nicht pro Zyklus.
        val logOnlyDtc = appPreferences.logOnlyDtcSessions.first()
        val logOnlyDiscovered = appPreferences.logOnlyDiscoveredPids.first()
        val loggedPidIds = appPreferences.loggedPidIds.first()

        val vin = try { obdRepository.readVin() } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

        // Fehlercodes beim Verbinden lesen: protokollieren und als Filter fuer die Aufzeichnung
        // verwenden. Kein Banner in der Oberflaeche — sichtbar sind sie im App-Log.
        val dtcList = try { obdRepository.readDtcs() } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        if (dtcList?.isNotEmpty() == true) {
            appLogRepository.log(LogLevel.WARN, "Active DTCs on connect: ${dtcList.joinToString()}")
        }
        val hasDtcForRecording: Boolean = dtcList?.isNotEmpty() ?: true

        var sessionId = -1L
        try {
            sessionId = sessionRepository.startSession(vin)
            emitAll(
                obdRepository.livePids(selectedPids)
                    // GPS ergaenzt die Geschwindigkeit, bevor aufgezeichnet und angezeigt wird —
                    // der Fahrzeugwert hat Vorrang.
                    .map { mergeGpsSpeed(it, gpsSpeed.value) }
                    .onEach { values -> record(sessionId, values, logOnlyDiscovered, loggedPidIds) }
                    .onEach { values ->
                        obdRepository.updateLatestReadings(values)
                        // Fahrterkennung aus der Geschwindigkeit — gleich ob sie vom Fahrzeug oder
                        // vom GPS kommt. Fehlt sie ganz, gilt "steht", damit die Navigation nie
                        // dauerhaft ausgeblendet bleibt.
                        val speed = values.firstOrNull { it.pid.id == PidRegistry.SPEED.id }
                        obdRepository.setMoving(speed != null && speed.value > 0f)
                    }
                    .map { SessionEvent.Values(it) }
            )
        } catch (e: CancellationException) {
            throw e  // Abbruch einer Coroutine nie verschlucken
        } catch (e: ObdException.ConnectionLost) {
            emit(SessionEvent.ConnectionLost)
        } catch (e: Exception) {
            crashReporter.recordException(e)
            emit(SessionEvent.Failed(ObdErrorMapper.toUiText(e)))
        } finally {
            // Datenbankfehler duerfen das Aufraeumen der Sitzung nicht verhindern.
            if (sessionId != -1L) {
                if (logOnlyDtc && !hasDtcForRecording) {
                    try { sessionRepository.deleteSession(sessionId) } catch (_: Exception) {}
                } else {
                    try { sessionRepository.endSession(sessionId) } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Die tatsaechlich abzufragenden PIDs: die belegten Slots, gefiltert gegen die vom Steuergeraet
     * gemeldete Menge, dazu immer die Drehzahl und — bei dynamischer Schwelle — die Temperaturen.
     *
     * Greift keiner davon, bleiben die Standard-PIDs. Das faengt den Fall ab, dass im DataStore
     * nur noch unbekannte IDs stehen, etwa nach einem Downgrade.
     */
    private suspend fun selectPids(slotIds: List<String>) = run {
        val dynamicThresholdEnabled = appPreferences.rpmDynamicThreshold.first()
        val supported = obdRepository.supportedPidIds.value
        val filteredSlotIds = if (supported.isNullOrEmpty()) slotIds else slotIds.filter { it in supported }
        buildList {
            addAll(filteredSlotIds)
            add(PidRegistry.RPM.id)
            if (dynamicThresholdEnabled) {
                add(PidRegistry.ENGINE_OIL_TEMP.id)
                add(PidRegistry.COOLANT_TEMP.id)
            }
        }
            .mapNotNull { PidRegistry.all[it] }
            .distinctBy { it.id }
            .ifEmpty { PidRegistry.defaults }
    }

    private suspend fun record(
        sessionId: Long,
        values: List<SensorValue>,
        logOnlyDiscovered: Boolean,
        loggedPidIds: Set<String>,
    ) {
        val filtered = values
            .filter { sv ->
                !logOnlyDiscovered || (obdRepository.supportedPidIds.value?.contains(sv.pid.id) ?: true)
            }
            .filter { sv -> sv.pid.id in loggedPidIds }
        sessionRepository.writeReadings(sessionId, filtered)
    }
}
