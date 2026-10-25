package de.adv990de.obd2.logbook

/**
 * Die per GPS gezaehlten Kilometer ([gpsKm]) seit dem Kilometerstand [anchorKm] des Logbuchs. Der Zaehler gehoert zu **diesem** Stand: aendert sich der hoechste Stand im Logbuch (ein neuer
 * Eintrag, ein geloeschter), ist er veraltet ([estimateKm]) und wird neu begonnen.
 */
data class GpsOdometer(val anchorKm: Int, val gpsKm: Float)

/**
 * Der geschaetzte aktuelle Kilometerstand: der hoechste Stand im Logbuch ([loggedKm], `null` ohne Eintrag = keine Schaetzung) plus die Kilometer, die der GPS-Zaehler seit **genau diesem**
 * Stand gezaehlt hat — auf ganze Kilometer gerundet und nie ueber [MAX_ODOMETER_KM]. Ein Zaehler eines anderen Standes, ein negativer oder nicht endlicher Wert zaehlt nichts. Es ist eine
 * Schaetzung ("ca."): gezaehlt wird nur bei verbundener Sitzung und eingeschaltetem GPS, sie kann also zu niedrig sein, nie hoeher als gefahren (plus GPS-Rauschen).
 */
fun estimateKm(loggedKm: Int?, gps: GpsOdometer?): Int? {
    if (loggedKm == null) return null
    val km = gps?.takeIf { it.anchorKm == loggedKm && it.gpsKm.isFinite() && it.gpsKm > 0f }?.gpsKm ?: return loggedKm
    return (loggedKm.toLong() + Math.round(km)).coerceAtMost(MAX_ODOMETER_KM.toLong()).toInt()
}

/** Wie viele Kilometer die Schaetzung ueber dem Logbuch-Stand liegt (0 ohne GPS-Anteil): die Oberflaeche kennzeichnet einen Stand mit Anteil als "ca.". */
fun gpsShareKm(loggedKm: Int?, gps: GpsOdometer?): Int = loggedKm?.let { (estimateKm(it, gps) ?: it) - it } ?: 0
