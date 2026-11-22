package de.adv990de.obd2.map

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Baut kleine PMTiles-v3-Archive fuer Tests, nach der Spezifikation (Anhang A.1) und **unabhaengig** vom Leser: eigener Kodierer fuer Varints, Verzeichnisse und Kopf.
 */
internal object PmTilesTestWriter {

    class Tile(val key: TileKey, val bytes: ByteArray)

    fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** Ein Verzeichnis nach Anhang A.1: Anzahl, Kennungen (Differenzen), Lauflaengen, Laengen, Versaetze (0 = anschliessend, sonst +1). */
    fun directory(entries: List<PmEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        varint(out, entries.size.toLong())
        var last = 0L
        for (e in entries) { varint(out, e.tileId - last); last = e.tileId }
        for (e in entries) varint(out, e.runLength)
        for (e in entries) varint(out, e.length)
        var next = 0L
        entries.forEachIndexed { i, e ->
            if (i > 0 && e.offset == next) varint(out, 0) else varint(out, e.offset + 1)
            next = e.offset + e.length
        }
        return out.toByteArray()
    }

    private fun le(out: ByteArrayOutputStream, value: Long, bytes: Int) {
        for (i in 0 until bytes) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    /**
     * Ein ganzes Archiv: [tiles] liegen in Reihenfolge der Kennung im Datenabschnitt; mit [leafSize] > 0 gibt es ein Wurzelverzeichnis mit Blattverzeichnissen zu je [leafSize] Eintraegen,
     * sonst nur die Wurzel. Packung (Verzeichnisse, Kacheln, Metadaten) je [internal] und [tileCompression] (1 = keine, 2 = gzip), Kacheltyp [tileType] (1 = MVT).
     */
    fun archive(
        tiles: List<Tile>,
        leafSize: Int = 0,
        internal: Int = 2,
        tileCompression: Int = 2,
        tileType: Int = 1,
        minZoom: Int = 0,
        maxZoom: Int = 15,
        metadata: String = """{"attribution":"test","vector_layers":[]}""",
        bbox: DoubleArray = doubleArrayOf(9.0, 49.0, 11.0, 51.0),
    ): ByteArray {
        fun pack(b: ByteArray, how: Int) = if (how == 2) gzip(b) else b
        val sorted = tiles.map { tileId(it.key)!! to pack(it.bytes, tileCompression) }.sortedBy { it.first }
        val data = ByteArrayOutputStream()
        val entries = sorted.map { (id, b) -> PmEntry(id, data.size().toLong(), b.size.toLong(), 1).also { data.write(b) } }
        val leaves = ByteArrayOutputStream()
        val root: List<PmEntry>
        if (leafSize > 0) {
            val chunks = entries.chunked(leafSize)
            root = chunks.map { chunk ->
                val packed = pack(directory(chunk), internal)
                PmEntry(chunk.first().tileId, leaves.size().toLong(), packed.size.toLong(), 0).also { leaves.write(packed) }
            }
        } else {
            root = entries
        }
        val rootBytes = pack(directory(root), internal)
        val meta = pack(metadata.toByteArray(Charsets.UTF_8), internal)

        val headerSize = 127L
        val rootOffset = headerSize
        val metaOffset = rootOffset + rootBytes.size
        val leafOffset = metaOffset + meta.size
        val dataOffset = leafOffset + leaves.size()
        val out = ByteArrayOutputStream()
        out.write("PMTiles".toByteArray(Charsets.US_ASCII))
        out.write(3)
        for (v in listOf(rootOffset, rootBytes.size.toLong(), metaOffset, meta.size.toLong(), leafOffset, leaves.size().toLong(), dataOffset, data.size().toLong())) le(out, v, 8)
        le(out, sorted.size.toLong(), 8); le(out, entries.size.toLong(), 8); le(out, sorted.size.toLong(), 8)
        out.write(1) // clustered
        out.write(internal); out.write(tileCompression); out.write(tileType)
        out.write(minZoom); out.write(maxZoom)
        le(out, (bbox[0] * 10_000_000).toLong(), 4); le(out, (bbox[1] * 10_000_000).toLong(), 4)
        le(out, (bbox[2] * 10_000_000).toLong(), 4); le(out, (bbox[3] * 10_000_000).toLong(), 4)
        out.write(maxZoom)
        le(out, ((bbox[0] + bbox[2]) / 2 * 10_000_000).toLong(), 4); le(out, ((bbox[1] + bbox[3]) / 2 * 10_000_000).toLong(), 4)
        check(out.size() == 127) { "Kopf ist ${out.size()} Byte" }
        out.write(rootBytes); out.write(meta); out.write(leaves.toByteArray()); out.write(data.toByteArray())
        return out.toByteArray()
    }
}
