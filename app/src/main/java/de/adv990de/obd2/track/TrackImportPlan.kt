package de.adv990de.obd2.track

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Woran ein Track als „schon vorhanden“ gilt: gleicher Name, gleiche Punktzahl, gleicher erster und gleicher letzter Punkt (Entwurf §5). **Positionen sind sensibel:** ein
 * Fingerabdruck wird nie geloggt.
 */
data class TrackFingerprint(
    val name: String,
    val pointCount: Int,
    val firstLat: Double,
    val firstLon: Double,
    val lastLat: Double,
    val lastLon: Double,
)

/**
 * Zwei Punkte gelten als derselbe, wenn sie in beiden Koordinaten hoechstens so weit auseinander liegen (Grad; rund 0,11 m): die GPX-Datei, die diese App schreibt, hat sieben
 * Nachkommastellen, die Datenbank die volle Genauigkeit — ein Vergleich auf Gleichheit erkaennte den eigenen Export nie wieder.
 */
internal const val FINGERPRINT_TOLERANCE_DEG = 1e-6

/** Der Fingerabdruck von [name] und [points]; `null` ohne Punkte (ein leerer Track wird nie importiert). */
fun trackFingerprint(name: String, points: List<TrackPoint>): TrackFingerprint? {
    val first = points.firstOrNull() ?: return null
    val last = points.last()
    return TrackFingerprint(name, points.size, first.lat, first.lon, last.lat, last.lon)
}

private fun close(a: Double, b: Double) = abs(a - b) <= FINGERPRINT_TOLERANCE_DEG

fun sameTrack(a: TrackFingerprint, b: TrackFingerprint): Boolean =
    a.name == b.name && a.pointCount == b.pointCount &&
        close(a.firstLat, b.firstLat) && close(a.firstLon, b.firstLon) && close(a.lastLat, b.lastLat) && close(a.lastLon, b.lastLon)

/** Eine Spur der Datei, wie der Import sie schreiben wuerde: [name] ist der endgueltige Name (nie leer), [duplicate] = es gibt sie schon (oder eine gleiche weiter oben in der Datei). */
data class TrackImportItem(
    val name: String,
    val points: List<TrackPoint>,
    val distanceM: Long,
    val startMs: Long?,
    val endMs: Long?,
    val duplicate: Boolean,
) {
    val pointCount: Int get() = points.size
}

/**
 * Was der Import einer GPX-Datei tun wuerde (die Vorschau). Nichts davon ist geschrieben. [items] sind die Spuren mit Punkten in Dateireihenfolge (der Leser liefert nie eine Spur ohne
 * Punkt), [rejectedPoints] Punkte mit unmoeglicher Koordinate, [ignoredWaypoints] Wegpunkte (in dieser Version nur gelesen, nie gezeigt).
 */
data class TrackImportPlan(
    val items: List<TrackImportItem>,
    val rejectedPoints: Int,
    val ignoredWaypoints: Int,
) {
    /** Was geschrieben wird: alles ausser den Doppelten. */
    val toInsert: List<TrackImportItem> get() = items.filterNot { it.duplicate }
    val duplicateTracks: Int get() = items.count { it.duplicate }

    /** Die Positionen der neuen (nicht doppelten) Spuren in [items]: nur diese lassen sich in der Vorschau an- und abwaehlen. */
    val newIndices: List<Int> get() = items.indices.filter { !items[it].duplicate }

    /**
     * Der Plan ohne die neuen Spuren an den Positionen [excluded] (Index in [items], wie [newIndices]). Doppelte bleiben (sie sind nie Teil des Imports und zaehlen weiter als vorhanden),
     * unbekannte Positionen sind ohne Wirkung; ohne Ausschluss ist es derselbe Plan.
     */
    fun without(excluded: Set<Int>): TrackImportPlan =
        if (excluded.isEmpty()) this else copy(items = items.filterIndexed { index, item -> item.duplicate || index !in excluded })

    /** Neue Spuren ohne jede Zeit (ein geplanter Weg, keine Fahrt): erlaubt, aber die Vorschau sagt es. */
    val withoutTime: Int get() = toInsert.count { it.startMs == null }
}

private class Named(val name: String, val points: List<TrackPoint>)

/** Die Spuren mit Punkten mit ihrem endgueltigen Namen; ohne Namen heisst die n-te Spur (ab 1, leere nicht mitgezaehlt) [fallbackName]`(n)`. */
private fun named(file: GpxFile, fallbackName: (Int) -> String): List<Named> {
    var n = 0
    return file.tracks.filter { it.points.isNotEmpty() }.map { track ->
        n++
        val name = normalizeTrackName(track.name) ?: normalizeTrackName(fallbackName(n)) ?: "Track $n"
        Named(name, track.points)
    }
}

/** Name und Punktzahl jeder Spur mit Punkten: danach sucht der Import die moeglichen Doppelten in der Datenbank. */
fun candidateKeys(file: GpxFile, fallbackName: (Int) -> String): List<Pair<String, Int>> =
    named(file, fallbackName).map { it.name to it.points.size }

/** Macht aus der gelesenen Datei den Plan; [existing] sind die Fingerabdruecke der moeglichen Doppelten (gleicher Name und gleiche Punktzahl genuegen als Vorauswahl). */
fun planTrackImport(file: GpxFile, existing: Collection<TrackFingerprint>, fallbackName: (Int) -> String): TrackImportPlan {
    val seen = existing.toMutableList()
    val items = named(file, fallbackName).map { track ->
        val print = trackFingerprint(track.name, track.points)!!
        val duplicate = seen.any { sameTrack(it, print) }
        if (!duplicate) seen += print
        val stats = trackStats(track.points)
        TrackImportItem(
            name = track.name,
            points = track.points,
            distanceM = stats.distanceM.roundToLong(),
            startMs = track.points.firstOrNull { it.timeMs != null }?.timeMs,
            endMs = track.points.lastOrNull { it.timeMs != null }?.timeMs,
            duplicate = duplicate,
        )
    }
    return TrackImportPlan(
        items = items,
        rejectedPoints = file.rejectedPoints,
        ignoredWaypoints = file.waypoints.size,
    )
}
