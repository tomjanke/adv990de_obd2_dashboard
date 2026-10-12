package de.adv990de.obd2.obd

/**
 * Liest die Fahrgestellnummer (FIN/VIN) aus der Antwort auf Modus 09, PID 02 (`0902`).
 *
 * Rein und ohne Android-Bezug. [ElmController.sendCommand] liefert alle Zeilen einer Antwort mit
 * Leerzeichen verbunden — die Zeilengrenzen sind danach weg, deshalb arbeitet der Decoder auf
 * Token-Ebene. Gehandhabt werden die Formen echter Adapter:
 * - **CAN (ISO 15765-4), mehrere Frames:** eine Laengenzeile (`014`), dann `0:`, `1:`, `2:` je Frame;
 *   nur der erste Frame traegt den Kopf `49 02 01` (Modus, PID, Anzahl der Datenelemente).
 * - **K-Line/J1850:** jede Zeile beginnt mit `49 02 <Nr>` und traegt vier Datenbytes; die FIN ist links
 *   mit `00` aufgefuellt.
 * - **Ohne Leerzeichen (`ATS0`)**, mit vorangestelltem `SEARCHING...`/`BUS INIT: OK`, in Klein-
 *   buchstaben, oder doppelt von zwei Steuergeraeten gemeldet.
 *
 * Alles andere — `NO DATA`, `UNABLE TO CONNECT`, eine Antwort auf eine andere Anfrage, Daten, die
 * keine 17 Zeichen ergeben — ergibt `null`, nie einen Hexdump oder Fehlertext. Der Aufrufer speichert
 * und zeigt das Ergebnis als "FIN"; ein Roh-Hexdump oder "NO DATA" waere dort schlicht falsch.
 *
 * Bewusst nur `[A-Z0-9]` statt der strengen ISO-3779-Menge ohne I/O/Q: Ein Steuergeraet, das ein
 * solches Zeichen sendet, soll seine FIN trotzdem zeigen koennen. Die Kopfbytes `49 02` lassen sich
 * gefahrlos aus den Daten entfernen — `0x02` ist kein druckbares Zeichen und kommt in einer FIN nie vor.
 */
object VinDecoder {
    private const val VIN_LENGTH = 17
    private const val MODE_09_RESPONSE = 0x49
    private const val PID_VIN = 0x02

    /** Meldungen, die der Adapter vor der eigentlichen Antwort ausgeben darf (K-Line-Initialisierung, Protokollsuche). */
    private val BENIGN_WORDS = setOf("SEARCHING...", "BUS", "INIT:", "OK")

    private val FRAME_MARKER = Regex("^[0-9A-F]:")
    private val HEX = Regex("^[0-9A-F]+$")
    private val THREE_HEX_DIGITS = Regex("^[0-9A-F]{3}$")
    private val VIN = Regex("^[A-Z0-9]{$VIN_LENGTH}$")

    /** Ob [text] eine FIN ist — auch aeltere, gespeicherte Werte (frueher stand hier die rohe Antwort). */
    fun isValidVin(text: String): Boolean = VIN.matches(text)

    fun decode(raw: String): String? {
        val tokens = raw.trim().uppercase().split(WHITESPACE).filter { it.isNotEmpty() }
        val hex = StringBuilder()
        for ((i, token) in tokens.withIndex()) {
            if (token in BENIGN_WORDS) continue
            // Laengenzeile einer CAN-Mehrframe-Antwort: genau drei Hexziffern unmittelbar vor dem ersten "0:".
            if (THREE_HEX_DIGITS.matches(token) && tokens.getOrNull(i + 1)?.startsWith("0:") == true) continue
            val data = if (FRAME_MARKER.containsMatchIn(token)) token.substring(2) else token
            if (data.isEmpty()) continue
            // Irgendein anderes Wort (Fehlertext, "?", Muell): keine FIN — nicht raten.
            if (!HEX.matches(data) || data.length % 2 != 0) return null
            hex.append(data)
        }

        val bytes = hex.chunked(2).map { it.toInt(16) }
        if (bytes.size < 4 || bytes[0] != MODE_09_RESPONSE || bytes[1] != PID_VIN) return null

        // Alle "49 02 <Nr>"-Koepfe entfernen (K-Line: je Zeile einer; CAN: einer am Anfang), danach die 00-Auffuellung.
        val data = ArrayList<Int>(bytes.size)
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == MODE_09_RESPONSE && i + 1 < bytes.size && bytes[i + 1] == PID_VIN) {
                i += 3
            } else {
                if (bytes[i] != 0) data.add(bytes[i])
                i++
            }
        }

        // Ein Zeichen ausserhalb von 0x20..0x7E ist keine FIN.
        if (data.any { it !in 0x20..0x7E }) return null
        val text = data.joinToString("") { it.toChar().toString() }

        return when {
            text.length == VIN_LENGTH -> text
            // Mehrere Steuergeraete melden dieselbe FIN hintereinander.
            text.length > VIN_LENGTH && text.length % VIN_LENGTH == 0 ->
                text.chunked(VIN_LENGTH).distinct().singleOrNull()
            else -> null
        }?.takeIf(::isValidVin)
    }
}
