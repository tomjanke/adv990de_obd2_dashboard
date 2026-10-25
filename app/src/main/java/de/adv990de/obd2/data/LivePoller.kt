package de.adv990de.obd2.data

import de.adv990de.obd2.obd.ElmController
import de.adv990de.obd2.obd.PidParser
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * Die Abfrageschleife der Live-Werte, aus dem [ObdRepository] herausgezogen (wie [DiagnosticReads] fuer die einmaligen Lesevorgaenge): sie bekommt die Verbindung je
 * Zyklus von aussen ([currentController]), meldet die rohen Antworten jedes Zyklus ([onCycleRaw], fuer die Diagnose in `ObdRepository.dropFrozenLink`) und eine gebrochene
 * Verbindung ([onLinkBroken], das Repository trennt daraufhin — nur wenn es noch dieselbe Verbindung ist, PRO-11). Die Zeitlimit-Richtlinie steht als KDoc an `ObdRepository`.
 */
internal class LivePoller(
    private val pidParser: PidParser,
    /** Der Controller der aktuellen Verbindung; `null` ohne Verbindung (das beendet die Schleife). Wird in jedem Zyklus neu erfragt. */
    private val currentController: () -> ElmController?,
    /** Trennt, falls der Controller noch die aktuelle Verbindung ist (`ObdRepository.disconnectIfCurrent`). */
    private val onLinkBroken: suspend (ElmController) -> Unit,
    /** Die rohen Antworten des gerade beendeten Zyklus je Befehl ("010C" -> "41 0C 19 31"); auch ein Teilzyklus, den ein BusInit unterbrach. */
    private val onCycleRaw: (Map<String, String>) -> Unit,
) {

    /**
     * Continuously polls the given [pids] and emits the latest values.
     * Skips PIDs that return NO DATA without stopping the stream.
     *
     * Two independent escalation paths can force a reconnect, whichever crosses its own
     * threshold first: 5 consecutive BusInit errors (bus stuck), or the watchdog: a cycle without
     * a single reading that hit at least one Timeout or UnknownResponse, at a point where
     * [watchdogTimeoutMs] of real time have passed since the link last showed it works. That is
     * the last cycle with readings, or one the ECU answered entirely with NO DATA (e.g. ignition
     * off): a well-formed answer, so such a cycle restarts the clock rather than counting against
     * it, and NoData alone never trips the watchdog. It catches a silently frozen adapter/BLE
     * stack that keeps timing out without ever hitting BusInit, and an adapter that still answers
     * but only unparseably (e.g. echo back on after it reset itself), which a reconnect cures by
     * re-running initialize(). The check runs once per cycle, so it fires up to one cycle after
     * the threshold. See [isReadingsStale].
     *
     * [timeSource] only exists so tests can inject kotlinx-coroutines-test's virtual clock
     * (`testTimeSource`); production uses the default [TimeSource.Monotonic]. It has to be real
     * time: a single Timeout already blocks for ElmController's 5 s read timeout, so the earlier
     * approach of adding [pollIntervalMs] per empty cycle undercounted a real stall 30-40x.
     *
     * [slowPids] (nicht in [pids]) kommen zusaetzlich in jedem [slowEveryCycles]-ten Zyklus dran, der erste (Zaehler 0) eingeschlossen — die
     * Spannung fuer den Spannungsalarm aendert sich langsam und kostet 0,15-0,3 s je Befehl. Watchdog, BusInit und Teilzyklen behandeln so
     * einen Zyklus wie jeden anderen.
     */
    fun poll(
        pids: List<PidDefinition>,
        pollIntervalMs: Long,
        watchdogTimeoutMs: Long,
        timeSource: TimeSource,
        slowPids: List<PidDefinition>,
        slowEveryCycles: Int,
    ): Flow<List<SensorValue>> = flow {
        require(slowEveryCycles >= 1) { "slowEveryCycles must be at least 1, was $slowEveryCycles" }
        var consecutiveBusInitErrors = 0
        var lastSuccessMark = timeSource.markNow()
        var cycle = 0L
        while (true) {
            // The repository's elm is @Volatile; capture the controller into a local so the reference stays alive even if
            // a concurrent disconnect() nulls it after the null check.
            val controller = currentController() ?: break
            val values = mutableListOf<SensorValue>()
            val rawByCommand = LinkedHashMap<String, String>()
            var busInitOccurred = false
            var timeoutOrUnknownOccurred = false
            val cyclePids = if (slowPids.isNotEmpty() && cycle % slowEveryCycles == 0L) pids + slowPids else pids
            for (pid in cyclePids) {
                try {
                    val raw = controller.sendCommand(pid.id)
                    rawByCommand[pid.id] = raw
                    values.add(pidParser.parse(pid, raw))
                } catch (_: ObdException.NoData) {
                    // Benign: the ECU explicitly has nothing to report for this PID, so the link
                    // itself demonstrably works. A cycle of nothing else restarts the watchdog below.
                } catch (_: ObdException.UnknownResponse) {
                    // The adapter answered, but unparseably. Once persistent (e.g. echo back on
                    // after the adapter reset itself), no reading gets through: counts for the watchdog.
                    timeoutOrUnknownOccurred = true
                } catch (_: ObdException.Timeout) {
                    // skip this PID this cycle — adapter may be slow; counts for the watchdog
                    timeoutOrUnknownOccurred = true
                } catch (_: ObdException.BusInit) {
                    busInitOccurred = true
                    break  // bus re-initializing; skip rest of this poll cycle
                } catch (e: ObdException.ConnectionLost) {
                    // disconnect() acquires connectMutex. If reconnect() is currently holding
                    // that mutex (i.e., is mid-BT-connect), this NonCancellable block will
                    // suspend until reconnect() releases the lock. Self-resolving, not a true
                    // deadlock, but the NonCancellable section may run longer than expected.
                    withContext(NonCancellable) { onLinkBroken(controller) }
                    throw e  // fatal — propagate to ViewModel
                }
            }
            onCycleRaw(rawByCommand)
            if (busInitOccurred) {
                consecutiveBusInitErrors++
                // After 5 consecutive BusInit errors the bus is stuck — escalate to ConnectionLost
                // so the ViewModel triggers auto-reconnect instead of polling silently forever.
                if (consecutiveBusInitErrors >= 5) {
                    withContext(NonCancellable) { onLinkBroken(controller) }
                    throw ObdException.ConnectionLost()
                }
            } else {
                consecutiveBusInitErrors = 0
            }
            // Silent drop: when all PIDs return NoData/Timeout/UnknownResponse in a cycle, nothing
            // is emitted. The collector sees no update for this cycle; the UI retains the last values.
            // Also suppress partial-cycle values collected before a BusInit interrupted the loop —
            // emitting them would show a stale mix of fresh and un-refreshed PID readings.
            if (!busInitOccurred && values.isNotEmpty()) {
                lastSuccessMark = timeSource.markNow()
                emit(values)
            } else if (timeoutOrUnknownOccurred) {
                // The watchdog. A NoData from another PID in the same cycle does not offset the
                // Timeout/UnknownResponse. BusInit keeps its own 5-strike counter above.
                if (isReadingsStale(lastSuccessMark.elapsedNow().inWholeMilliseconds, watchdogTimeoutMs)) {
                    withContext(NonCancellable) { onLinkBroken(controller) }
                    throw ObdException.ConnectionLost()
                }
            } else if (!busInitOccurred) {
                // Entirely NoData this cycle: the link works, so nothing to escalate, but nothing
                // to emit either. Restart the watchdog's clock anyway, so a later isolated Timeout
                // is judged against this recent baseline, not against the last reading from before
                // a long, benign NO DATA stretch (e.g. parked, ignition off), which would escalate
                // on the spot.
                lastSuccessMark = timeSource.markNow()
            }
            cycle++
            delay(pollIntervalMs)
        }
    }
}
