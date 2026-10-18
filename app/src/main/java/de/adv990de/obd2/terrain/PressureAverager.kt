package de.adv990de.obd2.terrain

/**
 * Mittelt die Druckproben zwischen zwei GPS-Fixes: das Barometer meldet mehrmals je Sekunde, die Rechnung bekommt eine Probe je Fix, und der Mittelwert
 * von rund fuenf Proben rauscht weniger als eine einzelne. Kommt zwischen zwei Fixes keine neue Probe, gilt der letzte Mittelwert noch [MAX_AGE_MS] — danach
 * null: ein Sensor, der nichts mehr liefert (Drosselung im Hintergrund, Ausfall), darf die Hoehe nicht still einfrieren. Mehrere Threads: die Rueckmeldungen
 * von Sensor und Standort kommen vom Hauptthread, ein Test ruft vom Testthread — deshalb synchronisiert.
 */
internal class PressureAverager {

    private var sum = 0.0
    private var count = 0
    private var lastMean: Float? = null
    private var lastSampleMs = 0L

    @Synchronized
    fun add(nowMs: Long, hPa: Float) {
        if (!hPa.isFinite()) return
        sum += hPa
        count++
        lastSampleMs = nowMs
    }

    /** Der Mittelwert der Proben seit dem letzten Aufruf (und leert sie); ohne neue Probe der letzte Mittelwert, wenn er jung genug ist. */
    @Synchronized
    fun mean(nowMs: Long): Float? {
        if (count > 0) {
            lastMean = (sum / count).toFloat()
            sum = 0.0
            count = 0
            return lastMean
        }
        return lastMean?.takeIf { nowMs - lastSampleMs <= MAX_AGE_MS }
    }

    internal companion object {
        const val MAX_AGE_MS = 5_000L
    }
}
