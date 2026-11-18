package de.adv990de.obd2.location

import de.adv990de.obd2.track.TrackPoint

/**
 * Ein Fix, auf das reduziert, was die Streckenaufzeichnung braucht (neben [de.adv990de.obd2.terrain.GpsFix] der Gelaende-Kachel). [timeMs] ist die Wanduhr des Fixes (UTC, kommt in den Punkt und damit
 * in die GPX-Datei), [elapsedMs] die monotone Zeit (nur zum Altern und fuer den Filter). Die Position ist immer da, alles andere nur, wenn der Fix es traegt: "fehlt" ist nicht 0.
 */
data class TrackFix(
    val timeMs: Long,
    val elapsedMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Float?,
    val accuracyM: Float?,
    val speedKmh: Float?,
)

/** Der Punkt, den dieser Fix ergibt, wenn der Filter ihn annimmt. */
fun TrackFix.toPoint() = TrackPoint(timeMs = timeMs, lat = lat, lon = lon, ele = altitudeM, accuracyM = accuracyM, speedKmh = speedKmh)
