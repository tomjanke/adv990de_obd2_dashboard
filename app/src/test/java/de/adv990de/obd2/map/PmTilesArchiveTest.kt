package de.adv990de.obd2.map

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Der PMTiles-Leser gegen selbstgebaute Archive nach der Spezifikation (der Kodierer steht in [PmTilesTestWriter] und teilt keinen Code mit dem Leser). */
class PmTilesArchiveTest {

    private fun tile(z: Int, x: Int, y: Int, content: String = "t$z/$x/$y") = PmTilesTestWriter.Tile(TileKey(z, x, y), content.toByteArray())

    private fun open(bytes: ByteArray) = runBlocking { PmTilesArchive.open(ByteArraySource(bytes)) }

    private fun read(a: PmTilesArchive, key: TileKey) = runBlocking { a.tile(key) }

    private val sample = listOf(tile(0, 0, 0), tile(1, 0, 0), tile(1, 1, 1), tile(2, 3, 2), tile(5, 17, 11), tile(12, 3423, 1763))

    // ── Kopf ──────────────────────────────────────────────────────────────────

    @Test
    fun `the header is read`() {
        val a = open(PmTilesTestWriter.archive(sample, minZoom = 0, maxZoom = 12))!!
        assertTrue(a.supported)
        assertEquals(PmTileType.MVT, a.header.tileType)
        assertEquals(PmCompression.GZIP, a.header.internalCompression)
        assertEquals(PmCompression.GZIP, a.header.tileCompression)
        assertEquals(0, a.header.minZoom)
        assertEquals(12, a.header.maxZoom)
        assertEquals(9.0, a.header.minLon, 1e-6)
        assertEquals(49.0, a.header.minLat, 1e-6)
        assertEquals(11.0, a.header.maxLon, 1e-6)
        assertEquals(51.0, a.header.maxLat, 1e-6)
    }

    @Test
    fun `a wrong magic, a wrong version or a short file is no archive`() {
        val good = PmTilesTestWriter.archive(sample)
        assertNull(open(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(open(good.copyOf().also { it[7] = 2 }))
        assertNull(open(good.copyOf().also { it[7] = 4 }))
        assertNull(open(good.copyOf(126)))
        assertNull(open(ByteArray(0)))
    }

    @Test
    fun `a header with a length above the range of a long is refused`() {
        val bad = PmTilesTestWriter.archive(sample).copyOf()
        for (i in 8 until 16) bad[i] = 0xFF.toByte() // Wurzelverzeichnis-Versatz = 2^64 - 1
        assertNull(open(bad))
    }

    @Test
    fun `a maximum zoom below the minimum zoom is refused`() {
        val bad = PmTilesTestWriter.archive(sample).copyOf()
        bad[100] = 9; bad[101] = 3
        assertNull(open(bad))
    }

    @Test
    fun `raster tiles and unknown packings are opened but not supported`() {
        assertFalse(open(PmTilesTestWriter.archive(sample, tileType = 2))!!.supported) // PNG
        val brotli = PmTilesTestWriter.archive(sample).copyOf().also { it[98] = 3 } // Kachelpackung brotli
        val a = open(brotli)!!
        assertFalse(a.supported)
        assertNull(read(a, TileKey(0, 0, 0)))
        assertFalse(open(PmTilesTestWriter.archive(sample).copyOf().also { it[97] = 4 })!!.supported) // zstd
        assertFalse(open(PmTilesTestWriter.archive(sample).copyOf().also { it[97] = 0 })!!.supported) // unbekannt
    }

    // ── Kacheln ───────────────────────────────────────────────────────────────

    @Test
    fun `every tile of a flat archive is found`() {
        val a = open(PmTilesTestWriter.archive(sample))!!
        for (t in sample) assertArrayEquals("${t.key}", t.bytes, read(a, t.key))
    }

    @Test
    fun `every tile of an archive with leaf directories is found`() {
        val many = (0 until 8).flatMap { x -> (0 until 8).map { y -> tile(3, x, y) } } + sample
        val a = open(PmTilesTestWriter.archive(many, leafSize = 5))!!
        for (t in many) assertArrayEquals("${t.key}", t.bytes, read(a, t.key))
    }

    @Test
    fun `uncompressed directories and tiles work`() {
        val a = open(PmTilesTestWriter.archive(sample, internal = 1, tileCompression = 1))!!
        assertEquals(PmCompression.NONE, a.header.tileCompression)
        for (t in sample) assertArrayEquals("${t.key}", t.bytes, read(a, t.key))
    }

    @Test
    fun `a tile that is not in the archive is null`() {
        val a = open(PmTilesTestWriter.archive(sample))!!
        assertNull(read(a, TileKey(1, 0, 1)))
        assertNull(read(a, TileKey(12, 3423, 1764)))
        assertNull(read(a, TileKey(7, 1, 1)))
    }

    @Test
    fun `a tile outside the zoom range of the archive is null`() {
        val a = open(PmTilesTestWriter.archive(sample, minZoom = 1, maxZoom = 5))!!
        assertNull(read(a, TileKey(0, 0, 0)))
        assertNull(read(a, TileKey(12, 3423, 1763)))
        assertNotNull(read(a, TileKey(5, 17, 11)))
    }

    @Test
    fun `an invalid tile is null`() {
        val a = open(PmTilesTestWriter.archive(sample))!!
        assertNull(read(a, TileKey(1, 2, 0)))
        assertNull(read(a, TileKey(-1, 0, 0)))
    }

    @Test
    fun `a run of equal tiles is found for every tile of the run`() {
        // Zwei Eintraege: Kennung 5 mit Lauflaenge 3 (Kacheln 5, 6, 7 teilen dieselben Daten), dann Kennung 20.
        val shared = "shared".toByteArray()
        val entries = listOf(PmEntry(5, 0, shared.size.toLong(), 3), PmEntry(20, shared.size.toLong(), 1, 1))
        val data = shared + byteArrayOf(9)
        val bytes = rawArchive(entries, data)
        val a = open(bytes)!!
        // Kennung 5 ist Stufe 2 (Kachel 0/0), 6 und 7 die naechsten auf der Kurve.
        val ids = listOf(5L, 6L, 7L).map { id -> allTiles(2).first { tileId(it) == id } }
        for (k in ids) assertArrayEquals("$k", shared, read(a, k))
        assertNull(read(a, allTiles(2).first { tileId(it) == 8L }))
    }

    private fun allTiles(z: Int) = (0 until (1 shl z)).flatMap { x -> (0 until (1 shl z)).map { y -> TileKey(z, x, y) } }

    /**
     * Ein Archiv ohne Packung mit vorgegebenen Eintraegen (fuer Faelle, die der Kodierer nicht erzeugt): Wurzel, danach [leaf] (Blattverzeichnisse), danach [data]. Die Laengen im Kopf stehen
     * auf den wahren Werten, ausser [leafLengthInHeader] und [dataLengthInHeader] nennen etwas anderes.
     */
    private fun rawArchive(
        entries: List<PmEntry>,
        data: ByteArray,
        leaf: ByteArray = ByteArray(0),
        leafLengthInHeader: Long = leaf.size.toLong(),
        dataLengthInHeader: Long = data.size.toLong(),
    ): ByteArray {
        val root = PmTilesTestWriter.directory(entries)
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Long, n: Int) { for (i in 0 until n) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
        out.write("PMTiles".toByteArray()); out.write(3)
        val rootOffset = 127L
        val leafOffset = rootOffset + root.size
        val dataOffset = leafOffset + leaf.size
        for (v in listOf(rootOffset, root.size.toLong(), dataOffset, 0L, leafOffset, leafLengthInHeader, dataOffset, dataLengthInHeader)) le(v, 8)
        le(0, 8); le(0, 8); le(0, 8)
        out.write(1); out.write(1); out.write(1); out.write(1) // clustered, keine, keine, MVT
        out.write(0); out.write(15)
        repeat(16) { out.write(0) }
        out.write(0); repeat(8) { out.write(0) }
        check(out.size() == 127)
        out.write(root); out.write(leaf); out.write(data)
        return out.toByteArray()
    }

    @Test
    fun `a leaf directory outside the leaf section is refused even if the bytes exist`() {
        val tile = "x".toByteArray()
        val leaf = PmTilesTestWriter.directory(listOf(PmEntry(0, 0, tile.size.toLong(), 1)))
        val entries = listOf(PmEntry(0, 0, leaf.size.toLong(), 0))
        // Mit dem wahren Wert im Kopf wird die Kachel gefunden ...
        assertArrayEquals(tile, read(open(rawArchive(entries, tile, leaf))!!, TileKey(0, 0, 0)))
        // ... sagt der Kopf, der Abschnitt der Blattverzeichnisse sei kuerzer, liegt das Blatt ausserhalb.
        assertNull(read(open(rawArchive(entries, tile, leaf, leafLengthInHeader = leaf.size - 1L))!!, TileKey(0, 0, 0)))
    }

    @Test
    fun `a tile outside the tile data section is refused even if the bytes exist`() {
        val tile = "xyz".toByteArray()
        val entries = listOf(PmEntry(0, 0, tile.size.toLong(), 1))
        assertArrayEquals(tile, read(open(rawArchive(entries, tile))!!, TileKey(0, 0, 0)))
        assertNull(read(open(rawArchive(entries, tile, dataLengthInHeader = tile.size - 1L))!!, TileKey(0, 0, 0)))
    }

    // ── Metadaten ─────────────────────────────────────────────────────────────

    @Test
    fun `the metadata is read as text`() {
        val a = open(PmTilesTestWriter.archive(sample, metadata = """{"attribution":"© OpenStreetMap","vector_layers":[{"id":"water"}]}"""))!!
        val json = runBlocking { a.metadataJson() }!!
        assertTrue(json, json.contains("© OpenStreetMap"))
        assertTrue(json, json.contains("water"))
    }

    @Test
    fun `missing metadata is null`() {
        val a = open(PmTilesTestWriter.archive(sample).copyOf().also { for (i in 32 until 40) it[i] = 0 })!!
        assertNull(runBlocking { a.metadataJson() })
    }

    // ── Kaputte Eingaben ──────────────────────────────────────────────────────

    @Test
    fun `a truncated archive gives null instead of an exception`() {
        val full = PmTilesTestWriter.archive(sample)
        for (cut in listOf(130, 160, full.size - 20, full.size - 1)) {
            val a = open(full.copyOf(cut)) ?: continue
            for (t in sample) read(a, t.key) // darf null liefern, nie werfen
        }
    }

    @Test
    fun `a directory with absurd content is refused`() {
        // Anzahl 3, aber keine Daten dahinter.
        assertNull(parseDirectory(byteArrayOf(3)))
        // Anzahl 0.
        assertNull(parseDirectory(byteArrayOf(0)))
        // Absurde Anzahl (über der Grenze).
        val out = java.io.ByteArrayOutputStream().also { PmTilesTestWriter.varint(it, 10_000_000) }
        assertNull(parseDirectory(out.toByteArray()))
        // Absteigende Kennungen (Differenz 0 beim zweiten Eintrag) taugen nicht für die Binaersuche.
        val bad = PmTilesTestWriter.directory(listOf(PmEntry(5, 0, 1, 1), PmEntry(5, 1, 1, 1)))
        assertNull(parseDirectory(bad))
    }

    @Test
    fun `a first entry that continues a previous offset is refused`() {
        // Versatz-Wert 0 beim ersten Eintrag hiesse Versatz -1.
        val out = java.io.ByteArrayOutputStream()
        PmTilesTestWriter.varint(out, 1); PmTilesTestWriter.varint(out, 5); PmTilesTestWriter.varint(out, 1); PmTilesTestWriter.varint(out, 1); PmTilesTestWriter.varint(out, 0)
        assertNull(parseDirectory(out.toByteArray()))
    }

    @Test
    fun `a gzip bomb is refused`() {
        val bomb = PmTilesTestWriter.gzip(ByteArray(MAX_TILE_BYTES + 1))
        assertNull(unpack(bomb, PmCompression.GZIP, MAX_TILE_BYTES))
        assertNotNull(unpack(PmTilesTestWriter.gzip(ByteArray(MAX_TILE_BYTES)), PmCompression.GZIP, MAX_TILE_BYTES))
    }

    @Test
    fun `broken gzip is null`() {
        assertNull(unpack(byteArrayOf(1, 2, 3, 4), PmCompression.GZIP, 100))
        assertNull(unpack(ByteArray(0), PmCompression.GZIP, 100))
    }

    @Test
    fun `a leaf directory that points into itself ends after a few levels`() {
        // Wurzel: ein Blattverzeichnis-Eintrag (Lauflaenge 0) auf das Blattverzeichnis; das Blatt zeigt wieder auf sich.
        val leafEntries = listOf(PmEntry(0, 0, 0, 0)) // Laenge gleich der des Blatts, unten eingesetzt
        val leaf = PmTilesTestWriter.directory(listOf(PmEntry(0, 0, 8, 0)))
        val root = PmTilesTestWriter.directory(listOf(PmEntry(0, 0, leaf.size.toLong(), 0)))
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Long, n: Int) { for (i in 0 until n) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
        out.write("PMTiles".toByteArray()); out.write(3)
        val rootOffset = 127L
        val leafOffset = rootOffset + root.size
        for (v in listOf(rootOffset, root.size.toLong(), leafOffset, 0L, leafOffset, leaf.size.toLong(), leafOffset + leaf.size, 0L)) le(v, 8)
        le(0, 8); le(0, 8); le(0, 8)
        out.write(1); out.write(1); out.write(1); out.write(1)
        out.write(0); out.write(15)
        repeat(16) { out.write(0) }; out.write(0); repeat(8) { out.write(0) }
        out.write(root); out.write(leaf)
        assertTrue(leafEntries.isNotEmpty())
        val a = open(out.toByteArray())!!
        assertNull(read(a, TileKey(0, 0, 0))) // endet, statt endlos zu laufen
    }

    @Test
    fun `a source that fails gives null`() {
        val failing = object : RandomAccessSource {
            override suspend fun read(offset: Long, length: Int): ByteArray? = null
        }
        assertNull(runBlocking { PmTilesArchive.open(failing) })
        val good = PmTilesTestWriter.archive(sample)
        var calls = 0
        val failAfterHeader = object : RandomAccessSource {
            override suspend fun read(offset: Long, length: Int): ByteArray? = if (calls++ == 0) good.copyOfRange(0, length) else null
        }
        val a = runBlocking { PmTilesArchive.open(failAfterHeader) }!!
        assertNull(read(a, TileKey(0, 0, 0)))
    }

    @Test
    fun `the directories are read once`() {
        val good = PmTilesTestWriter.archive(sample)
        var reads = 0
        val counting = object : RandomAccessSource {
            override suspend fun read(offset: Long, length: Int): ByteArray? { reads++; return ByteArraySource(good).read(offset, length) }
        }
        val a = runBlocking { PmTilesArchive.open(counting) }!!
        read(a, TileKey(0, 0, 0))
        val afterFirst = reads
        read(a, TileKey(1, 0, 0))
        // Header + Wurzel + Kachel = 3; die zweite Kachel braucht nur noch ihre eigenen Bytes.
        assertEquals(3, afterFirst)
        assertEquals(afterFirst + 1, reads)
    }

    @Test
    fun `byte array source refuses ranges outside`() {
        val s = ByteArraySource(ByteArray(10))
        assertNull(runBlocking { s.read(-1, 1) })
        assertNull(runBlocking { s.read(5, 6) })
        assertNull(runBlocking { s.read(11, 0) })
        assertNull(runBlocking { s.read(0, -1) })
        assertEquals(10, runBlocking { s.read(0, 10) }!!.size)
        assertEquals(0, runBlocking { s.read(10, 0) }!!.size)
    }
}
