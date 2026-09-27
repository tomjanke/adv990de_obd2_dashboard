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

/**
 * Laengste tolerierte Pause zwischen zwei Messwerten, ueber die hinweg der Gruen-Timer
 * weiterlaeuft. Liegt deutlich ueber dem Default des OBD-Watchdogs (20 s), damit ein normaler
 * Reconnect (Watchdog + erster Backoff-Schritt + Verbindungsaufbau) die Zaehlung nicht verliert —
 * aber weit unter "App war stundenlang zu": Nach einer laengeren Pause ist unbekannt, was die
 * Temperatur dazwischen tat, also beginnt die Auswertung von vorn (s. [updateOilBarTimer]).
 */
private const val MAX_SAMPLE_GAP_MS = 60_000L

enum class OilBarColor { Red, Yellow, Green }

/**
 * [aboveGreenThresholdSinceMillis]: Zeitpunkt, seit dem die Temperatur ununterbrochen ueber
 * [GREEN_ENTER_THRESHOLD_C] liegt (per Hysterese-Band, s.o.), oder null wenn der Timer nicht
 * laeuft.
 *
 * [lastSampleAtMillis]: Zeitpunkt des zuletzt verarbeiteten Messwerts, oder null vor dem ersten.
 * Grundlage fuer das Luecken-Limit [MAX_SAMPLE_GAP_MS].
 *
 * Beide Zeitpunkte stammen aus derselben Uhr wie das `nowMillis` der Aufrufe (im ViewModel die
 * monotone `SystemClock.elapsedRealtime()`), nicht aus den Zeitstempeln der Messwerte.
 */
data class OilBarTimerState(
    val aboveGreenThresholdSinceMillis: Long? = null,
    val lastSampleAtMillis: Long? = null,
)

/**
 * Aktualisiert den Gruen-Timer-Zustand fuer einen neuen Kuehlmitteltemperatur-Messwert.
 *
 * Zwei Schritte:
 * 1. Luecken-Limit: Liegt der vorige Messwert mehr als [MAX_SAMPLE_GAP_MS] zurueck, wird so
 *    weitergerechnet, als gaebe es noch keinen Zustand — ein laufender Timer verfaellt, statt nach
 *    einem langen Verbindungsabbruch (oder stundenlang geschlossener App) mit einem einzigen neuen
 *    Messwert seinen uralten Startzeitpunkt fortzusetzen und sofort Gruen zu zeigen.
 * 2. Die Schwellwert-Logik auf diesem (ggf. frischen) Zustand: > 80 °C startet den Timer bzw.
 *    laesst ihn laufen, ≤ 78 °C setzt ihn zurueck, 78–80 °C haelt ihn unveraendert.
 *
 * [OilBarTimerState.lastSampleAtMillis] wird in jedem Fall auf [nowMillis] gesetzt.
 *
 * Reine Funktion ohne Android-/Coroutines-Bezug, direkt testbar (Projekt-Konvention, vgl.
 * `determineGear`/`holdGear`, `isReadingsStale`). Zeit als Parameter statt System-Uhr-Zugriff
 * innerhalb der Funktion.
 */
fun updateOilBarTimer(coolantTempC: Float, nowMillis: Long, state: OilBarTimerState): OilBarTimerState {
    val lastSample = state.lastSampleAtMillis
    val base = if (lastSample != null && nowMillis - lastSample > MAX_SAMPLE_GAP_MS) OilBarTimerState() else state
    val aboveSince = when {
        coolantTempC > GREEN_ENTER_THRESHOLD_C -> base.aboveGreenThresholdSinceMillis ?: nowMillis
        coolantTempC <= GREEN_RESET_THRESHOLD_C -> null
        else -> base.aboveGreenThresholdSinceMillis // Hysterese-Zone 78-80°C: haelt unveraendert
    }
    return OilBarTimerState(aboveGreenThresholdSinceMillis = aboveSince, lastSampleAtMillis = nowMillis)
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
