package de.adv990de.obd2.ui.dashboard

/**
 * Mindestabstand zweier Sparkline-Punkte desselben PIDs. Ein OBD-Zyklus dauert rund eine Sekunde, ein GPS-Fix kommt
 * mit bis zu 5 Hz: Ohne diese Grenze fuellte die GPS-Geschwindigkeit das 60-Punkte-Fenster in 12 s statt in einer Minute.
 */
internal const val SPARKLINE_MIN_INTERVAL_MS = 800L

/**
 * Bekommt ein Messwert mit [timestampMs] einen neuen Punkt in der Sparkline, wenn der letzte Punkt dieses PIDs von
 * [lastTimestampMs] stammt?
 *
 * Bis 1.24.5 nahm der Puffer JEDEN Wert, den `applyReadings` bekam — auch die Wiederholungen: `ReadingsPipeline.gpsRefresh`
 * stellt bei jedem GPS-Fix zwischen zwei OBD-Zyklen die letzten OBD-Werte erneut zu (dieselben Messwerte, derselbe
 * Zeitstempel). Folge: eine Treppenkurve und ein Fenster von ~12 s statt ~60 s (B30). Ein Punkt entsteht deshalb nur fuer
 * einen NEUEN Messwert (neuerer Zeitstempel), und frueshtens nach [minIntervalMs].
 *
 * Ein Zeitstempel VOR dem letzten (die Wanduhr wurde zurueckgestellt) zaehlt als neu — sonst fror der Graph bis zum
 * Aufholen der Uhr ein. Reine Funktion, wie `isMoving` und `determineGear`.
 */
internal fun shouldAppendSparklineSample(
    lastTimestampMs: Long?,
    timestampMs: Long,
    minIntervalMs: Long = SPARKLINE_MIN_INTERVAL_MS,
): Boolean = lastTimestampMs == null ||
    timestampMs < lastTimestampMs ||
    timestampMs - lastTimestampMs >= minIntervalMs
