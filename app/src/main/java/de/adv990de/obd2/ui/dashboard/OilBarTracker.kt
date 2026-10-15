package de.adv990de.obd2.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue

// SavedStateHandle-Schluessel fuer den Oel-Balken-Timerzustand — s. Kommentare an OilBarTracker. Die Namen sind persistierter
// Zustand (Prozess-Tod, Activity-Neuerzeugung): nicht umbenennen.
internal const val KEY_OIL_BAR_ABOVE_GREEN_SINCE_MILLIS = "oilBarAboveGreenSinceMillis"
internal const val KEY_OIL_BAR_LAST_SAMPLE_AT_MILLIS = "oilBarLastSampleAtMillis"
internal const val KEY_OIL_BAR_LAST_SAMPLE_TIMESTAMP = "oilBarLastSampleTimestamp"
internal const val KEY_OIL_BAR_BELOW_RESET_SINCE_MILLIS = "oilBarBelowResetSinceMillis"

/**
 * Die Farbe des Oel-Temperatur-Balkens (Gruen-Hysterese) ueber die Zyklen einer Sitzung. Herausgeloest aus `DashboardViewModel`;
 * die reine Rechnung steht in `OilTemperatureBar.kt` ([updateOilBarTimer], [oilBarColor]), hier der Zustand dazu.
 *
 * Der Timer-Zustand ist bewusst NICHT Teil von `DashboardUiState`: [OilBarTimerState] ist ein Berechnungsdetail, das die UI nie liest;
 * in den UI-State gehoert nur die daraus abgeleitete [OilBarColor]. Er wird bei einem Verbindungsabbruch bewusst NICHT zurueckgesetzt (anders
 * als Gang und Geschwindigkeit): die Kuehlmitteltemperatur aendert sich nicht, nur weil BLE kurz aussetzt. Unbegrenzt ueberlebt der Timer
 * eine Trennung trotzdem nicht — das Luecken-Limit in [updateOilBarTimer] (60 s) laesst ihn verfallen, wenn zwischen zwei Messwerten mehr
 * Zeit liegt: ein Reconnect innerhalb dieser Zeit behaelt die Zaehlung, eine laengere Trennung (oder eine stundenlang geschlossene App) beginnt von vorn.
 *
 * Ueber [SavedStateHandle] statt eines einfachen Feldes (Backlog-Sweep 2026-09-28): eine reine In-Memory-Property ueberlebt nur
 * Konfigurationsaenderungen (die ViewModelStore-Instanz bleibt dabei erhalten), nicht aber eine Activity-Neuerzeugung BEI LAUFENDEM Prozess —
 * "Nicht im Hintergrund behalten" oder aggressives OEM-Reclaiming erzeugen dabei ein frisches ViewModel, ohne dass ConnectionLost oder das
 * Luecken-Limit je eintraten. [SavedStateHandle] uebersteht genau das (Bundle aus onSaveInstanceState), zusaetzlich zu Prozess-Tod.
 * [OilBarTimerState] hat keinen eigenen Parcelize-Support, deshalb als einzelne `Long?`-Werte gespeichert (seit 2026-10-12 drei: auch der Beginn eines Einbruchs, s. [RESET_DEBOUNCE_MS] in `OilTemperatureBar.kt`; ein Zustand aus einer älteren Version ohne diesen Schlüssel liest ihn als null).
 *
 * [now] ist die monotone Uhr des Aufrufers (im ViewModel `SystemClock.elapsedRealtime()`): Gruen-Timer und Luecken-Limit rechnen Dauern,
 * die nicht springen duerfen, wenn Netzzeit-Abgleich oder Nutzer die Systemzeit verstellen.
 */
internal class OilBarTracker(
    private val savedStateHandle: SavedStateHandle,
    private val now: () -> Long,
) {

    private var timerState: OilBarTimerState
        get() = OilBarTimerState(
            aboveGreenThresholdSinceMillis = savedStateHandle[KEY_OIL_BAR_ABOVE_GREEN_SINCE_MILLIS],
            lastSampleAtMillis = savedStateHandle[KEY_OIL_BAR_LAST_SAMPLE_AT_MILLIS],
            belowResetSinceMillis = savedStateHandle[KEY_OIL_BAR_BELOW_RESET_SINCE_MILLIS],
        )
        set(value) {
            savedStateHandle[KEY_OIL_BAR_ABOVE_GREEN_SINCE_MILLIS] = value.aboveGreenThresholdSinceMillis
            savedStateHandle[KEY_OIL_BAR_LAST_SAMPLE_AT_MILLIS] = value.lastSampleAtMillis
            savedStateHandle[KEY_OIL_BAR_BELOW_RESET_SINCE_MILLIS] = value.belowResetSinceMillis
        }

    /**
     * `SensorValue.timestamp` des zuletzt eingespeisten Kuehlmittelwerts. `ReadingsPipeline.gpsRefresh()` stellt bei jeder
     * GPS-Aenderung (bis 5 Hz) die letzten OBD-Werte erneut zu — dieselben `SensorValue`-Objekte mit ihrem urspruenglichen Zeitstempel, auch
     * wenn das Steuergeraet laengst nichts Neues mehr liefert (Zuendung aus: nur noch NO DATA, das der Watchdog beliebig lange toleriert).
     * Ohne diese Pruefung bestaetigte GPS-Rauschen allein einen alten Wert ueber 80 °C immer wieder, bis der Balken bei abgestelltem Motor
     * gruen wuerde. Deshalb zaehlt nur ein Messwert mit echt NEUEREM Zeitstempel als neuer Messwert; eine Wiederholung laesst Timer und Farbe unveraendert.
     *
     * Bekannte Grenze: Der Zeitstempel ist Wanduhr-Zeit (`System.currentTimeMillis()` in `PidParser`). Springt die Systemzeit waehrend der Fahrt
     * zurueck (manuelle Umstellung, grosse Netzzeit-Korrektur), gelten neue Messwerte so lange als "nicht neuer", bis die Uhr den zuletzt
     * verarbeiteten Zeitstempel wieder eingeholt hat — der Balken steht fuer die Dauer des Sprungs still (nie faelschlich Gruen; nach mehr als
     * 60 s greift danach das Luecken-Limit). Das ist der Preis fuer `>` statt `!=`: `>` macht auch eine Wiederholung wirkungslos, die erst nach
     * einem neueren Messwert ankaeme.
     *
     * Ebenfalls im [SavedStateHandle], aus demselben Grund wie [timerState]: sonst oeffnete eine Activity-Neuerzeugung bei laufendem Prozess
     * einmalig genau die GPS-Wiederholungs-Falle wieder, die diese Pruefung verhindern soll.
     */
    private var lastSampleTimestamp: Long?
        get() = savedStateHandle[KEY_OIL_BAR_LAST_SAMPLE_TIMESTAMP]
        set(value) { savedStateHandle[KEY_OIL_BAR_LAST_SAMPLE_TIMESTAMP] = value }

    /**
     * Die Restzeit bis Gruen jetzt ([oilBarGreenInMillis], aus demselben Zustand wie die Farbe, mit der Uhr des Aufrufers) — die Ambient-Statuszeile
     * zeigt sie. Rechnet bei jedem Aufruf neu: die Zeit laeuft auch zwischen zwei Messwerten weiter.
     */
    fun greenInMillis(): Long = oilBarGreenInMillis(now(), timerState)

    /**
     * Verarbeitet einen Zyklus und liefert die anzuzeigende Farbe. Unabhaengig von `rpmDynamicThreshold`: die Kuehlmitteltemperatur wird
     * immer gesucht, wenn ein plausibler Wert vorliegt. Fehlt sie in diesem Zyklus (kein Reading oder implausibel, z. B. NaN) oder ist sie nur
     * die GPS-Wiederholung eines schon verarbeiteten Messwerts (Zeitstempel nicht neuer, s. [lastSampleTimestamp]), bleibt der Timer-Zustand
     * unangetastet und die zuletzt angezeigte Farbe ([previous]) gilt — derselbe "keine Regression bei einem einzelnen fehlenden Wert"-Ansatz wie
     * bei `holdGear()`.
     */
    fun update(values: List<SensorValue>, previous: OilBarColor?): OilBarColor? {
        val reading = values
            .firstOrNull { it.pid.id == PidRegistry.COOLANT_TEMP.id && it.value.isFinite() }
            ?.takeIf { sv -> lastSampleTimestamp.let { last -> last == null || sv.timestamp > last } }
            ?: return previous
        lastSampleTimestamp = reading.timestamp
        val nowMillis = now()
        timerState = updateOilBarTimer(reading.value, nowMillis, timerState)
        return oilBarColor(reading.value, nowMillis, timerState)
    }
}
