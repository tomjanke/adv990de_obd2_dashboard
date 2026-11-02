package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.model.SensorValue

/** Anzahl der Punkte je Sparkline (das Fenster, das eine Kachel zeigt). */
internal const val SPARKLINE_WINDOW = 60

/**
 * Die Sparkline-Puffer des Dashboards: je PID die letzten [window] Messwerte. Herausgeloest aus `DashboardViewModel`.
 *
 * Nur ein NEUER Messwert bekommt einen Punkt ([shouldAppendSparklineSample], B30): das GPS-Zwischenupdate wiederholt die letzten
 * OBD-Werte, ohne diese Regel entstuenden Duplikate (Treppenkurve). Dafuer merkt sich der Puffer den Zeitstempel des letzten Punkts je PID.
 *
 * Eine Sparkline gehoert zu EINER Verbindung ([clear], UID-16): nach einem Verlust haengte der naechste Punkt sonst an die Kurve von
 * vorher, als haette es keine Luecke gegeben. Der Zeitstempel geht mit — sonst verschluckte der Mindestabstand den ersten Punkt der neuen
 * Verbindung, wenn die Geraeteuhr nur wenig weitergelaufen ist.
 */
internal class SparklineBuffer(private val window: Int = SPARKLINE_WINDOW) {

    private val points = mutableMapOf<String, ArrayDeque<Float>>()

    /** Zeitstempel des letzten Sparkline-Punkts je PID. */
    private val lastTimestamp = mutableMapOf<String, Long>()

    /** Der zuletzt gebaute [snapshot]; `null`, sobald sich etwas geaendert hat. Jedes GPS-Zwischenupdate fragt den Stand ab, ohne dass ein Punkt dazukommt. */
    private var cached: Map<String, List<Float>>? = null

    fun append(values: List<SensorValue>) {
        values.forEach { sv ->
            if (!shouldAppendSparklineSample(lastTimestamp[sv.pid.id], sv.timestamp)) return@forEach
            lastTimestamp[sv.pid.id] = sv.timestamp
            val deque = points.getOrPut(sv.pid.id) { ArrayDeque(window) }
            if (deque.size >= window) deque.removeFirst()
            deque.addLast(sv.value)
            cached = null
        }
    }

    /**
     * Eine Kopie des aktuellen Standes, je PID aelteste zuerst. Ohne Aenderung seit dem letzten Aufruf dieselbe Instanz (die Kopie wird nie veraendert, nur durch eine neue ersetzt).
     */
    fun snapshot(): Map<String, List<Float>> = cached ?: points.mapValues { (_, deque) -> deque.toList() }.also { cached = it }

    fun clear() {
        points.clear()
        lastTimestamp.clear()
        cached = null
    }
}
