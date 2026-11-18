package de.adv990de.obd2.ui.tracks

import java.text.NumberFormat
import java.util.Locale

/** Das Zeichen fuer einen Wert, den es nicht gibt ("unbekannt" ist nicht 0). */
internal const val TRACK_UNKNOWN = "–"

/** Eine Strecke: unter einem Kilometer ganze Meter, sonst Kilometer mit einer Nachkommastelle, in der Schreibweise der Geraetesprache. */
internal fun formatTrackDistance(metres: Long, locale: Locale = Locale.getDefault()): String {
    val m = metres.coerceAtLeast(0)
    if (m < 1_000) return "$m m"
    val km = NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }
    return km.format(m / 1000.0) + " km"
}

/** Eine Dauer als `m:ss`, ab einer Stunde `h:mm:ss`; ohne Wert ein Strich, eine negative Dauer gilt als null. */
internal fun formatTrackDuration(millis: Long?): String {
    if (millis == null) return TRACK_UNKNOWN
    val totalSeconds = millis.coerceAtLeast(0) / 1_000
    val h = totalSeconds / 3_600
    val m = totalSeconds % 3_600 / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/** Ganze Meter (Anstieg, Abstieg) in der Schreibweise der Geraetesprache; ohne Wert ein Strich. */
internal fun formatTrackMetres(metres: Int?, locale: Locale = Locale.getDefault()): String =
    if (metres == null) TRACK_UNKNOWN else NumberFormat.getIntegerInstance(locale).format(metres) + " m"
