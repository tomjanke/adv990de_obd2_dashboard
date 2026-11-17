package de.adv990de.obd2.track

/**
 * Die Werte des Filters. **Mein Vorschlag, ungemessen** (kein Wert stammt aus einer Aufzeichnung auf dem Motorrad); der Test haelt sie fest, damit eine Aenderung
 * bewusst geschieht.
 */
internal object TrackFilterLimits {
    /** Ein Fix mit schlechterer Genauigkeit wird verworfen. */
    const val MAX_ACCURACY_M = 30f

    /** Mindestabstand zum letzten Punkt. */
    const val MIN_DISTANCE_M = 5.0

    /** Nach [INTERVAL_MS] genuegt dieser kleinere Abstand (langsame Fahrt, Gelaende). */
    const val MIN_DISTANCE_AFTER_INTERVAL_M = 2.0

    const val INTERVAL_MS = 10_000L

    /** Darunter ist das Fahrzeug fuer die Aufzeichnung "steht" (das GPS-Rauschen im Stand soll keine Punkte machen). */
    const val MIN_SPEED_KMH = 2f
}

/**
 * Entscheidet, ob ein Fix ein Punkt des Tracks wird.
 *
 * - Der **erste** Fix zaehlt (auch im Stand: der Start ist der wichtigste Punkt), wenn seine Genauigkeit reicht.
 * - Danach: Genauigkeit hoechstens [TrackFilterLimits.MAX_ACCURACY_M]; im Stand (Geschwindigkeit bekannt und unter [TrackFilterLimits.MIN_SPEED_KMH]) nie; sonst
 *   mindestens [TrackFilterLimits.MIN_DISTANCE_M] vom letzten Punkt oder mindestens [TrackFilterLimits.INTERVAL_MS] danach und [TrackFilterLimits.MIN_DISTANCE_AFTER_INTERVAL_M].
 * - Eine unbekannte Genauigkeit oder Geschwindigkeit sperrt nichts (sonst nahme ein Geraet ohne diese Angaben nie etwas auf).
 * - Ein verworfener Fix verschiebt nichts: Bezug ist immer der letzte **angenommene** Punkt.
 *
 * Reine Klasse ohne Android; die Zeit [nowMs] ist die monotone Uhr des Aufrufers, nicht die Wanduhr im Punkt.
 */
class TrackPointFilter {

    private var last: TrackPoint? = null
    private var lastAtMs = 0L

    /** Beginnt neu: der naechste Fix ist wieder ein erster. */
    fun reset() {
        last = null
    }

    fun accept(fix: TrackPoint, nowMs: Long): Boolean {
        val accuracy = fix.accuracyM
        if (accuracy != null && accuracy > TrackFilterLimits.MAX_ACCURACY_M) return false
        val previous = last
        if (previous == null) return take(fix, nowMs)
        val speed = fix.speedKmh
        if (speed != null && speed < TrackFilterLimits.MIN_SPEED_KMH) return false
        val distance = distanceM(previous, fix)
        val elapsed = nowMs - lastAtMs
        val enough = distance >= TrackFilterLimits.MIN_DISTANCE_M ||
            (elapsed >= TrackFilterLimits.INTERVAL_MS && distance >= TrackFilterLimits.MIN_DISTANCE_AFTER_INTERVAL_M)
        return enough && take(fix, nowMs)
    }

    private fun take(fix: TrackPoint, nowMs: Long): Boolean {
        last = fix
        lastAtMs = nowMs
        return true
    }
}
