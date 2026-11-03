package de.adv990de.obd2.tripimport

import de.adv990de.obd2.data.SESSION_RETENTION_MS

/** Beginn und Ende einer Fahrt, die schon in der App liegt (Millisekunden seit 1970); [endMs] einer nie beendeten Fahrt ist der Zeitstempel ihres letzten Messwerts. */
data class SessionSpan(val startMs: Long, val endMs: Long)

/**
 * Was ein Import tun wuerde, bevor er etwas schreibt (die Vorschau): [trips] sind die neuen Fahrten in Dateireihenfolge, [duplicateTrips] die uebersprungenen, weil ihre
 * Zeit eine vorhandene Fahrt (oder eine fruehere der Datei) beruehrt, [expiredTrips] die uebersprungenen, die laenger als die Aufbewahrung zurueckliegen (die App
 * wuerde sie beim naechsten Start wieder loeschen), und [rejected] die abgelehnten Zeilen ([rejectedCount] alle, [rejected] nur die ersten).
 */
data class TripImportPlan(
    val trips: List<ParsedTrip>,
    val duplicateTrips: Int,
    val expiredTrips: Int,
    val rejected: List<RejectedRow>,
    val rejectedCount: Int,
) {
    /** Die Zahl der Messwerte aller neuen Fahrten. */
    val readingCount: Int get() = trips.sumOf { it.rows.size }
}

private fun overlaps(span: SessionSpan, trip: ParsedTrip) = span.startMs <= trip.endMs && span.endMs >= trip.startMs

/**
 * Macht aus der gelesenen Datei und den vorhandenen Fahrten den [TripImportPlan]. Eine Fahrt der Datei wird **uebersprungen**, wenn
 *  1. sie beginnt vor `nowMs - retentionMs` (aeltere Fahrten loescht [de.adv990de.obd2.data.DataRetention] beim naechsten Start; sie zu importieren waere vergebens), oder
 *  2. ihre Zeit [Beginn, Ende] die Zeit einer vorhandenen Fahrt oder einer schon angenommenen der Datei beruehrt (Grenzen eingeschlossen): eine Aufzeichnung laeuft nie
 *     zweimal zugleich, also ist es dieselbe Fahrt — ein zweiter Import derselben Datei aendert nichts, eine ergaenzte Datei bringt nur die neuen Fahrten.
 * Eine abgelaufene Fahrt zaehlt als abgelaufen, auch wenn sie sich zugleich mit einer vorhandenen deckt.
 */
fun planTripImport(parsed: ParsedTripFile, existing: List<SessionSpan>, nowMs: Long, retentionMs: Long = SESSION_RETENTION_MS): TripImportPlan {
    val cutoffMs = nowMs - retentionMs
    val accepted = ArrayList<ParsedTrip>()
    val acceptedSpans = ArrayList<SessionSpan>()
    var duplicates = 0
    var expired = 0
    for (trip in parsed.trips) {
        when {
            trip.startMs < cutoffMs -> expired++
            existing.any { overlaps(it, trip) } || acceptedSpans.any { overlaps(it, trip) } -> duplicates++
            else -> { accepted += trip; acceptedSpans += SessionSpan(trip.startMs, trip.endMs) }
        }
    }
    return TripImportPlan(accepted, duplicates, expired, parsed.rejected, parsed.rejectedCount)
}
