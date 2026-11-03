package de.adv990de.obd2.data

import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader

/** Ein Datensatz der Datei: die Zeilennummer, in der er beginnt (ein Feld in Anfuehrungszeichen darf Zeilenumbrueche tragen), und seine Felder. */
internal data class CsvRecord(val line: Int, val fields: List<String>)

private const val NOTHING_PEEKED = -2
private const val END_OF_INPUT = -1
private const val BYTE_ORDER_MARK = 0xFEFF
private const val READ_BUFFER_CHARS = 64 * 1024

/**
 * Obergrenzen gegen eine Datei, die kein CSV ist (eine Binaerdatei ohne Zeilenende waere sonst ein einziges Feld von der Groesse der Datei und ein `OutOfMemoryError`, der die App
 * beendet): ein Feld hat hoechstens [DEFAULT_MAX_FIELD_CHARS] Zeichen, ein Datensatz hoechstens [DEFAULT_MAX_FIELDS] Felder. Die Dateien der App haben Felder von wenigen Zeichen und acht Spalten.
 */
internal const val DEFAULT_MAX_FIELD_CHARS = 64 * 1024
internal const val DEFAULT_MAX_FIELDS = 256

/**
 * Liest CSV nach RFC 4180 **Datensatz fuer Datensatz** aus einem [Reader] (der Logbuch-Import und der Fahrten-Import nutzen dieselbe Zustandsmaschine; der
 * Fahrten-Import liest Dateien mit hunderttausenden Zeilen, die nie als ganzer Text im Speicher stehen sollen): Felder in Anfuehrungszeichen (`""` ist ein
 * Anfuehrungszeichen), Komma und Zeilenumbruch im Feld, Zeilenende `\n`, `\r\n` oder `\r`, ein Bytereihenfolge-Zeichen am Anfang, ein fehlendes Zeilenende am
 * Schluss. Eine Leerzeile ist ein Datensatz mit einem leeren Feld. Ein Anfuehrungszeichen mitten in einem unquotierten Feld ist ein gewoehnliches Zeichen.
 * Ein nie geschlossenes Anfuehrungszeichen ist ein Fehler ([IllegalArgumentException]) und kein stilles Abschneiden, ebenso ein Feld ueber [maxFieldChars] Zeichen und ein
 * Datensatz ueber [maxFields] Felder. Nicht fuer mehrere Threads.
 *
 * [next] liefert den naechsten Datensatz oder `null` am Ende; der [Reader] wird nicht geschlossen (das tut der Aufrufer).
 */
internal class CsvRecordReader(
    reader: Reader,
    private val maxFieldChars: Int = DEFAULT_MAX_FIELD_CHARS,
    private val maxFields: Int = DEFAULT_MAX_FIELDS,
) {

    private val input: Reader = if (reader is BufferedReader) reader else BufferedReader(reader, READ_BUFFER_CHARS)
    private var peeked = NOTHING_PEEKED
    private var line = 1
    private var started = false

    private fun read(): Int {
        if (peeked != NOTHING_PEEKED) return peeked.also { peeked = NOTHING_PEEKED }
        return input.read()
    }

    private fun peek(): Int {
        if (peeked == NOTHING_PEEKED) peeked = input.read()
        return peeked
    }

    fun next(): CsvRecord? {
        if (!started) {
            started = true
            if (peek() == BYTE_ORDER_MARK) read()
        }
        val fields = ArrayList<String>()
        val field = StringBuilder()
        val recordLine = line
        var inQuotes = false
        while (true) {
            val c = read()
            if (c == END_OF_INPUT) {
                require(!inQuotes) { "A quoted field starting in line $recordLine is never closed" }
                if (field.isEmpty() && fields.isEmpty()) return null
                fields.add(field.toString())
                return CsvRecord(recordLine, fields)
            }
            val ch = c.toChar()
            if (inQuotes) {
                if (ch == '"') {
                    if (peek() == '"'.code) { read(); appendChecked(field, '"', recordLine) } else inQuotes = false
                } else {
                    if (ch == '\n') line++
                    appendChecked(field, ch, recordLine)
                }
                continue
            }
            when (ch) {
                '"' -> if (field.isEmpty()) inQuotes = true else appendChecked(field, ch, recordLine)
                ',' -> { addChecked(fields, field, recordLine); field.setLength(0) }
                '\r' -> {
                    if (peek() == '\n'.code) read()
                    line++
                    fields.add(field.toString())
                    return CsvRecord(recordLine, fields)
                }
                '\n' -> {
                    line++
                    fields.add(field.toString())
                    return CsvRecord(recordLine, fields)
                }
                else -> appendChecked(field, ch, recordLine)
            }
        }
    }

    private fun appendChecked(field: StringBuilder, ch: Char, recordLine: Int) {
        require(field.length < maxFieldChars) { "A field in the record starting in line $recordLine is longer than $maxFieldChars characters" }
        field.append(ch)
    }

    private fun addChecked(fields: ArrayList<String>, field: StringBuilder, recordLine: Int) {
        require(fields.size < maxFields - 1) { "The record starting in line $recordLine has more than $maxFields fields" }
        fields.add(field.toString())
    }
}

/** Liest [text] ganz als CSV ([CsvRecordReader]); fuer kleine Dateien, die ohnehin als Text vorliegen. */
internal fun parseCsv(text: String): List<CsvRecord> {
    val reader = CsvRecordReader(StringReader(text))
    return generateSequence { reader.next() }.toList()
}
