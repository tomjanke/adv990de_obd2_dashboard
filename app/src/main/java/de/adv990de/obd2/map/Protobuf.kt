package de.adv990de.obd2.map

/** Eine Eingabe, die kein gueltiges Protobuf ist oder eine Grenze reisst. Bleibt intern: die Dekoder fangen sie und liefern `null`. */
internal class MalformedException(message: String) : RuntimeException(message)

internal const val WIRE_VARINT = 0
internal const val WIRE_FIXED64 = 1
internal const val WIRE_LENGTH = 2
internal const val WIRE_FIXED32 = 5

/**
 * Ein kleiner Protobuf-Leser fuer das, was Vektorkacheln brauchen: Varints, feste Zahlen, Laenge-Praefix. Er liest den Bereich [start, end) von [bytes] und prueft jede Grenze;
 * jeder Fehler ist eine [MalformedException].
 */
internal class ProtoReader(private val bytes: ByteArray, start: Int = 0, val end: Int = bytes.size) {
    var pos: Int = start
        private set

    init {
        require(start in 0..end && end <= bytes.size)
    }

    fun hasMore() = pos < end

    /** Das naechste Feld: Nummer und Wire-Typ, `null` am Ende. */
    fun nextTag(): Pair<Int, Int>? {
        if (pos >= end) return null
        val tag = varint()
        val field = tag ushr 3
        if (field <= 0 || field > Int.MAX_VALUE.toLong() || tag < 0) throw MalformedException("Feldnummer $field")
        return field.toInt() to (tag and 7).toInt()
    }

    /** Ein Varint als Long (bis 64 Bit; ein negativer Wert ist ein Wert ueber 2^63, wie `uint64` in einem Long). */
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw MalformedException("Varint abgeschnitten")
            val b = bytes[pos++].toInt() and 0xFF
            if (shift == 63 && (b and 0x7E) != 0) throw MalformedException("Varint zu gross")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw MalformedException("Varint zu lang")
        }
    }

    fun fixed32(): Int {
        if (end - pos < 4) throw MalformedException("fixed32 abgeschnitten")
        var v = 0
        for (i in 3 downTo 0) v = (v shl 8) or (bytes[pos + i].toInt() and 0xFF)
        pos += 4
        return v
    }

    fun fixed64(): Long {
        if (end - pos < 8) throw MalformedException("fixed64 abgeschnitten")
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
        pos += 8
        return v
    }

    /** Liest die Laenge eines Laenge-Praefix-Feldes und gibt den Bereich als eigenen Leser zurueck; der aeussere Leser steht danach dahinter. */
    fun lengthDelimited(): ProtoReader {
        val length = varint()
        if (length < 0 || length > end - pos) throw MalformedException("Laenge $length ueber den Rest ${end - pos}")
        val sub = ProtoReader(bytes, pos, pos + length.toInt())
        pos += length.toInt()
        return sub
    }

    fun string(): String {
        val sub = lengthDelimited()
        return String(bytes, sub.pos, sub.end - sub.pos, Charsets.UTF_8)
    }

    /** Ueberspringt den Wert eines Feldes mit dem Wire-Typ [wire] (unbekannte Felder). */
    fun skip(wire: Int) {
        when (wire) {
            WIRE_VARINT -> varint()
            WIRE_FIXED64 -> fixed64()
            WIRE_LENGTH -> lengthDelimited()
            WIRE_FIXED32 -> fixed32()
            else -> throw MalformedException("Wire-Typ $wire") // Gruppen (3, 4) und alles andere gibt es in Kacheln nicht
        }
    }
}
