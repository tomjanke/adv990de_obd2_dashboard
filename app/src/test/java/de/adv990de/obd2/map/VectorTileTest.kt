package de.adv990de.obd2.map

import de.adv990de.obd2.map.MvtTestWriter.Feature
import de.adv990de.obd2.map.MvtTestWriter.Layer
import de.adv990de.obd2.map.MvtTestWriter.SInt64
import de.adv990de.obd2.map.MvtTestWriter.UInt64
import de.adv990de.obd2.map.MvtTestWriter.geom
import de.adv990de.obd2.map.MvtTestWriter.line
import de.adv990de.obd2.map.MvtTestWriter.point
import de.adv990de.obd2.map.MvtTestWriter.polygon
import de.adv990de.obd2.map.MvtTestWriter.tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Der Dekoder fuer Vektorkacheln gegen von Hand kodierte Kacheln (Schreiber: [MvtTestWriter], ohne Code des Dekoders). */
class VectorTileTest {

    private fun IntArray.points(): List<Pair<Int, Int>> = toList().chunked(2).map { it[0] to it[1] }

    private fun decode(bytes: ByteArray) = decodeVectorTile(bytes)

    private fun single(layer: Layer) = decode(tile(layer))!!.layers.single()

    private fun layer(vararg f: Feature, name: String = "l", extent: Int? = 4096, keysFirst: Boolean = false) =
        Layer(name, f.toList(), extent = extent, keysBeforeFeatures = keysFirst)

    // ── Geometrie ─────────────────────────────────────────────────────────────

    @Test
    fun `a point`() {
        val f = single(layer(point(25, 17))).features.single()
        assertEquals(TileGeometryType.POINT, f.type)
        assertEquals(listOf(listOf(25 to 17)), f.parts.map { it.points() })
    }

    @Test
    fun `the example of the specification`() {
        // „[9 50 34]“: MoveTo(+25,+17) — ein Punkt bei (25, 17).
        val bytes = tile(Layer("l", listOf(Feature(1, geom { raw(9, 50, 34) })), extent = 4096))
        assertEquals(listOf(25 to 17), decode(bytes)!!.layers.single().features.single().parts.single().points())
    }

    @Test
    fun `a multipoint is one point per part`() {
        val f = single(layer(Feature(1, geom { moveTo(5 to 7, 3 to 2) }))).features.single()
        assertEquals(listOf(listOf(5 to 7), listOf(3 to 2)), f.parts.map { it.points() })
    }

    @Test
    fun `a linestring`() {
        val f = single(layer(line(2 to 2, 2 to 10, 10 to 10))).features.single()
        assertEquals(TileGeometryType.LINESTRING, f.type)
        assertEquals(listOf(listOf(2 to 2, 2 to 10, 10 to 10)), f.parts.map { it.points() })
    }

    @Test
    fun `a multilinestring has one part per line`() {
        val g = geom { moveTo(2 to 2); lineTo(2 to 10, 10 to 10); moveTo(1 to 1); lineTo(3 to 5) }
        val f = single(layer(Feature(2, g))).features.single()
        assertEquals(listOf(listOf(2 to 2, 2 to 10, 10 to 10), listOf(1 to 1, 3 to 5)), f.parts.map { it.points() })
    }

    @Test
    fun `a polygon is a closed ring without a repeated first point`() {
        val f = single(layer(polygon(listOf(3 to 6, 8 to 12, 20 to 34)))).features.single()
        assertEquals(TileGeometryType.POLYGON, f.type)
        assertEquals(listOf(listOf(3 to 6, 8 to 12, 20 to 34)), f.parts.map { it.points() })
    }

    @Test
    fun `a polygon with a hole has two rings`() {
        val outer = listOf(0 to 0, 10 to 0, 10 to 10, 0 to 10)
        val hole = listOf(2 to 2, 2 to 8, 8 to 8, 8 to 2)
        val f = single(layer(polygon(outer, hole))).features.single()
        assertEquals(listOf(outer, hole), f.parts.map { it.points() })
    }

    @Test
    fun `a multipolygon keeps every ring`() {
        val a = listOf(0 to 0, 10 to 0, 10 to 10, 0 to 10)
        val b = listOf(20 to 20, 30 to 20, 30 to 30, 20 to 30)
        assertEquals(listOf(a, b), single(layer(polygon(a, b))).features.single().parts.map { it.points() })
    }

    @Test
    fun `coordinates may be negative and beyond the extent`() {
        val f = single(layer(line(-50 to -20, 4200 to 5000))).features.single()
        assertEquals(listOf(listOf(-50 to -20, 4200 to 5000)), f.parts.map { it.points() })
    }

    @Test
    fun `steps are relative to the cursor and the cursor continues across commands`() {
        val g = geom { moveTo(100 to 100); lineTo(101 to 100, 101 to 101); moveTo(50 to 50); lineTo(51 to 51) }
        assertEquals(
            listOf(listOf(100 to 100, 101 to 100, 101 to 101), listOf(50 to 50, 51 to 51)),
            single(layer(Feature(2, g))).features.single().parts.map { it.points() },
        )
    }

    // ── Schichten, Tags, Werte ────────────────────────────────────────────────

    @Test
    fun `layers keep their names and extents and can be looked up`() {
        val t = decode(tile(layer(point(1, 1), name = "water", extent = 4096), layer(point(2, 2), name = "roads", extent = 512)))!!
        assertEquals(listOf("water", "roads"), t.layers.map { it.name })
        assertEquals(512, t.layer("roads")!!.extent)
        assertNull(t.layer("nothing"))
    }

    @Test
    fun `a missing extent is 4096`() {
        assertEquals(4096, single(layer(point(1, 1), extent = null)).extent)
    }

    @Test
    fun `tags of every value type`() {
        val tags = linkedMapOf<String, Any>("s" to "Wald", "i" to 42L, "u" to UInt64(7), "n" to SInt64(-5), "f" to 1.5f, "d" to 2.25, "b" to true, "c" to false)
        val f = single(layer(point(1, 1).let { Feature(1, geom { moveTo(1 to 1) }, tags) })).features.single()
        assertEquals("Wald", f.string("s"))
        assertEquals(42L, f.tags["i"])
        assertEquals(7L, f.tags["u"])
        assertEquals(-5L, f.tags["n"])
        assertEquals(1.5, f.tags["f"] as Double, 0.0)
        assertEquals(2.25, f.tags["d"] as Double, 0.0)
        assertEquals(true, f.tags["b"])
        assertEquals(false, f.tags["c"])
    }

    @Test
    fun `keys and values may come before or after the features`() {
        val tags = mapOf("kind" to "forest", "rank" to 3L)
        for (first in listOf(false, true)) {
            val f = single(layer(Feature(1, geom { moveTo(1 to 1) }, tags), keysFirst = first)).features.single()
            assertEquals("first=$first", tags, f.tags)
        }
    }

    @Test
    fun `tags and geometry that are not packed are read too`() {
        val g = geom { moveTo(4 to 4); lineTo(8 to 8) }
        val f = single(layer(Feature(2, g, mapOf("k" to "v"), packed = false))).features.single()
        assertEquals(listOf(4 to 4, 8 to 8), f.parts.single().points())
        assertEquals("v", f.string("k"))
    }

    @Test
    fun `unknown fields are skipped`() {
        val t = decode(tile(Layer("l", listOf(point(1, 1)), unknownFields = true), unknownTileFields = true))!!
        assertEquals(1, t.layers.single().features.size)
    }

    @Test
    fun `an empty tile has no layers`() {
        assertEquals(0, decode(ByteArray(0))!!.layers.size)
    }

    // ── Ungueltige Merkmale fallen weg, der Rest bleibt ───────────────────────

    private fun withBad(bad: Feature): TileLayer = single(layer(point(1, 1), bad, point(2, 2)))

    @Test
    fun `a feature with an odd number of tags is dropped`() {
        val l = withBad(Feature(1, geom { moveTo(5 to 5) }, rawTags = listOf(0L)))
        assertEquals(2, l.features.size)
    }

    @Test
    fun `a feature with a tag index outside the tables is dropped`() {
        val l = withBad(Feature(1, geom { moveTo(5 to 5) }, rawTags = listOf(3L, 4L)))
        assertEquals(2, l.features.size)
    }

    @Test
    fun `a feature of unknown type is dropped`() {
        assertEquals(2, withBad(Feature(0, geom { moveTo(5 to 5) })).features.size)
        assertEquals(2, withBad(Feature(9, geom { moveTo(5 to 5) })).features.size)
    }

    @Test
    fun `broken geometry drops the feature`() {
        val cases = mapOf(
            "LineTo ohne MoveTo" to Feature(2, geom { lineTo(1 to 1) }),
            "MoveTo mit Anzahl 2 bei einer Linie" to Feature(2, geom { moveTo(1 to 1, 2 to 2) }),
            "ClosePath mit Anzahl 2" to Feature(3, geom { moveTo(0 to 0); lineTo(5 to 0, 5 to 5); close(2) }),
            "ClosePath bei einer Linie" to Feature(2, geom { moveTo(0 to 0); lineTo(5 to 0); close() }),
            "ClosePath ohne Ring" to Feature(3, geom { close() }),
            "Ring ohne ClosePath" to Feature(3, geom { moveTo(0 to 0); lineTo(5 to 0, 5 to 5) }),
            "unbekannter Befehl 3" to Feature(2, geom { raw(command(3, 1), 0, 0) }),
            "Parameter fehlen" to Feature(2, geom { raw(command(1, 1), 0) }),
            "Anzahl groesser als der Rest" to Feature(2, geom { raw(command(1, 5), 0, 0) }),
            "Anzahl 0" to Feature(2, geom { raw(command(1, 0)) }),
            "Koordinate zu gross" to Feature(2, geom { moveTo((1 shl 24) to 0); lineTo(((1 shl 24) + 1) to 0) }),
        )
        for ((name, bad) in cases) assertEquals(name, 2, withBad(bad).features.size)
    }

    private fun command(id: Int, count: Int) = MvtTestWriter.command(id, count)

    @Test
    fun `parts that are too small are left out and the other parts stay`() {
        // Eine Linie mit einem Punkt und ein Ring mit zwei Punkten: die guten Teile bleiben.
        val lines = Feature(2, geom { moveTo(1 to 1); moveTo(5 to 5); lineTo(9 to 9) })
        assertEquals(listOf(listOf(5 to 5, 9 to 9)), single(layer(lines)).features.single().parts.map { it.points() })
        val rings = Feature(3, geom { moveTo(0 to 0); lineTo(3 to 3); close(); moveTo(10 to 10); lineTo(20 to 10, 20 to 20); close() })
        assertEquals(listOf(listOf(10 to 10, 20 to 10, 20 to 20)), single(layer(rings)).features.single().parts.map { it.points() })
    }

    // ── Schichten mit besonderen Eigenschaften ────────────────────────────────

    @Test
    fun `a layer of an unknown version is skipped and the rest stays`() {
        val bytes = tile(Layer("skipped", listOf(point(1, 1)), version = 3), Layer("kept", listOf(point(2, 2)), version = 1))
        assertEquals(listOf("kept"), decode(bytes)!!.layers.map { it.name })
    }

    @Test
    fun `a layer without a name makes the tile unusable`() {
        assertNull(decode(tile(Layer(null, listOf(point(1, 1))))))
    }

    @Test
    fun `an absurd extent makes the tile unusable`() {
        assertNull(decode(tile(Layer("l", listOf(point(1, 1)), extent = 0))))
        assertNull(decode(tile(Layer("l", listOf(point(1, 1)), extent = (1 shl 20) + 1))))
    }

    // ── Kaputte Eingaben ──────────────────────────────────────────────────────

    @Test
    fun `truncated bytes are null`() {
        val full = tile(layer(line(1 to 1, 5 to 5, tags = mapOf("k" to "v"))))
        for (cut in 1 until full.size) {
            // Ein Abschnitt, der zufaellig an einer Feldgrenze endet, ist eine gueltige (kuerzere) Kachel; sonst null. Wichtig: nie eine Ausnahme.
            decode(full.copyOf(cut))
        }
        assertNull(decode(full.copyOf(full.size - 1)))
    }

    @Test
    fun `a length beyond the rest is null`() {
        // Feld 3 (Schicht), Laenge 100, aber nur 2 Bytes dahinter.
        assertNull(decode(byteArrayOf(0x1A, 100, 1, 2)))
    }

    @Test
    fun `a group wire type is null`() {
        assertNull(decode(byteArrayOf(0x0B))) // Feld 1, Wire-Typ 3
    }

    @Test
    fun `a varint that never ends is null`() {
        assertNull(decode(ByteArray(12) { 0xFF.toByte() }))
    }

    @Test
    fun `a varint of eleven bytes is null even if it ends`() {
        // Ein Merkmal, dessen (ueberlesene) Kennung ein 11-Byte-Varint ist: zehn Bytes mit gesetztem Folgebit, das zehnte mit dem Wert 1, dann das Ende.
        val id = ByteArray(9) { 0x80.toByte() } + byteArrayOf(0x81.toByte(), 0x00)
        val feature = MvtTestWriter.Proto().apply {
            out.write(0x08); out.write(id)
            varintField(3, 1)
            bytesField(4, byteArrayOf(9, 2, 2))
        }.bytes()
        val layer = MvtTestWriter.Proto().apply { stringField(1, "l"); bytesField(2, feature) }.bytes()
        val tile = MvtTestWriter.Proto().apply { bytesField(3, layer) }.bytes()
        assertNull(decode(tile))
        // Dieselbe Kachel mit einer kurzen Kennung ist gueltig (die Kachel selbst ist in Ordnung).
        val ok = MvtTestWriter.Proto().apply {
            varintField(1, 5); varintField(3, 1); bytesField(4, byteArrayOf(9, 2, 2))
        }.bytes()
        val okLayer = MvtTestWriter.Proto().apply { stringField(1, "l"); bytesField(2, ok) }.bytes()
        assertEquals(1, decode(MvtTestWriter.Proto().apply { bytesField(3, okLayer) }.bytes())!!.layers.single().features.size)
    }

    @Test
    fun `field number zero is null`() {
        assertNull(decode(byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun `too many points make the tile unusable`() {
        val bytes = tile(layer(line(*Array(10) { it to it })))
        assertNotNull(decodeVectorTile(bytes, maxPoints = 10, maxFeaturesPerLayer = 100))
        assertNull(decodeVectorTile(bytes, maxPoints = 9, maxFeaturesPerLayer = 100))
    }

    @Test
    fun `too many features make the tile unusable`() {
        val bytes = tile(layer(*Array(5) { point(it, it) }))
        assertNotNull(decodeVectorTile(bytes, maxPoints = 100, maxFeaturesPerLayer = 5))
        assertNull(decodeVectorTile(bytes, maxPoints = 100, maxFeaturesPerLayer = 4))
    }

    @Test
    fun `too many layers make the tile unusable`() {
        val layers = Array(MAX_LAYERS + 1) { Layer("l$it", listOf(point(1, 1))) }
        assertNull(decode(tile(*layers)))
        assertNotNull(decode(tile(*layers.copyOf(MAX_LAYERS).requireNoNulls())))
    }

    @Test
    fun `random damage never throws`() {
        val rich = tile(
            layer(
                point(10, 10, mapOf("kind" to "peak", "ele" to 1200L)),
                line(0 to 0, 100 to 100, 200 to 50, tags = mapOf("kind" to "track")),
                polygon(listOf(0 to 0, 50 to 0, 50 to 50, 0 to 50), listOf(10 to 10, 10 to 40, 40 to 40, 40 to 10), tags = mapOf("kind" to "forest")),
                name = "landuse",
            ),
            Layer("water", listOf(polygon(listOf(5 to 5, 90 to 5, 90 to 90))), unknownFields = true),
        )
        assertNotNull(decode(rich))
        val rnd = Random(2026_11_22)
        repeat(5_000) {
            val damaged = rich.copyOf()
            repeat(1 + rnd.nextInt(4)) { damaged[rnd.nextInt(damaged.size)] = rnd.nextInt(256).toByte() }
            val bytes = if (rnd.nextInt(4) == 0) damaged.copyOf(rnd.nextInt(damaged.size + 1)) else damaged
            decode(bytes) // darf null oder eine Kachel liefern, nie eine Ausnahme
        }
        repeat(2_000) {
            decode(ByteArray(rnd.nextInt(200)).also(rnd::nextBytes))
        }
    }

    @Test
    fun `a rich tile survives decoding in every part`() {
        val bytes = tile(
            layer(
                polygon(listOf(0 to 0, 50 to 0, 50 to 50, 0 to 50), listOf(10 to 10, 10 to 40, 40 to 40, 40 to 10), tags = mapOf("kind" to "forest", "name" to "Hardt")),
                name = "landuse",
            ),
        )
        val f = decode(bytes)!!.layer("landuse")!!.features.single()
        assertEquals("forest", f.string("kind"))
        assertEquals("Hardt", f.string("name"))
        assertEquals(2, f.parts.size)
        assertTrue(f.parts.all { it.size == 8 })
    }
}
