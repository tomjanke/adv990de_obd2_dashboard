package de.adv990de.obd2.ui.dashboard

/** ≤ 75°C zeigt Rot — bewusst invertierte Farbsemantik (Rot = zu kalt, nicht gefaehrlich/heiss
 *  wie sonst in der App), User-bestaetigt 2026-09-27 als inhaltlich korrekt, s. CLAUDE.md. */
private const val RED_THRESHOLD_C = 75f

/** > 80°C startet den Gruen-Timer. */
private const val GREEN_ENTER_THRESHOLD_C = 80f

/** ≤ 78°C resettet den Gruen-Timer. Zone 78-80°C: Timer haelt (Hysterese gegen Thermostat-
 *  Pendeln um den Einstiegspunkt), weder Start noch Reset. */
private const val GREEN_RESET_THRESHOLD_C = 78f

/** Ununterbrochen ueber [GREEN_ENTER_THRESHOLD_C] fuer diese Dauer -> Gruen. */
private const val GREEN_HOLD_MS = 10 * 60 * 1000L

enum class OilBarColor { Red, Yellow, Green }

/** [aboveGreenThresholdSinceMillis]: Zeitpunkt, seit dem die Temperatur ununterbrochen ueber
 *  [GREEN_ENTER_THRESHOLD_C] liegt (per Hysterese-Band, s.o.), oder null wenn der Timer nicht
 *  laeuft. */
data class OilBarTimerState(val aboveGreenThresholdSinceMillis: Long? = null)

/**
 * Aktualisiert den Gruen-Timer-Zustand fuer einen neuen Kuehlmitteltemperatur-Messwert.
 *
 * Reine Funktion ohne Android-/Coroutines-Bezug, direkt testbar (Projekt-Konvention, vgl.
 * `determineGear`/`holdGear`, `isReadingsStale`). Zeit als Parameter statt System-Uhr-Zugriff
 * innerhalb der Funktion.
 */
fun updateOilBarTimer(coolantTempC: Float, nowMillis: Long, state: OilBarTimerState): OilBarTimerState = when {
    coolantTempC > GREEN_ENTER_THRESHOLD_C ->
        state.copy(aboveGreenThresholdSinceMillis = state.aboveGreenThresholdSinceMillis ?: nowMillis)
    coolantTempC <= GREEN_RESET_THRESHOLD_C -> OilBarTimerState(aboveGreenThresholdSinceMillis = null)
    else -> state // Hysterese-Zone 78-80°C: haelt unveraendert
}

/**
 * Bestimmt die anzuzeigende Farbe aus Kuehlmitteltemperatur und Timer-Zustand. Verbindungslos
 * (kein `isLive`-Parameter hier) — der aufrufende Composable entscheidet ueber Grau bei
 * getrennter Verbindung, analog zu `RpmBar`s eigenem `isLive`-Parameter.
 */
fun oilBarColor(coolantTempC: Float, nowMillis: Long, state: OilBarTimerState): OilBarColor = when {
    coolantTempC <= RED_THRESHOLD_C -> OilBarColor.Red
    state.aboveGreenThresholdSinceMillis != null &&
        nowMillis - state.aboveGreenThresholdSinceMillis >= GREEN_HOLD_MS -> OilBarColor.Green
    else -> OilBarColor.Yellow
}
