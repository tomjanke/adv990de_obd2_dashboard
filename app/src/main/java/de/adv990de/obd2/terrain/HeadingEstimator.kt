package de.adv990de.obd2.terrain

/**
 * Die Peilung in Grad (0 bis unter 360): der **GPS-Kurs ueber Grund**, kein Kompass. Er gilt ab [MIN_SPEED_KMH] (die Rauschgrenze von `determineGear`) und bei gemeldeter Kursgenauigkeit
 * bis [MAX_ACCURACY_DEG]; eine fehlende Genauigkeit entwertet den Kurs nicht. Darunter, ohne Fix oder ohne Kurs wird die letzte gueltige Peilung [HOLD_MS] gehalten, danach null.
 * Das Magnetometer bleibt aussen vor (Stahl, Magnete, Halterung am Motorrad).
 */
class HeadingEstimator {

    private var heldDeg: Float? = null
    private var heldAtMs = 0L

    fun update(nowMs: Long, fix: GpsFix?): Float? {
        val course = validCourse(fix)
        if (course != null) {
            heldDeg = course
            heldAtMs = nowMs
            return course
        }
        val held = heldDeg
        if (held != null && nowMs - heldAtMs <= HOLD_MS) return held
        heldDeg = null
        return null
    }

    private fun validCourse(fix: GpsFix?): Float? {
        if (fix == null) return null
        val speed = fix.speedKmh ?: return null
        if (!speed.isFinite() || speed < MIN_SPEED_KMH) return null
        val bearing = fix.bearingDeg?.takeIf { it.isFinite() } ?: return null
        val accuracy = fix.bearingAccuracyDeg
        if (accuracy != null && !(accuracy <= MAX_ACCURACY_DEG)) return null
        return (((bearing.toDouble() % 360.0) + 360.0) % 360.0).toFloat()
    }

    fun reset() {
        heldDeg = null
        heldAtMs = 0
    }

    internal companion object {
        const val MIN_SPEED_KMH = 8f
        const val MAX_ACCURACY_DEG = 45f
        const val HOLD_MS = 30_000L
    }
}
