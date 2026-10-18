package de.adv990de.obd2.terrain

import kotlin.math.max

/**
 * Die Hoehe, die die Kachel gross zeigt: **Druckhoehe + Versatz**. Das Barometer bildet Aenderungen sehr fein ab, driftet aber absolut mit dem Wetter; die GPS-Hoehe ist
 * absolut, aber grob. Der Versatz ist der gleitende Mittelwert von (GPS-Hoehe − Druckhoehe) — zuerst ein laufender Mittelwert (schnelle Annaeherung, Rauschen mittelt sich
 * heraus), danach mit der Zeitkonstante [CALIBRATION_TAU_MS]. Nur Proben mit ausreichender Vertikalgenauigkeit und frischem Fix zaehlen ([usableGpsAltitude]).
 *
 * **Ohne Kalibrierung gibt es keine Hoehe** (null): eine unkalibrierte Druckhoehe waere eine falsche Zahl. Gibt es [MAX_UNCALIBRATED_MS] lang keine brauchbare Probe, wird die Hoehe
 * wieder unbekannt und die naechste Probe kalibriert neu (die Drift ueber so lange Zeit ist nicht mehr abschaetzbar). **Ohne Barometer** zeigt sie die geglaettete GPS-Hoehe.
 *
 * Reine Zustandsmaschine ohne Android; die Zeit kommt als Parameter (`elapsedRealtime`-Millisekunden).
 */
class ElevationEstimator {

    private var offsetM = 0f
    private var calibrationSamples = 0
    private var lastCalibrationMs: Long? = null
    private val gps = SmoothedValue(TerrainLimits.GPS_ONLY_TAU_MS)

    /** Die Hoehe in Metern nach diesem Schritt, null wenn unbekannt. */
    fun update(nowMs: Long, input: TerrainInput): Float? {
        val gpsAltitude = usableGpsAltitude(nowMs, input.fix)
        val pressureAltitude = plausiblePressureHpa(input.pressureHpa)?.let { pressureAltitudeM(it) }
        if (gpsAltitude != null) {
            // Die geglaettete GPS-Hoehe laeuft immer mit: faellt das Barometer aus, ist sie schon warm.
            gps.update(nowMs, gpsAltitude)
            if (pressureAltitude != null) calibrate(nowMs, gpsAltitude - pressureAltitude)
        }
        if (pressureAltitude != null) {
            val calibratedAt = lastCalibrationMs
            if (calibratedAt != null && nowMs - calibratedAt <= MAX_UNCALIBRATED_MS) return pressureAltitude + offsetM
        }
        return if (nowMs - gps.lastUpdateMs <= MAX_UNCALIBRATED_MS) gps.value else null
    }

    private fun calibrate(nowMs: Long, sample: Float) {
        val last = lastCalibrationMs
        if (last == null || nowMs - last > MAX_UNCALIBRATED_MS) {
            offsetM = sample
            calibrationSamples = 1
        } else {
            val slow = (nowMs - last).toFloat() / CALIBRATION_TAU_MS
            val gain = max(slow, 1f / (calibrationSamples + 1)).coerceAtMost(1f)
            offsetM += gain * (sample - offsetM)
            calibrationSamples++
        }
        lastCalibrationMs = nowMs
    }

    fun reset() {
        offsetM = 0f
        calibrationSamples = 0
        lastCalibrationMs = null
        gps.reset()
    }

    internal companion object {
        /** Zeitkonstante des langsamen Nachfuehrens des Versatzes. */
        const val CALIBRATION_TAU_MS = 120_000L

        /** So lange traegt eine Kalibrierung ohne neue brauchbare Probe (zehn Minuten). */
        const val MAX_UNCALIBRATED_MS = 600_000L
    }
}
