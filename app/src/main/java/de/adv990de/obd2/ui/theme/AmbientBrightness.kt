package de.adv990de.obd2.ui.theme

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Die drei Helligkeitsstufen des Bildschirms (Nutzerwunsch 2026-11-18: bei Daemmerung und Dunkelheit dimmen, weil sich das Telefon im Visier spiegelt).
 * [windowBrightness] ist der Wert fuer `WindowManager.LayoutParams.screenBrightness` (0 bis 1). **Die Zahlen sind meine Schaetzung, nicht gemessen** — sie sind
 * Konstanten, die man nach einer Fahrt in der Daemmerung nachstellt (der App-Log nennt bei jedem Wechsel den gemessenen Lux-Wert).
 */
enum class BrightnessLevel(val windowBrightness: Float) {
    DAY(1f),
    DUSK(0.5f),
    NIGHT(0.1f),
}

/**
 * Schwellen und Takt der automatischen Helligkeit. Lux-Werte nach der ueblichen Groessenordnung (bedeckter Tag ab etwa 1 000 lx, Daemmerung zehn bis einige
 * hundert, Nacht mit Strassenlampen unter 50) — **nicht an diesem Telefon gemessen**; der Sensor sitzt zudem je nach Halterung hinter dem Visier.
 * Zwischen Betreten und Verlassen einer Stufe liegt Abstand (Hysterese), damit ein Wert an der Grenze nicht flackert.
 */
object AmbientBrightness {
    /** Ab diesem Wert wird es Tag (aus Daemmerung oder Nacht). */
    const val DAY_ENTER_LUX = 1_200f

    /** Unter diesem Wert verlaesst die Anzeige den Tag. */
    const val DAY_LEAVE_LUX = 800f

    /** Unter diesem Wert wird es Nacht (aus Tag oder Daemmerung). */
    const val NIGHT_ENTER_LUX = 30f

    /** Ab diesem Wert verlaesst die Anzeige die Nacht. */
    const val NIGHT_LEAVE_LUX = 50f

    /** Abstand der Auswertungen: einmal je Sekunde, unabhaengig davon, wie oft der Sensor meldet (er meldet nur bei Aenderung). */
    const val TICK_MS = 1_000L

    /** Die Zahl der letzten Auswertungen, aus denen der Median gebildet wird (ungerade): eine Stufe wechselt, wenn mindestens 3 der letzten 5 Sekunden dafuer sprechen. */
    const val WINDOW_TICKS = 5
}

/**
 * Die Stufe zu einem (schon geglaetteten) Lux-Wert, mit Hysterese: [previous] bestimmt, ob die Schwelle zum Betreten oder zum Verlassen einer Stufe gilt.
 * Ein Wert, der keine Zahl oder negativ ist, ist keine Messung und laesst [previous] stehen.
 */
fun brightnessLevelFor(lux: Float, previous: BrightnessLevel): BrightnessLevel {
    if (!lux.isFinite() || lux < 0f) return previous
    val dayFrom = if (previous == BrightnessLevel.DAY) AmbientBrightness.DAY_LEAVE_LUX else AmbientBrightness.DAY_ENTER_LUX
    val nightBelow = if (previous == BrightnessLevel.NIGHT) AmbientBrightness.NIGHT_LEAVE_LUX else AmbientBrightness.NIGHT_ENTER_LUX
    return when {
        lux >= dayFrom -> BrightnessLevel.DAY
        lux < nightBelow -> BrightnessLevel.NIGHT
        else -> BrightnessLevel.DUSK
    }
}

/** Die Stufe samt dem Lux-Wert (Median), aus dem sie entstand; [medianLux] ist `null`, wenn es keine Messung gibt (Funktion aus). */
data class BrightnessReading(val level: BrightnessLevel, val medianLux: Float?)

/**
 * Glaettet die Sensorwerte zur Stufe. Der Sensor meldet nur **bei Aenderung** — bliebe ein Spitzenwert (ein Blitz, eine Lampe) der letzte Wert, bevor der Sensor
 * wieder meldet, haette ein Mittel ueber Meldungen ihn ewig im Fenster. Deshalb gilt der letzte Wert als gehalten, und [onTick] schreibt ihn **einmal je Sekunde**
 * in ein Fenster der letzten [AmbientBrightness.WINDOW_TICKS] Werte; die Stufe folgt dem Median (ein Ausreisser kippt sie nicht, ein echter Wechsel nach etwa
 * drei Sekunden). **Der erste Wert gilt sofort** (kein Warten beim Start der Anzeige). Beide Methoden laufen in einer Coroutine des Sammlers; `@Synchronized`
 * schuetzt nur davor, dass ein Aufrufer mit mehreren Threads sie mischt.
 */
class AmbientBrightnessTracker {
    private val window = ArrayDeque<Float>()
    private var held: Float? = null
    private var level = BrightnessLevel.DAY

    /** Eine Messung des Sensors. Liefert die Stufe nur beim **ersten** gueltigen Wert (sofort), sonst `null`; ungueltige Werte (NaN, negativ) zaehlen nicht. */
    @Synchronized
    fun onSample(lux: Float): BrightnessReading? {
        if (!lux.isFinite() || lux < 0f) return null
        val first = held == null
        held = lux
        if (!first) return null
        window.clear()
        repeat(AmbientBrightness.WINDOW_TICKS) { window.addLast(lux) }
        return evaluate()
    }

    /** Eine Auswertung je Sekunde; `null`, solange es noch keine Messung gibt. */
    @Synchronized
    fun onTick(): BrightnessReading? {
        val value = held ?: return null
        window.addLast(value)
        while (window.size > AmbientBrightness.WINDOW_TICKS) window.removeFirst()
        return evaluate()
    }

    private fun evaluate(): BrightnessReading {
        val median = window.sorted()[window.size / 2]
        level = brightnessLevelFor(median, level)
        return BrightnessReading(level, median)
    }
}

/**
 * Die Zeile fuer den App-Log bei einem Wechsel der Stufe (englisch wie alle Log-Zeilen). Der Lux-Wert ist der **Median** der letzten Sekunden, aus dem die Stufe
 * entstand; ohne Wert (Funktion ausgeschaltet) steht „dimming off“. Daran stellt man die Schwellen nach einer Fahrt nach.
 */
fun brightnessLogMessage(from: BrightnessLevel, reading: BrightnessReading): String {
    val why = reading.medianLux?.let { "median ${it.toInt()} lx" } ?: "dimming off"
    return "Brightness level ${from.name} -> ${reading.level.name} ($why)"
}

/**
 * Die Stufe als Fluss: ist [enabled] aus, immer [BrightnessLevel.DAY] (volle Helligkeit wie vor dieser Funktion), ist sie an, folgt die Stufe dem Lichtsensor
 * ([lux], ein kalter Fluss — der Sensor ist nur angemeldet, solange gesammelt wird und die Funktion an ist). Ohne Sensorwerte bleibt es bei DAY. Beim Einschalten
 * beginnt die Glaettung neu. Der Fluss meldet nur einen **Wechsel der Stufe** (mit dem Lux-Wert, bei dem er geschah), nicht jede Sekunde.
 */
fun ambientBrightness(
    enabled: Flow<Boolean>,
    lux: () -> Flow<Float>,
    tickMs: Long = AmbientBrightness.TICK_MS,
): Flow<BrightnessReading> = enabled.distinctUntilChanged().flatMapLatest { on ->
    if (!on) {
        flowOf(BrightnessReading(BrightnessLevel.DAY, null))
    } else {
        channelFlow {
            val tracker = AmbientBrightnessTracker()
            launch { lux().collect { value -> tracker.onSample(value)?.let { send(it) } } }
            launch {
                while (true) {
                    delay(tickMs)
                    tracker.onTick()?.let { send(it) }
                }
            }
        }
    }
}.distinctUntilChangedBy { it.level }
