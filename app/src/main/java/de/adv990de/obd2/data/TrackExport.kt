package de.adv990de.obd2.data

/** So viele Zeichen des Track-Namens stehen hoechstens im Dateinamen des Exports. */
internal const val TRACK_FILE_NAME_MAX = 40

/** Der Name, wenn vom Track-Namen nichts Brauchbares uebrig bleibt. */
internal const val TRACK_FILE_NAME_FALLBACK = "unnamed"

/**
 * `track_<Name>_<UTC-Zeit auf die Millisekunde>.gpx`. Der Name kommt vom Nutzer (oder aus einer importierten Datei) und ist **nie ein Pfad**: nur Buchstaben und Ziffern
 * bleiben (jeder Sprache), jede Folge anderer Zeichen — Schraegstriche, Punkte, Leerzeichen, Doppelpunkte — wird zu **einem** Unterstrich, am Rand steht keiner; hoechstens
 * [TRACK_FILE_NAME_MAX] Zeichen (ganze Zeichen, nie mitten in einem Surrogatpaar). Bleibt nichts uebrig, steht [TRACK_FILE_NAME_FALLBACK]. Der Name passt zum Aufraeumen
 * alter Exporte (`track_*.gpx`, s. [deleteStaleExports]).
 */
internal fun trackFileName(name: String, nowMillis: Long): String =
    "track_${cleanTrackNamePart(name)}_${exportTimeStamp(nowMillis)}.gpx"

private fun cleanTrackNamePart(name: String): String {
    val out = StringBuilder()
    var count = 0
    var pendingSeparator = false
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        i += Character.charCount(cp)
        if (!Character.isLetterOrDigit(cp)) {
            pendingSeparator = true
            continue
        }
        val separator = pendingSeparator && count > 0
        if (count + (if (separator) 2 else 1) > TRACK_FILE_NAME_MAX) break
        if (separator) {
            out.append('_')
            count++
        }
        pendingSeparator = false
        out.appendCodePoint(cp)
        count++
    }
    return out.toString().ifEmpty { TRACK_FILE_NAME_FALLBACK }
}
