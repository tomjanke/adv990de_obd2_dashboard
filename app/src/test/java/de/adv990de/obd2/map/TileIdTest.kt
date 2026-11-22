package de.adv990de.obd2.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Kachel-Kennung nach PMTiles v3. Die Werte stehen in der Spezifikation (Abschnitt „Tile IDs“, gelesen am 2026-11-22). */
class TileIdTest {

    @Test
    fun `the values of the specification`() {
        assertEquals(0L, tileId(TileKey(0, 0, 0)))
        assertEquals(1L, tileId(TileKey(1, 0, 0)))
        assertEquals(2L, tileId(TileKey(1, 0, 1)))
        assertEquals(3L, tileId(TileKey(1, 1, 1)))
        assertEquals(4L, tileId(TileKey(1, 1, 0)))
        assertEquals(5L, tileId(TileKey(2, 0, 0)))
        assertEquals(19_078_479L, tileId(TileKey(12, 3423, 1763)))
    }

    @Test
    fun `every zoom level starts right after the previous one`() {
        // Stufe z hat 4^z Kacheln: die Kennungen einer Stufe liegen lueckenlos in [(4^z-1)/3, (4^(z+1)-1)/3).
        for (z in 0..5) {
            val ids = (0 until (1 shl z)).flatMap { x -> (0 until (1 shl z)).map { y -> tileId(TileKey(z, x, y))!! } }
            val first = ((1L shl (2 * z)) - 1) / 3
            assertEquals((first until first + (1L shl (2 * z))).toList(), ids.sorted())
        }
    }

    @Test
    fun `neighbours on the curve are neighbours on the map`() {
        // Eine Hilbert-Kurve springt nie: zwei aufeinanderfolgende Kennungen einer Stufe liegen in angrenzenden Kacheln.
        val z = 5
        val byId = (0 until (1 shl z)).flatMap { x -> (0 until (1 shl z)).map { y -> tileId(TileKey(z, x, y))!! to (x to y) } }.sortedBy { it.first }
        byId.zipWithNext().forEach { (a, b) ->
            val d = Math.abs(a.second.first - b.second.first) + Math.abs(a.second.second - b.second.second)
            assertEquals("von ${a.second} nach ${b.second}", 1, d)
        }
    }

    @Test
    fun `an invalid tile has no id`() {
        assertNull(tileId(TileKey(-1, 0, 0)))
        assertNull(tileId(TileKey(1, 2, 0)))
        assertNull(tileId(TileKey(1, 0, 2)))
        assertNull(tileId(TileKey(1, -1, 0)))
        assertNull(tileId(TileKey(MAX_TILE_ZOOM + 1, 0, 0)))
        assertTrue(tileId(TileKey(MAX_TILE_ZOOM, (1 shl MAX_TILE_ZOOM) - 1, 0)) != null)
    }
}
