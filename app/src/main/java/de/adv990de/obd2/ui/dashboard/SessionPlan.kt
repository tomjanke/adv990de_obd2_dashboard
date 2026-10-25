package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.obd.model.PidDefinition
import kotlinx.coroutines.flow.first

/**
 * Was eine Sitzung aus den Einstellungen braucht — **einmal je Sitzung gelesen, nicht je Zyklus** (`ReadingsPipeline.runSession`): die abzufragenden PIDs ([selectedPids]), ob nur Fahrten mit
 * Fehlercodes aufgezeichnet werden ([logOnlyDtc]), das Zeitlimit des Watchdogs ([watchdogTimeoutMs]) und die PIDs, die nur jeden vierten Zyklus gefragt werden ([slowPids]).
 */
internal class SessionPlan(
    val selectedPids: List<PidDefinition>,
    val logOnlyDtc: Boolean,
    val watchdogTimeoutMs: Long,
    val slowPids: List<PidDefinition>,
)

/**
 * Liest den [SessionPlan] aus den Einstellungen. [supported] ist die vom Steuergeraet gemeldete PID-Menge (`null` solange keine Discovery gelaufen ist).
 *
 * Die Spannung nur fuer den Alarm: jeden 4. Zyklus, wenn sie keine Kachel ist. Wie die dynamische Schwelle einmal je Sitzung gelesen —
 * eingeschaltet waehrend der Verbindung wirkt die Abfrage ab der naechsten (der Tracker liest den Schalter selbst live).
 */
internal suspend fun readSessionPlan(slotIds: List<String>, supported: Set<String>?, appPreferences: AppPreferences): SessionPlan {
    val selectedPids = selectPids(
        slotIds,
        supported = supported,
        dynamicThresholdEnabled = appPreferences.rpmDynamicThreshold.first(),
    )
    val logOnlyDtc = appPreferences.logOnlyDtcSessions.first()
    val watchdogTimeoutMs = appPreferences.obdWatchdogTimeoutSeconds.first() * 1_000L
    val slowPids = selectSlowPids(selectedPids, supported, appPreferences.alarmSettings.first().voltageEnabled)
    return SessionPlan(selectedPids, logOnlyDtc, watchdogTimeoutMs, slowPids)
}
