package de.adv990de.obd2.ui.weather

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * So lange bleibt die zuletzt erfolgreich abgerufene Aussentemperatur nach fehlgeschlagenen Abrufen
 * stehen (User-Entscheidung 2026-09-27) — dieselbe "Anzeige-Persistenz" wie bei der Ganganzeige
 * (`holdGear`): Ein Funkloch oder ein einzelner Server-Fehler soll die Anzeige nicht sofort leeren.
 * Die Aussentemperatur aendert sich in einer halben Stunde selten so stark, dass der letzte Wert
 * irrefuehrend waere; danach ist er es womoeglich, also verschwindet er.
 */
val OUTSIDE_TEMP_HOLD: Duration = 30.minutes

/**
 * Zuletzt erfolgreich abgerufene Aussentemperatur [tempC] und ihr Abrufzeitpunkt [refreshedAt] —
 * auf derselben monotonen Zeitachse wie das `now` der Aufrufe (im ViewModel: Abstand zu einer
 * einmal genommenen `TimeSource`-Marke), keine Wanduhrzeit.
 */
data class HeldOutsideTemp(val tempC: Float, val refreshedAt: Duration)

/**
 * Ein Abrufzyklus: Ein neuer Wert ([fetchedC] != null) gewinnt immer und startet das Fenster neu.
 * Ohne neuen Wert — Abruf fehlgeschlagen oder (beim Neustart des Pollings) noch nicht erfolgt —
 * bleibt [held] stehen, solange seit dessen Abruf weniger als [OUTSIDE_TEMP_HOLD] vergangen ist;
 * ab genau 30 Minuten ist er abgelaufen (`>=`, dieselbe Konvention wie `isReadingsStale`).
 *
 * Reine Funktion ohne Android-/Coroutines-Bezug, direkt testbar (Projekt-Konvention, vgl.
 * `holdGear`, `updateOilBarTimer`): Zeit als Parameter, kein Uhrzugriff in der Funktion. Anzeigen
 * wird der Aufrufer `result?.tempC`.
 */
fun holdOutsideTemp(fetchedC: Float?, now: Duration, held: HeldOutsideTemp?): HeldOutsideTemp? = when {
    fetchedC != null -> HeldOutsideTemp(tempC = fetchedC, refreshedAt = now)
    held != null && now - held.refreshedAt < OUTSIDE_TEMP_HOLD -> held
    else -> null
}
