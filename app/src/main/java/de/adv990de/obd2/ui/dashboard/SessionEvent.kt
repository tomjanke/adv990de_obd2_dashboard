package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText

/** Was der Messwert-Strom dem ViewModel meldet. */
sealed interface SessionEvent {
    /**
     * Ein vollstaendiger Abfragezyklus, bereits um die GPS-Geschwindigkeit ergaenzt.
     *
     * [gpsSpeedKmh] ist der ROHE GPS-Wert desselben Zyklus — unabhaengig davon, ob er in [values]
     * tatsaechlich zum Einsatz kam ([mergeGpsSpeed] gibt dem Fahrzeugwert Vorrang, sobald das
     * Fahrzeug selbst eine Geschwindigkeit meldet). Die virtuelle Ganganzeige braucht ihn separat
     * und exklusiv, s. Kommentar in `DashboardViewModel.applyReadings()`.
     *
     * [isObdCycle] ist `false` fuer die Zwischenupdates, die ein neuer GPS-Fix ausloest (`ReadingsPipeline.gpsRefresh`: dieselben OBD-Werte
     * noch einmal, mit neuer Geschwindigkeit). Die Anzeige rechnet damit neu; aufgezeichnet wird nur je echtem OBD-Zyklus (auch der Gang).
     */
    data class Values(
        val values: List<SensorValue>,
        val gpsSpeedKmh: Float?,
        val isObdCycle: Boolean = true,
    ) : SessionEvent

    /** Keine Verbindung — es fliessen keine Werte. */
    data object Idle : SessionEvent

    /**
     * Die Verbindung steht, aber seit [OBD_STALE_AFTER_MS] kam kein Zyklus mit einem Messwert mehr (das
     * Steuergeraet schweigt, z. B. bei ausgeschalteter Zuendung — der Watchdog toleriert reines NO DATA
     * bewusst). Die zuletzt gezeigten Werte sind veraltet und duerfen nicht weiter als live erscheinen.
     * Der naechste [Values] hebt das wieder auf.
     */
    data object Stale : SessionEvent

    /** Die Verbindung ist waehrend der Fahrt abgerissen; der Koordinator soll uebernehmen. */
    data object ConnectionLost : SessionEvent

    /** Die Sitzung ist an einem anderen Fehler gescheitert. */
    data class Failed(val error: UiText) : SessionEvent
}

/**
 * So lange darf kein OBD-Zyklus mit mindestens einem Messwert eintreffen, bevor die Werte als veraltet
 * gelten ([SessionEvent.Stale]). Ein Zyklus dauert normalerweise ~1 s; ein einzelner Timeout blockiert 5 s.
 * 10 s ueberbruecken ein paar verpasste Zyklen, liegen aber deutlich vor dem Watchdog (Standard 20 s), der
 * einen stummen Adapter ohnehin neu verbindet.
 */
internal const val OBD_STALE_AFTER_MS = 10_000L
