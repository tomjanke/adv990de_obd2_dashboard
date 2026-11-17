package de.adv990de.obd2.track

import java.time.Instant
import java.util.Locale

/**
 * Schreibt einen Track als GPX 1.1 (eine `trk` mit einer `trkseg`). Zeiten in UTC, **Zahlen immer mit Punkt** — `String.format` ohne Locale schriebe auf einem deutschen
 * Geraet Kommas und machte die Datei fuer jede andere Software unlesbar. Der Name wird fuer XML maskiert, Zeichen, die XML 1.0 verbietet, entfallen.
 *
 * Ein Punkt ohne Zeit oder Hoehe schreibt das jeweilige Element nicht; eine nicht endliche Hoehe und eine Zeit ausserhalb der Jahre 0000 bis 9999 gelten als unbekannt.
 *
 * **Die Datei enthaelt die Wege des Nutzers** — wer sie teilt, gibt sie weiter; die Oberflaeche sagt es.
 */
fun writeGpx(out: Appendable, name: String, points: List<TrackPoint>) {
    out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    out.append("<gpx version=\"1.1\" creator=\"OBD Dashboard\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    out.append("<trk>\n<name>").append(xmlText(name)).append("</name>\n<trkseg>\n")
    for (p in points) {
        out.append("<trkpt lat=\"").append(coordinate(p.lat)).append("\" lon=\"").append(coordinate(p.lon)).append("\">")
        val ele = p.ele
        if (ele != null && ele.isFinite()) out.append("<ele>").append(String.format(Locale.ROOT, "%.1f", ele)).append("</ele>")
        val t = p.timeMs
        if (t != null && t in MIN_TIME_MS..MAX_TIME_MS) out.append("<time>").append(Instant.ofEpochMilli(t).toString()).append("</time>")
        out.append("</trkpt>\n")
    }
    out.append("</trkseg>\n</trk>\n</gpx>\n")
}

private const val MIN_TIME_MS = 0L

/** 9999-12-31T23:59:59.999Z */
private const val MAX_TIME_MS = 253_402_300_799_999L

private fun coordinate(v: Double): String = String.format(Locale.ROOT, "%.7f", v)

/** Maskiert fuer Text zwischen Tags und laesst Zeichen weg, die XML 1.0 nicht erlaubt (Steuerzeichen, einzelne Surrogate, U+FFFE und U+FFFF). */
internal fun xmlText(s: String): String {
    val sb = StringBuilder(s.length + 8)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        val allowed = cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
        if (!allowed) continue
        when (cp) {
            '&'.code -> sb.append("&amp;")
            '<'.code -> sb.append("&lt;")
            '>'.code -> sb.append("&gt;")
            '"'.code -> sb.append("&quot;")
            '\''.code -> sb.append("&apos;")
            else -> sb.appendCodePoint(cp)
        }
    }
    return sb.toString()
}
