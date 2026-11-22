package de.adv990de.obd2.map

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/*
 * Ein Leser fuer PMTiles v3 (Spezifikation: github.com/protomaps/PMTiles, spec/v3/spec.md, gelesen am 2026-11-22). Er liest nur, was er braucht: den Kopf (127 Byte), Verzeichnisse und einzelne
 * Kacheln ueber eine [RandomAccessSource]. Jeder Formfehler und jede Grenzverletzung ist `null`, nie eine Ausnahme: die Quelle (eine Datei, spaeter ein Server) ist nicht vertrauenswuerdig.
 */

/** Der Kopf einer PMTiles-Datei (nur die Felder, die wir brauchen). Orte in Grad. */
data class PmTilesHeader(
    val rootDirOffset: Long,
    val rootDirLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafDirsOffset: Long,
    val leafDirsLength: Long,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    val internalCompression: PmCompression,
    val tileCompression: PmCompression,
    val tileType: PmTileType,
    val minZoom: Int,
    val maxZoom: Int,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
)

enum class PmCompression { UNKNOWN, NONE, GZIP, BROTLI, ZSTD }

enum class PmTileType { UNKNOWN, MVT, PNG, JPEG, WEBP, AVIF, MLT }

/** Ein Eintrag eines Verzeichnisses: [runLength] 0 heisst „Blattverzeichnis“, sonst [runLength] aufeinanderfolgende Kacheln mit denselben Daten. */
data class PmEntry(val tileId: Long, val offset: Long, val length: Long, val runLength: Long)

// ── Grenzen (der Server ist nicht vertrauenswuerdig) ───────────────────────────────────────────────────────────────

/** So gross darf ein Verzeichnis gepackt sein (die Spezifikation verlangt fuer die Wurzel hoechstens 16 KiB). */
internal const val MAX_DIRECTORY_PACKED_BYTES = 4 * 1024 * 1024

/** So gross darf ein Verzeichnis entpackt sein. */
internal const val MAX_DIRECTORY_BYTES = 16 * 1024 * 1024

/** So viele Eintraege darf ein Verzeichnis haben. */
internal const val MAX_DIRECTORY_ENTRIES = 200_000

/** So gross darf eine Kachel gepackt und entpackt sein. */
internal const val MAX_TILE_BYTES = 4 * 1024 * 1024

/** So gross darf der Metadaten-Text entpackt sein. */
internal const val MAX_METADATA_BYTES = 1024 * 1024

/** Wie tief Blattverzeichnisse verschachtelt sein duerfen (die Spezifikation rät von mehr als einer Ebene ab, die Referenz erlaubt drei). */
internal const val MAX_LEAF_DEPTH = 3

/** Wie viele Verzeichnisse wir im Speicher halten. */
private const val DIRECTORY_CACHE_SIZE = 64

private const val HEADER_BYTES = 127
private val MAGIC = byteArrayOf(0x50, 0x4D, 0x54, 0x69, 0x6C, 0x65, 0x73) // "PMTiles"

/**
 * Eine geoeffnete PMTiles-Datei. [open] liest den Kopf; [tile] liefert die entpackten Bytes einer Kachel. Verzeichnisse werden bei Bedarf gelesen und zwischengespeichert.
 *
 * Unterstuetzt sind Vektorkacheln (MVT) und die Packungen „keine“ und gzip; alles andere ist [supported] = `false` und [tile] liefert `null`.
 */
class PmTilesArchive private constructor(private val source: RandomAccessSource, val header: PmTilesHeader) {

    /** `true`, wenn wir diese Datei lesen koennen: Vektorkacheln, Packung keine oder gzip. */
    val supported: Boolean =
        header.tileType == PmTileType.MVT && header.internalCompression.isReadable() && header.tileCompression.isReadable()

    private val directories = object : LinkedHashMap<Long, List<PmEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<PmEntry>>) = size > DIRECTORY_CACHE_SIZE
    }

    /** Die Metadaten (JSON) als Text, `null` wenn sie fehlen oder nicht lesbar sind. */
    suspend fun metadataJson(): String? {
        if (!supported || header.metadataLength <= 0 || header.metadataLength > MAX_DIRECTORY_PACKED_BYTES) return null
        val packed = source.read(header.metadataOffset, header.metadataLength.toInt()) ?: return null
        return unpack(packed, header.internalCompression, MAX_METADATA_BYTES)?.toString(Charsets.UTF_8)
    }

    /** Die entpackten Bytes der Kachel [key] oder `null` (nicht in der Datei, ungueltig, nicht lesbar, nicht unterstuetzt). */
    suspend fun tile(key: TileKey): ByteArray? {
        if (!supported) return null
        val id = tileId(key) ?: return null
        if (key.z < header.minZoom || key.z > header.maxZoom) return null
        var dirOffset = header.rootDirOffset
        var dirLength = header.rootDirLength
        repeat(MAX_LEAF_DEPTH + 1) {
            val entries = directory(dirOffset, dirLength) ?: return null
            val entry = findEntry(entries, id) ?: return null
            if (entry.runLength > 0) return readTile(entry)
            // Blattverzeichnis: Lage relativ zum Abschnitt der Blattverzeichnisse.
            if (entry.offset < 0 || entry.offset > header.leafDirsLength || entry.length > header.leafDirsLength - entry.offset) return null
            dirOffset = header.leafDirsOffset + entry.offset
            dirLength = entry.length
        }
        return null
    }

    private suspend fun readTile(entry: PmEntry): ByteArray? {
        if (entry.offset < 0 || entry.length <= 0 || entry.length > MAX_TILE_BYTES) return null
        if (entry.offset > header.tileDataLength || entry.length > header.tileDataLength - entry.offset) return null
        val packed = source.read(header.tileDataOffset + entry.offset, entry.length.toInt()) ?: return null
        return unpack(packed, header.tileCompression, MAX_TILE_BYTES)
    }

    private suspend fun directory(offset: Long, length: Long): List<PmEntry>? {
        directories[offset]?.let { return it }
        if (length <= 0 || length > MAX_DIRECTORY_PACKED_BYTES || offset < 0) return null
        val packed = source.read(offset, length.toInt()) ?: return null
        val bytes = unpack(packed, header.internalCompression, MAX_DIRECTORY_BYTES) ?: return null
        val entries = parseDirectory(bytes) ?: return null
        directories[offset] = entries
        return entries
    }

    companion object {
        /** Liest den Kopf. `null`, wenn die Datei kein PMTiles v3 ist oder der Kopf unsinnige Werte traegt. */
        suspend fun open(source: RandomAccessSource): PmTilesArchive? {
            val bytes = source.read(0, HEADER_BYTES) ?: return null
            val header = parseHeader(bytes) ?: return null
            return PmTilesArchive(source, header)
        }
    }
}

private fun PmCompression.isReadable() = this == PmCompression.NONE || this == PmCompression.GZIP

/** Der Eintrag, in dem [id] liegt: der mit genau dieser Kennung, sonst der letzte kleinere, wenn er [id] noch ueberdeckt (oder ein Blattverzeichnis ist). */
internal fun findEntry(entries: List<PmEntry>, id: Long): PmEntry? {
    var lo = 0
    var hi = entries.size - 1
    while (lo <= hi) {
        val m = (lo + hi) ushr 1
        val e = entries[m]
        when {
            id > e.tileId -> lo = m + 1
            id < e.tileId -> hi = m - 1
            else -> return e
        }
    }
    if (hi >= 0) {
        val e = entries[hi]
        if (e.runLength == 0L) return e
        if (id - e.tileId < e.runLength) return e
    }
    return null
}

internal fun parseHeader(bytes: ByteArray): PmTilesHeader? {
    if (bytes.size < HEADER_BYTES) return null
    for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
    if (bytes[7].toInt() != 3) return null
    fun u64(at: Int): Long? {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (bytes[at + i].toLong() and 0xFF)
        return if (v < 0) null else v // ein Wert ueber Long.MAX_VALUE ist unsinnig
    }
    fun i32(at: Int): Int {
        var v = 0
        for (i in 3 downTo 0) v = (v shl 8) or (bytes[at + i].toInt() and 0xFF)
        return v
    }
    val rootOffset = u64(8) ?: return null
    val rootLength = u64(16) ?: return null
    val metaOffset = u64(24) ?: return null
    val metaLength = u64(32) ?: return null
    val leafOffset = u64(40) ?: return null
    val leafLength = u64(48) ?: return null
    val dataOffset = u64(56) ?: return null
    val dataLength = u64(64) ?: return null
    val minZoom = bytes[100].toInt() and 0xFF
    val maxZoom = bytes[101].toInt() and 0xFF
    if (maxZoom < minZoom) return null
    fun compression(b: Byte) = PmCompression.entries.getOrElse(b.toInt() and 0xFF) { PmCompression.UNKNOWN }
    fun tileType(b: Byte) = PmTileType.entries.getOrElse(b.toInt() and 0xFF) { PmTileType.UNKNOWN }
    // Orte: Laenge (4 Byte), dann Breite (4 Byte), je Ganzzahl / 10 000 000 (Abschnitt „Position“ der Spezifikation).
    fun deg(at: Int) = i32(at) / 10_000_000.0
    return PmTilesHeader(
        rootDirOffset = rootOffset, rootDirLength = rootLength,
        metadataOffset = metaOffset, metadataLength = metaLength,
        leafDirsOffset = leafOffset, leafDirsLength = leafLength,
        tileDataOffset = dataOffset, tileDataLength = dataLength,
        internalCompression = compression(bytes[97]), tileCompression = compression(bytes[98]), tileType = tileType(bytes[99]),
        minZoom = minZoom, maxZoom = maxZoom,
        minLon = deg(102), minLat = deg(106), maxLon = deg(110), maxLat = deg(114),
    )
}

/** Liest ein entpacktes Verzeichnis (Spezifikation, Anhang A.2). `null` bei jedem Formfehler. */
internal fun parseDirectory(bytes: ByteArray): List<PmEntry>? {
    val r = VarintReader(bytes)
    val n = r.next() ?: return null
    if (n <= 0 || n > MAX_DIRECTORY_ENTRIES) return null
    val count = n.toInt()
    val ids = LongArray(count)
    var last = 0L
    for (i in 0 until count) {
        val delta = r.next() ?: return null
        last += delta
        if (last < 0) return null
        ids[i] = last
    }
    val runs = LongArray(count) { r.next() ?: return null }
    val lengths = LongArray(count) { r.next() ?: return null }
    val offsets = LongArray(count)
    for (i in 0 until count) {
        val v = r.next() ?: return null
        offsets[i] = if (i > 0 && v == 0L) offsets[i - 1] + lengths[i - 1] else v - 1
        if (offsets[i] < 0) return null
    }
    // Die Kennungen muessen aufsteigen, sonst taugt die Binaersuche nicht.
    for (i in 1 until count) if (ids[i] <= ids[i - 1]) return null
    return List(count) { PmEntry(ids[it], offsets[it], lengths[it], runs[it]) }
}

/** Liest Varints (LEB128, wie Protobuf) mit Grenzen; `null` am Ende der Daten oder bei einem zu langen Wert. */
internal class VarintReader(private val bytes: ByteArray, private var pos: Int = 0, private val end: Int = bytes.size) {
    fun next(): Long? {
        var result = 0L
        var shift = 0
        while (pos < end) {
            val b = bytes[pos++].toInt() and 0xFF
            if (shift >= 63 && (b and 0x7F) > 1) return null // ueber 63 Bit
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return if (result < 0) null else result
            shift += 7
            if (shift > 63) return null
        }
        return null
    }
}

/** Entpackt [packed] nach [compression]; hoechstens [limit] Bytes. `null` bei Fehlern oder wenn die Grenze ueberschritten wuerde (kein Entpacken ohne Ende). */
internal fun unpack(packed: ByteArray, compression: PmCompression, limit: Int): ByteArray? = when (compression) {
    PmCompression.NONE -> if (packed.size <= limit) packed else null
    PmCompression.GZIP -> try {
        GZIPInputStream(packed.inputStream()).use { readLimited(it, limit) }
    } catch (e: java.io.IOException) {
        null
    }
    else -> null
}

private fun readLimited(input: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > limit) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
