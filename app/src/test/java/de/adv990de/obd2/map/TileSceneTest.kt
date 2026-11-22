package de.adv990de.obd2.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSceneTest {
    private val palette = MapPalette(
        water = 0xFF0000FF.toInt(), wood = 0xFF00AA00.toInt(), grass = 0xFF66CC66.toInt(), farm = 0xFFCCCC66.toInt(), scrub = 0xFF99AA55.toInt(),
        roadHighway = 0xFFFFFFFF.toInt(), roadMajor = 0xFFEEEEEE.toInt(), roadMinor = 0xFFDDDDDD.toInt(), roadService = 0xFFCCCCCC.toInt(),
        roadTrack = 0xFFBBBBBB.toInt(), roadPath = 0xFFAAAAAA.toInt(), rail = 0xFF999999.toInt(),
    )
    private val style = MapStyle(palette, MapProfile.TRACK_VIEW)

    private fun polygon(kind: String, vararg rings: IntArray) = TileFeature(TileGeometryType.POLYGON, mapOf("kind" to kind), rings.toList())
    private fun road(kind: String, vararg lines: IntArray) = TileFeature(TileGeometryType.LINESTRING, mapOf("kind" to kind), lines.toList())
    private val square = intArrayOf(0, 0, 100, 0, 100, 100, 0, 100)

    @Test
    fun `an empty tile gives an empty scene`() {
        val scene = buildTileScene(VectorTile(emptyList()), style, 14.0)
        assertTrue(scene.isEmpty)
        assertEquals(4096, scene.extent)
    }

    @Test
    fun `the extent is the one of the layers`() {
        val tile = VectorTile(listOf(TileLayer("landuse", 512, listOf(polygon("forest", square)))))
        assertEquals(512, buildTileScene(tile, style, 14.0).extent)
    }

    @Test
    fun `features of one operation share a group and keep every part`() {
        val tile = VectorTile(listOf(TileLayer("landuse", 4096, listOf(polygon("forest", square), polygon("wood", square, intArrayOf(10, 10, 20, 10, 20, 20))))))
        val scene = buildTileScene(tile, style, 14.0)
        assertEquals(1, scene.groups.size)
        assertEquals(DrawOp.Fill(palette.wood, 23), scene.groups[0].op)
        assertEquals(3, scene.groups[0].parts.size)
        assertEquals(listOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f), scene.groups[0].parts[0].toList())
    }

    @Test
    fun `groups are sorted from below to above whatever the order in the tile`() {
        val tile = VectorTile(
            listOf(
                TileLayer("roads", 4096, listOf(road("highway", intArrayOf(0, 0, 50, 50)))),
                TileLayer("landuse", 4096, listOf(polygon("forest", square))),
                TileLayer("water", 4096, listOf(polygon("x", square) .let { TileFeature(TileGeometryType.POLYGON, emptyMap(), it.parts) })),
            ),
        )
        val orders = buildTileScene(tile, style, 14.0).groups.map { it.op.order }
        assertEquals(orders.sorted(), orders)
        assertEquals(3, orders.size)
    }

    @Test
    fun `lines of different width are different groups`() {
        val tile = VectorTile(listOf(TileLayer("roads", 4096, listOf(road("highway", intArrayOf(0, 0, 50, 50)), road("minor_road", intArrayOf(0, 0, 50, 50))))))
        val groups = buildTileScene(tile, style, 14.0).groups
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.op is DrawOp.Line })
    }

    @Test
    fun `what the style does not draw is missing`() {
        val tile = VectorTile(listOf(TileLayer("buildings", 4096, listOf(polygon("x", square))), TileLayer("earth", 4096, listOf(polygon("x", square)))))
        assertTrue(buildTileScene(tile, style, 14.0).isEmpty)
    }

    @Test
    fun `parts with too few points are dropped`() {
        val tile = VectorTile(
            listOf(
                TileLayer("landuse", 4096, listOf(polygon("forest", intArrayOf(0, 0, 10, 10), square))),
                TileLayer("roads", 4096, listOf(road("minor_road", intArrayOf(5, 5), intArrayOf(0, 0, 9, 9)))),
            ),
        )
        val groups = buildTileScene(tile, style, 14.0).groups
        assertEquals(2, groups.size)
        assertEquals(1, groups.first { it.op is DrawOp.Fill }.parts.size) // ein Ring mit zwei Punkten ist keine Flaeche
        assertEquals(1, groups.first { it.op is DrawOp.Line }.parts.size) // ein Linienzug mit einem Punkt ist keine Linie
    }

    @Test
    fun `a line with exactly two points and a ring with exactly three are kept`() {
        val tile = VectorTile(
            listOf(
                TileLayer("landuse", 4096, listOf(polygon("forest", intArrayOf(0, 0, 10, 0, 5, 9)))),
                TileLayer("roads", 4096, listOf(road("minor_road", intArrayOf(0, 0, 9, 9)))),
            ),
        )
        assertEquals(2, buildTileScene(tile, style, 14.0).groups.size)
    }

    @Test
    fun `the line width follows the zoom that was given`() {
        val tile = VectorTile(listOf(TileLayer("roads", 4096, listOf(road("minor_road", intArrayOf(0, 0, 9, 9))))))
        val low = (buildTileScene(tile, style, 10.0).groups[0].op as DrawOp.Line).widthDp
        val high = (buildTileScene(tile, style, 17.0).groups[0].op as DrawOp.Line).widthDp
        assertTrue(high > low)
    }
}
