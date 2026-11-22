package de.adv990de.obd2.map

/**
 * Eine Quelle, aus der man Bereiche einer Datei liest — eine lokale Datei, Test-Bytes oder (spaeter) ein Server mit Bereichsabfragen (HTTP `Range`). Die ganze Datei liegt nie im Speicher.
 *
 * [read] liefert genau [length] Bytes ab [offset] oder `null`, wenn der Bereich nicht lesbar ist (ausserhalb der Datei, Fehler, Zeitlimit). Eine zu kurze Antwort ist `null`, nie ein Teilstueck:
 * ein abgeschnittenes Verzeichnis oder eine abgeschnittene Kachel waere sonst ein stiller Fehler.
 */
interface RandomAccessSource {
    suspend fun read(offset: Long, length: Int): ByteArray?
}

/** Eine Quelle aus Bytes im Speicher (Tests, kleine Dateien). */
class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    override suspend fun read(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length < 0 || offset > bytes.size || length > bytes.size - offset) return null
        val from = offset.toInt()
        return bytes.copyOfRange(from, from + length)
    }
}
