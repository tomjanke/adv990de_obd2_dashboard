package de.adv990de.obd2.fuel

/**
 * Rechnet GPS-Geschwindigkeiten ueber die Zeit in gefahrene Kilometer um — der Streckenzaehler der Tankanzeige (Nutzerwunsch 2026-10-15).
 *
 * Die [GpsSpeedSource][de.adv990de.obd2.location.GpsSpeedSource] liefert nur die Geschwindigkeit, keine Positionen; die Strecke ist deshalb das Integral
 * (Trapez zwischen zwei Messungen). Gezaehlt wird nur, was belegt ist:
 * - eine unbekannte oder unplausible Geschwindigkeit (null, NaN, unendlich, negativ, ueber [MAX_PLAUSIBLE_SPEED_KMH]) beendet das Intervall,
 * - zwischen zwei Messungen darf hoechstens [MAX_GAP_MS] liegen — ein Tunnel oder eine Funkstille wird nicht ueberbrueckt (zu wenig statt erfunden),
 * - unter [MIN_COUNTED_SPEED_KMH] gilt das GPS-Rauschen im Stand als Stillstand (sonst wuchse der Zaehler bei langen Pausen).
 *
 * Reine Klasse ohne Android-Bezug; die Zeit kommt als monotone Uhr (`elapsedRealtime`) herein.
 */
internal class DistanceIntegrator {

    private var lastSpeedKmh = 0f
    private var lastAtMillis: Long? = null

    /** Beendet das laufende Intervall (Verbindung weg, Werte veraltet): die naechste Messung beginnt ein neues und zaehlt nichts. */
    fun reset() {
        lastAtMillis = null
    }

    /** Meldet die GPS-Geschwindigkeit [speedKmh] zum Zeitpunkt [atMillis] und liefert die seit der vorigen Messung gefahrenen Kilometer (>= 0). */
    fun sample(speedKmh: Float?, atMillis: Long): Float {
        val speed = speedKmh?.takeIf { it.isFinite() && it >= 0f && it <= MAX_PLAUSIBLE_SPEED_KMH }
        if (speed == null) {
            lastAtMillis = null
            return 0f
        }
        val counted = if (speed < MIN_COUNTED_SPEED_KMH) 0f else speed
        val previousAt = lastAtMillis
        val previousSpeed = lastSpeedKmh
        lastAtMillis = atMillis
        lastSpeedKmh = counted
        if (previousAt == null) return 0f
        val elapsedMs = atMillis - previousAt
        if (elapsedMs <= 0L || elapsedMs > MAX_GAP_MS) return 0f
        // km/h * ms / 3.600.000 ms je Stunde = km
        return (previousSpeed + counted) / 2f * elapsedMs / MILLIS_PER_HOUR
    }

    internal companion object {
        /** Eine groessere Luecke zwischen zwei Messungen wird nicht gezaehlt. */
        const val MAX_GAP_MS = 10_000L

        /** Schneller ist kein Fahrzeug dieser App; ein groesserer Wert ist ein Messfehler. */
        const val MAX_PLAUSIBLE_SPEED_KMH = 250f

        /** Darunter ist die GPS-Geschwindigkeit Rauschen im Stand. */
        const val MIN_COUNTED_SPEED_KMH = 2f

        private const val MILLIS_PER_HOUR = 3_600_000f
    }
}
