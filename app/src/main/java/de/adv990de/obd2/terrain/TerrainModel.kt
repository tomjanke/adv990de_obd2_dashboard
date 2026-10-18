package de.adv990de.obd2.terrain

import kotlin.math.floor
import kotlin.math.pow

/**
 * Ein GPS-Fix, auf das reduziert, was die Gelaende-Kachel braucht — alle Felder optional: ein Fix kann jedes einzeln schuldig bleiben, und "fehlt" ist
 * etwas anderes als 0. [elapsedMs] ist die monotone Zeit des Fixes (`Location.elapsedRealtimeNanos`), nicht die Wanduhr.
 */
data class GpsFix(
    val elapsedMs: Long,
    val speedKmh: Float?,
    val altitudeM: Float?,
    val verticalAccuracyM: Float?,
    val bearingDeg: Float?,
    val bearingAccuracyDeg: Float?,
)

/** Was ein Schritt der Gelaende-Rechnung bekommt: der juengste Fix (null = keiner, oder veraltet) und der mittlere Luftdruck seit dem letzten Fix. */
data class TerrainInput(val fix: GpsFix?, val pressureHpa: Float?)

/** Was die Kachel zeigt; jedes Feld einzeln null, wenn es keinen Wert gibt. [gradeHistory] = Steigung in % ueber die letzten 1000 m, aeltester zuerst. */
data class TerrainDisplay(
    val altitudeM: Float?,
    val gradePercent: Float?,
    val headingDeg: Float?,
    val gradeHistory: List<Float>,
)

/** Die acht Sektoren der Windrose, im Uhrzeigersinn ab Nord. Die Reihenfolge ist die der Winkel: [compassPoint] rechnet damit. */
enum class CompassPoint { N, NE, E, SE, S, SW, W, NW }

/**
 * Der Sektor einer Peilung in Grad: acht Sektoren zu je 45 Grad, Nord von 337,5 bis 22,5 Grad; eine Grenze gehoert zum naechsten Sektor im Uhrzeigersinn.
 * Winkel ausserhalb von 0 bis 360 werden eingerollt (360 = Nord, -10 = 350). Ein nicht endlicher Wert ist keine Peilung (null).
 */
fun compassPoint(headingDeg: Float): CompassPoint? {
    if (!headingDeg.isFinite()) return null
    val normalized = ((headingDeg.toDouble() % 360.0) + 360.0) % 360.0
    return CompassPoint.entries[floor((normalized + 22.5) / 45.0).toInt() % CompassPoint.entries.size]
}

/**
 * Hoehe aus dem Luftdruck nach der Standardatmosphaere (Bezugsdruck 1013,25 hPa) — dieselbe Formel wie `SensorManager.getAltitude`, ohne Android, damit sie
 * im JVM-Test steht. **Das ist keine Hoehe ueber dem Meer:** der Druck am Meer schwankt mit dem Wetter um mehrere hPa (rund 8 bis 9 m je hPa); als absolute
 * Hoehe taugt der Wert erst mit einem Versatz zur GPS-Hoehe ([ElevationEstimator]), fuer Hoehenaenderungen ueber kurze Strecken ([GradeEstimator]) genuegt er so.
 */
fun pressureAltitudeM(hPa: Float): Float =
    (44330.0 * (1.0 - (hPa / STANDARD_SEA_LEVEL_HPA).toDouble().pow(1.0 / 5.255))).toFloat()

internal const val STANDARD_SEA_LEVEL_HPA = 1013.25f

/** Ein Luftdruck, der auf der Erde vorkommen kann (Gipfel des Everest rund 340 hPa); alles andere ist ein Sensorfehler und zaehlt nicht. */
internal fun plausiblePressureHpa(hPa: Float?): Float? = hPa?.takeIf { it.isFinite() && it in 300f..1100f }

/** Schwellen, die mehr als eine Klasse der Gelaende-Rechnung braucht; die Herleitung steht in der Spezifikation `2026-10-18-terrain-tile-design.md`. */
internal object TerrainLimits {
    /** Eine GPS-Hoehe zaehlt nur bei dieser Vertikalgenauigkeit (m); fehlt die Angabe, zaehlt sie nicht. */
    const val MAX_VERTICAL_ACCURACY_M = 20f

    /** Ein Fix, der aelter ist, zaehlt nicht fuer die Hoehe. */
    const val MAX_FIX_AGE_MS = 3_000L

    /** Zeitkonstante der Glaettung einer GPS-Hoehe ohne Barometer. */
    const val GPS_ONLY_TAU_MS = 15_000L

    /** Ab dieser Geschwindigkeit gilt eine Steigung, darunter ist das GPS zu verrauscht. */
    const val MIN_GRADE_SPEED_KMH = 10f
}

/** Gleitender Mittelwert mit Zeitkonstante: je Probe geht `dt / tau` (hoechstens 1) der Differenz in den Wert ein. Die erste Probe setzt ihn. */
internal class SmoothedValue(private val tauMs: Long) {
    var value: Float? = null
        private set
    var lastUpdateMs: Long = 0
        private set

    fun update(nowMs: Long, sample: Float): Float {
        val current = value
        val next = if (current == null) sample else {
            val gain = ((nowMs - lastUpdateMs).coerceAtLeast(0).toFloat() / tauMs).coerceIn(0f, 1f)
            current + gain * (sample - current)
        }
        value = next
        lastUpdateMs = nowMs
        return next
    }

    fun reset() {
        value = null
        lastUpdateMs = 0
    }
}

/** Die GPS-Hoehe eines Fixes, wenn sie fuer die Rechnung taugt: vorhanden, endlich, genau genug und frisch (sonst null). */
internal fun usableGpsAltitude(nowMs: Long, fix: GpsFix?): Float? {
    if (fix == null) return null
    val altitude = fix.altitudeM?.takeIf { it.isFinite() } ?: return null
    val accuracy = fix.verticalAccuracyM ?: return null
    if (!(accuracy <= TerrainLimits.MAX_VERTICAL_ACCURACY_M)) return null
    val age = nowMs - fix.elapsedMs
    if (age < 0 || age > TerrainLimits.MAX_FIX_AGE_MS) return null
    return altitude
}
