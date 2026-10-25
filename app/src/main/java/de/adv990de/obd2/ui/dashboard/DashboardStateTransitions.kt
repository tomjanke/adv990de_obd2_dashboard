package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.model.SensorValue

/*
 * Die Zustandsuebergaenge des Dashboards als reine Funktionen `DashboardUiState -> DashboardUiState`, herausgeloest aus `DashboardViewModel`.
 * Das ViewModel entscheidet, WANN ein Uebergang gilt (Ereignis des Messwert-Stroms oder des Koordinators) und raeumt die Tracker ab
 * (Sparklines, Gang, Spannung); hier steht, WAS im Zustand danach steht. Jede Funktion liest nur den Zustand, den sie bekommt — so lassen sich die
 * Felder, die ein Uebergang setzt, zuruecksetzt oder stehen laesst, einzeln pruefen.
 */

/**
 * Der Link zum Adapter ist aufgebaut, Werte fehlen noch. Setzt nur, was bei einem schon "verbundenen"
 * Zustand nicht ueberschrieben werden soll (kommt der erste Messwert vor diesem Aufruf, bleibt alles).
 */
internal fun DashboardUiState.afterLinkUp(): DashboardUiState {
    if (isConnected) return this
    return copy(
        isConnected = true,
        isReconnecting = false,
        reconnectAttempt = 0,
        reconnectCause = null,
        error = null,
        isStale = false,
        valuesLost = null,
        gpsSpeedKmh = null,
        awaitingFirstValues = true,
    )
}

/**
 * Der Zustand nach einem OBD-Zyklus: die Kacheln der Belegung ([displayIds]), Drehzahl, Geschwindigkeit, Gang, Oel-Balken, Spannungs-Trend und die
 * Sparklines des Zyklus; die Verbindung gilt als stehend und frisch (keine Wiederverbindung, nichts veraltet, kein Fehler). Die Werte rechnet der Aufrufer
 * (Tracker und Fallbacks stehen im `DashboardViewModel`).
 */
internal fun DashboardUiState.afterReadings(
    values: List<SensorValue>,
    displayIds: Set<String>,
    rpmValue: Float,
    engineTempC: Float?,
    speedKmh: Float?,
    gear: GearReading,
    oilBarColor: OilBarColor?,
    oilBarGreenInMillis: Long,
    voltageTrend: VoltageTrend?,
    sparklines: Map<String, List<Float>>,
): DashboardUiState = copy(
    readings = values.filter { sv -> sv.pid.id in displayIds },
    rpmValue = rpmValue,
    engineTempC = engineTempC,
    speedKmh = speedKmh,
    gear = gear.gear,
    isNeutral = gear.isNeutral,
    oilBarColor = oilBarColor,
    // Die Restzeit laeuft mit der Uhr, nicht nur mit neuen Messwerten: auch ein GPS-Zwischenupdate rechnet sie neu.
    oilBarGreenInMillis = oilBarGreenInMillis,
    voltageTrend = voltageTrend,
    isConnected = true,
    isReconnecting = false,
    reconnectCause = null,
    isStale = false,
    valuesLost = null,
    gpsSpeedKmh = null,
    awaitingFirstValues = false,
    error = null,
    sparklines = sparklines,
)

/**
 * Das Steuergeraet schweigt bei stehender Verbindung (B29) oder die Werte sind eingefroren und werden zurueckgehalten: Die Werte sind veraltet und werden
 * nicht weiter als live gezeigt — Kacheln "--", Drehzahlbalken leer, Gang "–". Verbindungsstatus
 * und Verbindungs-Punkt bleiben (der Adapter ist ja da). Die Oel-Balken-Farbe bleibt gehalten (sie
 * hat ihr eigenes 60-s-Luecken-Limit); sie wird ohne "live" ohnehin grau gezeichnet.
 *
 * [cause] ist die Ursache; **"eingefroren" bleibt, wenn es schon galt** (das [SessionEvent.Stale] zehn Sekunden nach dem Beginn der Sperre aendert den Grund nicht).
 * Die GPS-Geschwindigkeit steht ab jetzt in der Anzeige ([DashboardUiState.gpsSpeedKmh]), der Wert des letzten Zyklus ([lastGpsSpeedKmh]), bis das GPS einen neuen meldet.
 */
internal fun DashboardUiState.afterStale(cause: ValuesLostCause, lastGpsSpeedKmh: Float?): DashboardUiState {
    val shownCause = if (valuesLost == ValuesLostCause.FROZEN) ValuesLostCause.FROZEN else cause
    return copy(
        voltageTrend = null,
        isStale = true,
        valuesLost = shownCause,
        gpsSpeedKmh = lastGpsSpeedKmh,
        awaitingFirstValues = false,
        readings = emptyList(),
        rpmValue = 0f,
        engineTempC = null,
        speedKmh = null,
        gear = null,
        isNeutral = false,
    )
}

/**
 * Keine Verbindung. Steht ein Fehler oder eine laufende Wiederverbindung im Zustand, bleibt
 * dieser erhalten und nur die veralteten Messwerte verschwinden — sonst ueberschriebe der
 * Leerlauf genau die Meldung, die der Nutzer lesen soll.
 */
internal fun DashboardUiState.afterIdle(): DashboardUiState =
    if (error != null || isReconnecting) {
        // speedKmh/gear werden hier explizit mit zurueckgesetzt (anders als rpmValue/
        // engineTempC): Sonst faedelt der erste Zyklus nach einem Reconnect
        // determineGear(..., lastGear = <alter, nie neu bestaetigter Gang>, ...) ein und
        // die Hysterese "erinnert" sich an einen Zustand von vor dem Verbindungsabbruch.
        copy(
            isConnected = false,
            isStale = false,
            awaitingFirstValues = false,
            readings = emptyList(),
            speedKmh = null,
            gpsSpeedKmh = null,
            valuesLost = null,
            gear = null,
            isNeutral = false,
            oilBarColor = null,
            oilBarGreenInMillis = OIL_BAR_GREEN_HOLD_MS,
            voltageTrend = null,
            sparklines = emptyMap(),
        )
    } else {
        DashboardUiState.disconnected()
    }

/**
 * Der Zustand, sobald ein Verbindungsverlust gemeldet ist und die Wiederverbindung beginnt (der Aufrufer stoesst danach den Koordinator an — die Reihenfolge ist
 * wesentlich, s. `DashboardViewModel.startReconnecting`).
 */
internal fun DashboardUiState.afterConnectionLost(cause: ConnectionLostCause): DashboardUiState = copy(
    sparklines = emptyMap(),
    voltageTrend = null,
    // Ein gemeldeter Verbindungsverlust heisst per Definition "nicht mehr verbunden" — unabhaengig davon,
    // ob das Idle-Ereignis des Messwert-Stroms schon da ist. Seit onLinkUp() den Link vor dem ersten Wert
    // als verbunden fuehrt, bliebe isConnected sonst wahr, solange isConnectedFlow nicht gekippt ist
    // (die Reihenfolge der beiden Wege ist nicht garantiert). readings/Gang/... raeumt applyIdle() ab.
    isConnected = false,
    isStale = false,
    valuesLost = null,
    gpsSpeedKmh = null,
    awaitingFirstValues = false,
    isReconnecting = true,
    reconnectAttempt = 0,
    reconnectTotal = RECONNECT_ATTEMPT_COUNT,
    reconnectCause = cause,
)

/** Der Zustand nach einem Ereignis des [ConnectionCoordinator]. */
internal fun DashboardUiState.afterConnectionEvent(event: ConnectionEvent): DashboardUiState = when (event) {
    is ConnectionEvent.Attempting ->
        if (event.attempt == 0) {
            // Auto-Connect beim Start: Der uebrige Zustand bleibt, es gab noch keinen.
            copy(isReconnecting = true, reconnectAttempt = 0)
        } else {
            DashboardUiState(
                isReconnecting = true,
                reconnectAttempt = event.attempt,
                reconnectTotal = event.total,
                // Der Grund des Neuverbindens gilt ueber alle Versuche; der Versuch ersetzt den Zustand, nicht den Grund.
                reconnectCause = reconnectCause,
            )
        }
    // Der Spinner haelt nicht bis zum ersten Messwert an — die Verbindung steht.
    ConnectionEvent.Connected -> copy(isReconnecting = false, reconnectAttempt = 0, reconnectCause = null)
    is ConnectionEvent.Failed -> DashboardUiState.disconnected(event.error)
    // Der Nutzer hat getrennt, waehrend wiederverbunden wurde: sauber getrennt, ohne Spinner und
    // ohne den Fehler eines frueheren Versuchs. (Der Messwert-Strom meldet Idle, sobald die
    // Verbindung weg ist; applyIdle() haette Spinner und Fehler sonst stehen lassen.)
    ConnectionEvent.Aborted -> DashboardUiState()
}
