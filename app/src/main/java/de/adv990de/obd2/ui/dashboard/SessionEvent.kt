package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.model.DtcEntry
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

    /**
     * Die Werte sind eingefroren und werden jetzt zurueckgehalten ([FrozenAction.Hold], zum ersten Mal in einem Fall): einmal je Sperre, nicht je Zyklus. Die Oberflaeche zeigt "veraltet"
     * mit der Ursache "eingefroren" **sofort**, nicht erst nach [OBD_STALE_AFTER_MS]; das [Stale] danach aendert daran nichts. Der naechste [Values] hebt es auf.
     */
    data object Frozen : SessionEvent

    /**
     * Die GPS-Geschwindigkeit hat sich geaendert, **waehrend die Fahrzeugwerte fehlen** ([Stale] oder [Frozen]): sie haengt nicht am Adapter und wird weiter angezeigt (nur in diesem Zustand)
     * und fuer die Tankanzeige gezaehlt. [kmh] ist `null` ohne Fix oder mit abgeschaltetem GPS. Anders als [Values] bringt das nie alte Fahrzeugwerte zurueck.
     */
    data class GpsSpeed(val kmh: Float?) : SessionEvent

    /** Die Verbindung ist waehrend der Fahrt abgerissen; der Koordinator soll uebernehmen. [cause] sagt, ob der Link versagt hat oder die App wegen eingefrorener Werte selbst getrennt hat. */
    data class ConnectionLost(val cause: ConnectionLostCause = ConnectionLostCause.LINK) : SessionEvent

    /** Die Sitzung ist an einem anderen Fehler gescheitert. */
    data class Failed(val error: UiText) : SessionEvent

    /**
     * Ein Auslesen der Fehlercodes. [isBaseline] = das Auslesen beim Verbinden (einmal je Sitzung, **auch als `null`**, wenn es keine positive
     * Antwort gab): der Stand, gegen den die Fehlerwache neue Codes erkennt, kein Anlass zum Alarm. Sonst ein Nachlesen der Wache unterwegs
     * (`ReadingsPipeline.faultWatch`), und das ist immer ein Erfolg — ein Auslesen ohne Antwort meldet die Wache gar nicht.
     * [codes] `null` heisst "keine Aussage", nie "keine Codes" (das waere eine leere Liste).
     */
    data class FaultCodes(val codes: List<DtcEntry>?, val isBaseline: Boolean) : SessionEvent
}

/**
 * So lange darf kein OBD-Zyklus mit mindestens einem Messwert eintreffen, bevor die Werte als veraltet
 * gelten ([SessionEvent.Stale]). Ein Zyklus dauert normalerweise ~1 s; ein einzelner Timeout blockiert 5 s.
 * 10 s ueberbruecken ein paar verpasste Zyklen, liegen aber deutlich vor dem Watchdog (Standard 20 s), der
 * einen stummen Adapter ohnehin neu verbindet.
 */
internal const val OBD_STALE_AFTER_MS = 10_000L
