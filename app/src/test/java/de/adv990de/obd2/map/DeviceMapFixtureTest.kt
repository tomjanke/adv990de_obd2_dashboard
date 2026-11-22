package de.adv990de.obd2.map

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File

class DeviceMapFixtureTest {
    private val asset = File(projectRoot, "app/src/androidTest/assets/map/sample.pmtiles")

    @Test
    fun `the committed sample map is what the fixture builds`() {
        if (System.getenv("REGENERATE_MAP_FIXTURE") == "1") asset.writeBytes(DeviceMapFixture.bytes())
        assertTrue("Datei fehlt: mit REGENERATE_MAP_FIXTURE=1 neu schreiben", asset.isFile)
        assertTrue("Die Datei weicht vom Aufbau ab: mit REGENERATE_MAP_FIXTURE=1 neu schreiben", asset.readBytes().contentEquals(DeviceMapFixture.bytes()))
    }

    @Test
    fun `the centre of the middle tile lies in the middle tile and the block lies inside the box`() {
        assertEquals(DeviceMapFixture.center(), tileContaining(DeviceMapFixture.centerLat(), DeviceMapFixture.centerLon(), DeviceMapFixture.ZOOM))
        val b = DeviceMapFixture.bbox()
        assertTrue(b[0] < DeviceMapFixture.centerLat() && DeviceMapFixture.centerLat() < b[2])
        assertTrue(b[1] < DeviceMapFixture.centerLon() && DeviceMapFixture.centerLon() < b[3])
    }

    @Test
    fun `the sample map opens and holds the nine tiles around the place`() = runBlocking {
        val archive = PmTilesArchive.open(ByteArraySource(DeviceMapFixture.bytes()))
        assertNotNull(archive)
        assertTrue(archive!!.supported)
        val c = DeviceMapFixture.center()
        for (dx in -1..1) for (dy in -1..1) assertNotNull(archive.tile(TileKey(DeviceMapFixture.ZOOM, c.x + dx, c.y + dy)))
        assertEquals(null, archive.tile(TileKey(DeviceMapFixture.ZOOM, c.x + 2, c.y)))
        val tile = decodeVectorTile(archive.tile(c)!!)!!
        assertEquals(listOf("landuse", "water", "roads"), tile.layers.map { it.name })
    }
}
