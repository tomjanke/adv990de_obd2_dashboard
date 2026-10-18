package de.adv990de.obd2.terrain

/**
 * Die Steigung in Prozent: Hoehenaenderung / Strecke ueber ein Fenster von mindestens [BAROMETER_WINDOW_M] (ohne Barometer [GPS_WINDOW_M]). Die Strecke ist das Integral der
 * Geschwindigkeit der Fixes — das GPS liefert hier keine Positionen, und die App behaelt keine.
 *
 * Als Hoehe dient die **unkalibrierte Druckhoehe**: der Versatz der Kalibrierung springt nicht in die Steigung, und ueber 100 m ist die Wetterdrift ohne Belang. Ohne
 * Barometer die geglaettete GPS-Hoehe (deutlich ungenauer, daher das laengere Fenster). Wechselt die Quelle, beginnt das Fenster neu.
 *
 * Keine Steigung (null) unter [TerrainLimits.MIN_GRADE_SPEED_KMH], ohne Fix oder Geschwindigkeit, bei mehr als [MAX_SPEED_KMH], bei einer Luecke von mehr als [MAX_GAP_MS]
 * und bei einem Betrag ueber [MAX_GRADE_PERCENT] (Messfehler). Ein Halt verwirft das Fenster; der Verlauf fuer den Graphen ([history]) bleibt.
 *
 * Reine Zustandsmaschine ohne Android; die Zeit steht in den Fixes.
 */
class GradeEstimator {

    private class Sample(val distanceM: Double, val altitudeM: Float)

    private val window = ArrayDeque<Sample>()
    private val gps = SmoothedValue(TerrainLimits.GPS_ONLY_TAU_MS)
    private var distanceM = 0.0
    private var lastFixMs: Long? = null
    private var usingBarometer: Boolean? = null
    private var nextHistoryAtM: Double? = null
    private val historyValues = ArrayDeque<Float>()

    /** Die Steigung in % der letzten [HISTORY_LENGTH_M] Fahrstrecke, ein Wert je [HISTORY_STEP_M]; der aelteste zuerst. */
    val history: List<Float> get() = historyValues.toList()

    fun update(nowMs: Long, input: TerrainInput): Float? {
        val fix = input.fix ?: return null
        val previousMs = lastFixMs
        lastFixMs = fix.elapsedMs
        val speed = fix.speedKmh
        if (speed == null || !speed.isFinite() || speed < 0f || speed > MAX_SPEED_KMH) {
            window.clear()
            return null
        }
        if (speed < TerrainLimits.MIN_GRADE_SPEED_KMH) {
            window.clear()
            return null
        }
        val dtMs = if (previousMs == null) null else fix.elapsedMs - previousMs
        if (dtMs != null && dtMs <= 0) return null   // doppelter oder rueckwaerts laufender Fix: nichts zu rechnen
        if (dtMs == null || dtMs > MAX_GAP_MS) window.clear() else distanceM += speed / 3.6 * dtMs / 1000.0

        val pressure = plausiblePressureHpa(input.pressureHpa)
        val barometer = pressure != null
        if (usingBarometer != barometer) {
            window.clear()
            gps.reset()
            usingBarometer = barometer
        }
        val altitude = if (pressure != null) {
            pressureAltitudeM(pressure)
        } else {
            usableGpsAltitude(nowMs, fix)?.let { gps.update(nowMs, it) } ?: gps.value
        } ?: return null

        window.addLast(Sample(distanceM, altitude))
        val needM = if (barometer) BAROMETER_WINDOW_M else GPS_WINDOW_M
        while (window.size > 1 && distanceM - window[1].distanceM >= needM) window.removeFirst()
        val start = window.first()
        val spanM = distanceM - start.distanceM
        if (spanM < needM) return null
        val grade = ((altitude - start.altitudeM) / spanM * 100.0).toFloat()
        if (!grade.isFinite() || kotlin.math.abs(grade) > MAX_GRADE_PERCENT) return null
        appendToHistory(grade)
        return grade
    }

    private fun appendToHistory(grade: Float) {
        val target = nextHistoryAtM ?: distanceM
        if (distanceM < target) return
        historyValues.addLast(grade)
        while (historyValues.size > HISTORY_POINTS) historyValues.removeFirst()
        var next = target + HISTORY_STEP_M
        if (next <= distanceM) next = distanceM + HISTORY_STEP_M   // bei hoher Geschwindigkeit uebersprungene Marken nicht nachholen
        nextHistoryAtM = next
    }

    fun reset() {
        window.clear()
        gps.reset()
        distanceM = 0.0
        lastFixMs = null
        usingBarometer = null
        nextHistoryAtM = null
        historyValues.clear()
    }

    internal companion object {
        const val BAROMETER_WINDOW_M = 100.0
        const val GPS_WINDOW_M = 300.0
        const val MAX_SPEED_KMH = 250f
        const val MAX_GAP_MS = 10_000L
        const val MAX_GRADE_PERCENT = 50f
        const val HISTORY_STEP_M = 25.0
        const val HISTORY_POINTS = 40
        const val HISTORY_LENGTH_M = HISTORY_STEP_M * HISTORY_POINTS
    }
}
