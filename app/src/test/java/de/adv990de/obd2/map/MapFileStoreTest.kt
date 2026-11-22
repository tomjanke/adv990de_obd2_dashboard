package de.adv990de.obd2.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Die Karten-Datei im App-Ordner, mit echten Dateien im temporaeren Ordner und selbstgebauten PMTiles-Archiven. */
class MapFileStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir() = File(tmp.root, "maps")
    private fun store(space: Long = Long.MAX_VALUE / 4, max: Long = MAX_MAP_BYTES) = MapFileStore(dir(), usableSpace = { space }, maxBytes = max)

    private val tiles = listOf(PmTilesTestWriter.Tile(TileKey(0, 0, 0), "a".toByteArray()), PmTilesTestWriter.Tile(TileKey(5, 17, 11), "b".toByteArray()))
    private fun good(metadata: String = """{"name":"Testkarte","attribution":"© OpenStreetMap contributors","vector_layers":[]}""") =
        PmTilesTestWriter.archive(tiles, minZoom = 2, maxZoom = 14, metadata = metadata, bbox = doubleArrayOf(9.0, 49.0, 11.5, 51.5))

    private fun import(s: MapFileStore, bytes: ByteArray, size: Long? = bytes.size.toLong()) = runBlocking { s.import({ ByteArrayInputStream(bytes) }, size) }

    // ── Einspielen ────────────────────────────────────────────────────────────

    @Test
    fun `a valid file is imported and described`() {
        val s = store()
        val r = import(s, good())
        val info = (r as MapImportResult.Success).info
        assertEquals("Testkarte", info.name)
        assertEquals("© OpenStreetMap contributors", info.attribution)
        assertEquals(2, info.minZoom); assertEquals(14, info.maxZoom)
        assertEquals(9.0, info.minLon, 1e-6); assertEquals(49.0, info.minLat, 1e-6)
        assertEquals(11.5, info.maxLon, 1e-6); assertEquals(51.5, info.maxLat, 1e-6)
        assertEquals(good().size.toLong(), info.bytes)
        assertEquals(MapState.Ready(info), runBlocking { s.inspect() })
        assertArrayEquals(good(), File(dir(), MAP_FILE_NAME).readBytes())
        assertFalse(File(dir(), "map.pmtiles.tmp").exists())
    }

    @Test
    fun `the imported archive serves its tiles`() {
        val s = store()
        import(s, good())
        val a = runBlocking { s.archive() }!!
        assertArrayEquals("b".toByteArray(), runBlocking { a.tile(TileKey(5, 17, 11)) })
        assertNull(runBlocking { a.tile(TileKey(0, 0, 0)) }) // Stufe 0 liegt ausserhalb des Zoom-Bereichs 2 bis 14 dieser Datei
    }

    @Test
    fun `a file without metadata is imported with unknown name and attribution`() {
        val bytes = PmTilesTestWriter.archive(tiles).copyOf().also { for (i in 32 until 40) it[i] = 0 } // Laenge der Metadaten 0
        val info = (import(store(), bytes) as MapImportResult.Success).info
        assertNull(info.name); assertNull(info.attribution)
    }

    @Test
    fun `broken or deeply nested metadata is ignored but the map is still usable`() {
        val deep = "[".repeat(100_000)
        for (metadata in listOf("not json", "[1,2]", deep, "{\"name\":123,\"attribution\":null}")) {
            val r = import(store(), PmTilesTestWriter.archive(tiles, metadata = metadata))
            assertTrue(metadata.take(20), r is MapImportResult.Success)
        }
    }

    @Test
    fun `a file that is no archive is refused and nothing is left behind`() {
        val s = store()
        assertEquals(MapImportResult.Failure(MapProblem.NOT_PMTILES), import(s, "just some text".toByteArray()))
        assertEquals(MapImportResult.Failure(MapProblem.NOT_PMTILES), import(s, ByteArray(0), size = 0))
        assertEquals(MapState.NoMap, runBlocking { s.inspect() })
        assertEquals(emptyList<String>(), dir().list()!!.toList())
    }

    @Test
    fun `raster tiles and unreadable packings are refused with their reason`() {
        assertEquals(MapImportResult.Failure(MapProblem.NOT_VECTOR), import(store(), PmTilesTestWriter.archive(tiles, tileType = 2)))
        val brotli = PmTilesTestWriter.archive(tiles).copyOf().also { it[98] = 3 }
        assertEquals(MapImportResult.Failure(MapProblem.UNSUPPORTED_PACKING), import(store(), brotli))
    }

    @Test
    fun `a failing import keeps the map that was there`() {
        val s = store()
        import(s, good())
        val before = File(dir(), MAP_FILE_NAME).readBytes()
        assertEquals(MapImportResult.Failure(MapProblem.NOT_PMTILES), import(s, "garbage".toByteArray()))
        assertArrayEquals(before, File(dir(), MAP_FILE_NAME).readBytes())
        assertTrue(runBlocking { s.inspect() } is MapState.Ready)
        assertNotNull(runBlocking { s.archive() })
    }

    @Test
    fun `a second import replaces the first and the old reader is not used any more`() {
        val s = store()
        import(s, good())
        val first = runBlocking { s.archive() }!!
        val other = PmTilesTestWriter.archive(listOf(PmTilesTestWriter.Tile(TileKey(1, 1, 1), "zzz".toByteArray())), minZoom = 1, maxZoom = 1, metadata = """{"name":"Zweite"}""")
        val info = (import(s, other) as MapImportResult.Success).info
        assertEquals("Zweite", info.name)
        val second = runBlocking { s.archive() }!!
        assertTrue(first !== second)
        assertArrayEquals("zzz".toByteArray(), runBlocking { second.tile(TileKey(1, 1, 1)) })
        assertNull(runBlocking { second.tile(TileKey(0, 0, 0)) })
        assertEquals(other.size.toLong(), File(dir(), MAP_FILE_NAME).length())
    }

    @Test
    fun `the archive is cached`() {
        val s = store()
        import(s, good())
        assertTrue(runBlocking { s.archive() } === runBlocking { s.archive() })
    }

    // ── Grenzen und Platz ─────────────────────────────────────────────────────

    @Test
    fun `a source that says it is too big is refused before reading`() {
        var opened = false
        val r = runBlocking { store(max = 100).import({ opened = true; ByteArrayInputStream(ByteArray(10)) }, knownSize = 101) }
        assertEquals(MapImportResult.Failure(MapProblem.TOO_LARGE), r)
        assertFalse(opened)
    }

    @Test
    fun `a source exactly at the limit is accepted`() {
        val bytes = good()
        assertTrue(import(store(max = bytes.size.toLong()), bytes, size = bytes.size.toLong()) is MapImportResult.Success)
        assertEquals(MapImportResult.Failure(MapProblem.TOO_LARGE), import(store(max = bytes.size - 1L), bytes, size = bytes.size.toLong()))
    }

    @Test
    fun `a source of unknown size that turns out to be too big is refused while copying`() {
        val bytes = good()
        assertEquals(MapImportResult.Failure(MapProblem.TOO_LARGE), import(store(max = bytes.size - 1L), bytes, size = null))
        assertTrue(import(store(max = bytes.size.toLong()), bytes, size = null) is MapImportResult.Success)
        assertEquals(emptyList<String>(), File(dir(), "x").parentFile!!.list()!!.filter { it.endsWith(".tmp") })
    }

    @Test
    fun `not enough free space refuses before reading and keeps a margin`() {
        val bytes = good()
        var opened = false
        val tight = store(space = bytes.size + MAP_SPACE_MARGIN_BYTES - 1)
        assertEquals(MapImportResult.Failure(MapProblem.NO_SPACE), runBlocking { tight.import({ opened = true; ByteArrayInputStream(bytes) }, bytes.size.toLong()) })
        assertFalse(opened)
        assertTrue(import(store(space = bytes.size + MAP_SPACE_MARGIN_BYTES), bytes) is MapImportResult.Success)
    }

    @Test
    fun `a write that fails because the disk is full is NO_SPACE and one that fails otherwise is UNREADABLE`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("boom")
        }
        assertEquals(MapImportResult.Failure(MapProblem.NO_SPACE), runBlocking { store(space = 0).import({ failing }, null) })
        assertEquals(MapImportResult.Failure(MapProblem.UNREADABLE), runBlocking { store(space = Long.MAX_VALUE / 4).import({ failing }, null) })
        assertEquals(emptyList<String>(), dir().list()!!.toList())
    }

    @Test
    fun `a source that cannot be opened is UNREADABLE`() {
        assertEquals(MapImportResult.Failure(MapProblem.UNREADABLE), runBlocking { store().import({ null }, null) })
        assertEquals(MapImportResult.Failure(MapProblem.UNREADABLE), runBlocking { store().import({ throw IOException("no") }, null) })
    }

    // ── Abbruch ───────────────────────────────────────────────────────────────

    @Test
    fun `a cancelled import cleans up the half copy and keeps the old map`() {
        val s = store()
        import(s, good())
        val before = File(dir(), MAP_FILE_NAME).readBytes()
        val started = CompletableDeferred<Unit>()
        val stream = object : InputStream() {
            var calls = 0
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (calls++ == 0) { b.fill(1, off, off + 10); started.complete(Unit); return 10 }
                Thread.sleep(50); return 10 // langsam, bis der Abbruch kommt
            }
        }
        runBlocking {
            val job = launch(Dispatchers.Default) { s.import({ stream }, null) }
            started.await()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals(listOf(MAP_FILE_NAME), dir().list()!!.toList())
        assertArrayEquals(before, File(dir(), MAP_FILE_NAME).readBytes())
    }

    // ── Entfernen, Zustand ────────────────────────────────────────────────────

    @Test
    fun `without a file there is no map`() {
        val s = store()
        assertEquals(MapState.NoMap, runBlocking { s.inspect() })
        assertNull(runBlocking { s.archive() })
    }

    @Test
    fun `removing deletes the file, a leftover temp file and resets the reader`() {
        val s = store()
        import(s, good())
        assertNotNull(runBlocking { s.archive() })
        File(dir(), "map.pmtiles.tmp").writeText("leftover")
        runBlocking { s.remove() }
        assertEquals(emptyList<String>(), dir().list()!!.toList())
        assertEquals(MapState.NoMap, runBlocking { s.inspect() })
        assertNull(runBlocking { s.archive() })
        runBlocking { s.remove() } // ein zweites Mal ohne Datei: kein Fehler
    }

    @Test
    fun `a damaged file in the folder is reported as broken and gives no reader`() {
        val s = store()
        dir().mkdirs()
        File(dir(), MAP_FILE_NAME).writeBytes("not a map".toByteArray())
        assertEquals(MapState.Broken(MapProblem.NOT_PMTILES), runBlocking { s.inspect() })
        assertNull(runBlocking { s.archive() })
        // Eine gute Datei ersetzt sie.
        assertTrue(import(s, good()) is MapImportResult.Success)
        assertTrue(runBlocking { s.inspect() } is MapState.Ready)
    }

    @Test
    fun `a map file with raster tiles that was put there gives no reader`() {
        dir().mkdirs()
        File(dir(), MAP_FILE_NAME).writeBytes(PmTilesTestWriter.archive(tiles, tileType = 2))
        val s = store()
        assertEquals(MapState.Broken(MapProblem.NOT_VECTOR), runBlocking { s.inspect() })
        assertNull(runBlocking { s.archive() })
        File(dir(), MAP_FILE_NAME).writeBytes(PmTilesTestWriter.archive(tiles).copyOf().also { it[98] = 3 })
        assertNull(runBlocking { store().archive() })
    }

    @Test
    fun `metadata text is trimmed and blank text counts as missing`() {
        val padded = (import(store(), PmTilesTestWriter.archive(tiles, metadata = """{"name":"  Karte ","attribution":"  "}""")) as MapImportResult.Success).info
        assertEquals("Karte", padded.name)
        assertNull(padded.attribution)
        assertEquals(null to null, parseMetadata("""{"name":"","attribution":""}"""))
        assertEquals(null to null, parseMetadata(null))
    }

    @Test
    fun `metadata nested beyond the guard is ignored even where the parser would cope`() {
        val deep = """{"name":"Tief","x":""" + "[".repeat(50) + "]".repeat(50) + "}"
        assertEquals(null to null, parseMetadata(deep))
        assertEquals("Flach" to null, parseMetadata("""{"name":"Flach","x":[[1]]}"""))
    }

    @Test
    fun `a stale temp file does not disturb an import`() {
        val s = store()
        dir().mkdirs()
        File(dir(), "map.pmtiles.tmp").writeText("stale")
        assertTrue(import(s, good()) is MapImportResult.Success)
        assertFalse(File(dir(), "map.pmtiles.tmp").exists())
    }

    @Test
    fun `two imports at the same time do not mix`() {
        val s = store()
        val a = good()
        val b = PmTilesTestWriter.archive(tiles, metadata = """{"name":"B"}""")
        runBlocking {
            val ra = async(Dispatchers.Default) { s.import({ ByteArrayInputStream(a) }, a.size.toLong()) }
            val rb = async(Dispatchers.Default) { s.import({ ByteArrayInputStream(b) }, b.size.toLong()) }
            assertTrue(ra.await() is MapImportResult.Success); assertTrue(rb.await() is MapImportResult.Success)
        }
        val bytes = File(dir(), MAP_FILE_NAME).readBytes()
        assertTrue(bytes.contentEquals(a) || bytes.contentEquals(b))
        assertEquals(listOf(MAP_FILE_NAME), dir().list()!!.toList())
    }

    // ── Dateiquelle ───────────────────────────────────────────────────────────

    @Test
    fun `the file source reads ranges and refuses what lies outside`() {
        val f = tmp.newFile().also { it.writeBytes(ByteArray(100) { i -> i.toByte() }) }
        FileSource(f).use { s ->
            assertArrayEquals(ByteArray(10) { (it + 20).toByte() }, runBlocking { s.read(20, 10) })
            assertEquals(0, runBlocking { s.read(100, 0) }!!.size)
            assertNull(runBlocking { s.read(95, 6) })
            assertNull(runBlocking { s.read(-1, 1) })
            assertNull(runBlocking { s.read(0, -1) })
        }
    }

    @Test
    fun `a closed file source gives null`() {
        val f = tmp.newFile().also { it.writeBytes(ByteArray(10)) }
        val s = FileSource(f)
        s.close()
        assertNull(runBlocking { s.read(0, 5) })
    }

    // ── Abdeckung ─────────────────────────────────────────────────────────────

    private val info = MapInfo("x", null, minLon = 9.0, minLat = 49.0, maxLon = 11.0, maxLat = 51.0, minZoom = 0, maxZoom = 14, bytes = 1)

    @Test
    fun `coverage of a point`() {
        assertTrue(info.covers(50.0, 10.0))
        assertTrue(info.covers(49.0, 9.0)) // Rand zaehlt
        assertFalse(info.covers(48.9, 10.0))
        assertFalse(info.covers(50.0, 11.1))
    }

    @Test
    fun `coverage of a rectangle`() {
        assertTrue(info.covers(49.5, 9.5, 50.5, 10.5))
        assertFalse(info.covers(48.5, 9.5, 50.5, 10.5)) // ragt nach Sueden hinaus
        assertFalse(info.covers(49.5, 8.5, 50.5, 10.5)) // ragt nach Westen hinaus
        assertFalse(info.covers(49.5, 9.5, 51.5, 10.5)) // Norden
        assertFalse(info.covers(49.5, 9.5, 50.5, 11.5)) // Osten
        assertTrue(info.overlaps(48.0, 8.0, 49.5, 9.5)) // beruehrt
        assertFalse(info.overlaps(47.0, 8.0, 48.9, 8.9))
        assertFalse(info.overlaps(51.1, 9.5, 52.0, 10.5))
        assertFalse(info.overlaps(49.5, 11.1, 50.5, 12.0)) // Osten daneben
        assertFalse(info.overlaps(49.5, 7.0, 50.5, 8.9)) // Westen daneben
    }

}
