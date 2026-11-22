package de.adv990de.obd2.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Der Stil: welches Merkmal welche Anweisung bekommt (Schichten und Werte der Protomaps-Basiskarte, Fassung 4). */
class MapStyleTest {

    // Jede Farbe verschieden, damit ein Test sieht, welche gemeint ist.
    private val palette = MapPalette(
        water = 0xFF000001.toInt(), wood = 0xFF000002.toInt(), grass = 0xFF000003.toInt(), farm = 0xFF000004.toInt(), scrub = 0xFF000005.toInt(),
        roadHighway = 0xFF000011.toInt(), roadMajor = 0xFF000012.toInt(), roadMinor = 0xFF000013.toInt(), roadService = 0xFF000014.toInt(),
        roadTrack = 0xFF000015.toInt(), roadPath = 0xFF000016.toInt(), rail = 0xFF000017.toInt(),
    )
    private val view = MapStyle(palette, MapProfile.TRACK_VIEW)
    private val hud = MapStyle(palette, MapProfile.HUD)

    private fun feature(type: TileGeometryType, vararg tags: Pair<String, Any>) = TileFeature(type, mapOf(*tags), emptyList())
    private fun poly(vararg tags: Pair<String, Any>) = feature(TileGeometryType.POLYGON, *tags)
    private fun line(vararg tags: Pair<String, Any>) = feature(TileGeometryType.LINESTRING, *tags)

    private fun fill(style: MapStyle, layer: String, f: TileFeature) = style.op(layer, f, 15.0) as? DrawOp.Fill
    private fun stroke(style: MapStyle, layer: String, f: TileFeature, zoom: Double = 15.0) = style.op(layer, f, zoom) as? DrawOp.Line

    // ── Wald und Wiese ────────────────────────────────────────────────────────

    @Test
    fun `forest and wood are wood in both profiles`() {
        for (kind in listOf("forest", "wood")) for (style in listOf(view, hud)) assertEquals("$kind ${style.profile}", palette.wood, fill(style, "landuse", poly("kind" to kind))?.argb)
    }

    @Test
    fun `meadow, grass and parks are grass in both profiles`() {
        for (kind in listOf("meadow", "grass", "park", "recreation_ground", "garden", "dog_park")) for (style in listOf(view, hud)) {
            assertEquals("$kind ${style.profile}", palette.grass, fill(style, "landuse", poly("kind" to kind))?.argb)
        }
    }

    @Test
    fun `farmland and scrub are drawn in the track view only`() {
        for (kind in listOf("farmland", "farmyard", "orchard")) {
            assertEquals(palette.farm, fill(view, "landuse", poly("kind" to kind))?.argb)
            assertNull(kind, hud.op("landuse", poly("kind" to kind), 15.0))
        }
        assertEquals(palette.scrub, fill(view, "landuse", poly("kind" to "scrub"))?.argb)
        assertNull(hud.op("landuse", poly("kind" to "scrub"), 15.0))
    }

    @Test
    fun `settlements, protected areas and unknown values stay ground`() {
        for (kind in listOf("residential", "commercial", "industrial", "national_park", "nature_reserve", "protected_area", "school", "pitch", "cemetery", "wetland", "other", "invented")) {
            assertNull(kind, view.op("landuse", poly("kind" to kind), 15.0))
        }
        assertNull(view.op("landuse", poly(), 15.0)) // ohne kind
    }

    @Test
    fun `landcover is the coarse version of the same areas`() {
        assertEquals(palette.wood, fill(view, "landcover", poly("kind" to "forest"))?.argb)
        assertEquals(palette.grass, fill(hud, "landcover", poly("kind" to "grassland"))?.argb)
        assertEquals(palette.farm, fill(view, "landcover", poly("kind" to "farmland"))?.argb)
        assertNull(hud.op("landcover", poly("kind" to "farmland"), 5.0))
        for (kind in listOf("barren", "glacier", "urban_area")) assertNull(kind, view.op("landcover", poly("kind" to kind), 5.0))
    }

    @Test
    fun `areas that are not polygons are not drawn`() {
        assertNull(view.op("landuse", line("kind" to "forest"), 15.0))
        assertNull(view.op("landuse", feature(TileGeometryType.POINT, "kind" to "forest"), 15.0))
        assertNull(view.op("landcover", line("kind" to "forest"), 5.0))
    }

    // ── Wasser ────────────────────────────────────────────────────────────────

    @Test
    fun `water polygons are filled and rivers and streams are lines`() {
        for (style in listOf(view, hud)) {
            assertEquals(palette.water, fill(style, "water", poly("kind" to "lake"))?.argb)
            assertEquals(palette.water, fill(style, "water", poly("kind" to "ocean"))?.argb)
            assertEquals(palette.water, stroke(style, "water", line("kind_detail" to "river"))?.argb)
            assertEquals(palette.water, stroke(style, "water", line("kind_detail" to "stream"))?.argb)
            assertEquals(palette.water, stroke(style, "water", line("kind_detail" to "canal"))?.argb)
            assertEquals(palette.water, stroke(style, "water", line("kind_detail" to "ditch"))?.argb)
        }
        // So steht es in einer echten Datei: die Art eines Wasserlaufs in `kind`.
        for (style in listOf(view, hud)) {
            assertEquals(palette.water, stroke(style, "water", line("kind" to "river"))?.argb)
            assertEquals(palette.water, stroke(style, "water", line("kind" to "canal"))?.argb)
            assertEquals(palette.water, stroke(style, "water", line("kind" to "stream"))?.argb)
        }
        assertTrue(stroke(view, "water", line("kind" to "river"))!!.widthDp > stroke(view, "water", line("kind" to "stream"))!!.widthDp)
        assertNull(view.op("water", line("kind" to "dock"), 15.0))
        assertNull(view.op("water", line("kind" to "dock", "kind_detail" to "river"), 15.0)) // `kind` hat Vorrang
        assertNull(view.op("water", line("kind_detail" to "dock"), 15.0))
        assertNull(view.op("water", line(), 15.0))
        assertNull(view.op("water", feature(TileGeometryType.POINT, "kind" to "lake"), 15.0))
        assertTrue(stroke(view, "water", line("kind_detail" to "river"))!!.widthDp > stroke(view, "water", line("kind_detail" to "stream"))!!.widthDp)
    }

    // ── Strassen ──────────────────────────────────────────────────────────────

    private fun road(style: MapStyle, kind: String?, detail: String? = null, zoom: Double = 15.0): DrawOp.Line? {
        val tags = listOfNotNull(kind?.let { "kind" to it as Any }, detail?.let { "kind_detail" to it as Any })
        return style.op("roads", line(*tags.toTypedArray()), zoom) as? DrawOp.Line
    }

    @Test
    fun `road classes get their colours`() {
        assertEquals(palette.roadHighway, road(view, "highway", "motorway")?.argb)
        assertEquals(palette.roadHighway, road(view, "highway", "trunk_link")?.argb)
        assertEquals(palette.roadMajor, road(view, "major_road", "primary")?.argb)
        assertEquals(palette.roadMajor, road(view, "major_road", "tertiary")?.argb)
        assertEquals(palette.roadMinor, road(view, "minor_road", "residential")?.argb)
        assertEquals(palette.roadMinor, road(view, "minor_road", "unclassified")?.argb)
        assertEquals(palette.roadService, road(view, "minor_road", "service")?.argb)
        assertEquals(palette.roadService, road(view, "minor_road", "parking_aisle")?.argb)
        assertEquals(palette.roadPath, road(view, "path", "path")?.argb)
        assertEquals(palette.roadPath, road(view, "path", "cycleway")?.argb)
        assertEquals(palette.roadPath, road(view, "path", "bridleway")?.argb)
        assertEquals(palette.rail, road(view, "rail", "rail")?.argb)
    }

    @Test
    fun `a footpath is a footpath whatever its kind says`() {
        for (detail in listOf("path", "footway", "cycleway", "bridleway", "pedestrian")) assertEquals(detail, palette.roadPath, road(view, "minor_road", detail)?.argb)
    }

    @Test
    fun `a track is a track whatever its kind says`() {
        assertEquals(palette.roadTrack, road(view, "path", "track")?.argb)
        assertEquals(palette.roadTrack, road(view, "minor_road", "track")?.argb)
        assertEquals(palette.roadTrack, road(hud, "path", "track")?.argb) // im HUD sichtbar: Enduro-Wege
    }

    @Test
    fun `the HUD leaves out service roads, footpaths and rails`() {
        assertNull(road(hud, "minor_road", "service"))
        assertNull(road(hud, "path", "footway"))
        assertNull(road(hud, "path", "path"))
        assertNull(road(hud, "path", "cycleway"))
        assertNull(road(hud, "rail", "rail"))
        assertNotNull(road(hud, "minor_road", "residential"))
        assertNotNull(road(hud, "major_road", "primary"))
        assertNotNull(road(hud, "highway", "motorway"))
    }

    @Test
    fun `steps, sidewalks, ferries and runways are never drawn`() {
        for (detail in listOf("steps", "sidewalk", "crossing", "corridor", "pier", "runway", "taxiway", "disused", "slipway", "cable_car")) {
            assertNull(detail, road(view, "path", detail))
        }
        for (kind in listOf("ferry", "pier", "aerialway", "aeroway", "invented")) assertNull(kind, road(view, kind))
        assertNull(road(view, null))
    }

    @Test
    fun `a living street is a small road and a platform is not drawn`() {
        assertEquals(palette.roadMinor, road(view, "other", "living_street")?.argb)
        assertNull(road(view, "other", "platform"))
        assertNull(road(view, "other", null))
        assertNull(road(view, "aeroway", "runway"))
    }

    @Test
    fun `roads that are not lines are not drawn`() {
        assertNull(view.op("roads", poly("kind" to "highway"), 15.0))
        assertNull(view.op("roads", feature(TileGeometryType.POINT, "kind" to "highway"), 15.0))
    }

    @Test
    fun `the order puts important roads on top of minor ones and lines on top of areas`() {
        val orders = listOf(
            fill(view, "landcover", poly("kind" to "forest"))!!.order,
            fill(view, "landuse", poly("kind" to "farmland"))!!.order,
            fill(view, "landuse", poly("kind" to "scrub"))!!.order,
            fill(view, "landuse", poly("kind" to "meadow"))!!.order,
            fill(view, "landuse", poly("kind" to "forest"))!!.order,
            fill(view, "water", poly("kind" to "lake"))!!.order,
            stroke(view, "water", line("kind_detail" to "river"))!!.order,
            road(view, "rail", "rail")!!.order,
            road(view, "path", "path")!!.order,
            road(view, "path", "track")!!.order,
            road(view, "minor_road", "service")!!.order,
            road(view, "minor_road", "residential")!!.order,
            road(view, "major_road", "primary")!!.order,
            road(view, "highway", "motorway")!!.order,
        )
        assertEquals(orders.sorted(), orders)
        assertEquals(orders.size, orders.toSet().size - 0 + 0) // alle verschieden bis auf gleiche Klassen: hier sind alle Klassen verschieden
    }

    @Test
    fun `widths grow with the zoom and with the importance of the road`() {
        for (cls in RoadClass.entries) {
            var last = 0f
            for (zoom in listOf(0.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 25.0)) {
                val w = roadWidthDp(cls, zoom)
                assertTrue("$cls z$zoom", w >= last && w >= 0.5f)
                last = w
            }
        }
        for (zoom in listOf(10.0, 14.0, 18.0)) {
            val ranked = listOf(RoadClass.HIGHWAY, RoadClass.MAJOR, RoadClass.MINOR, RoadClass.SERVICE).map { roadWidthDp(it, zoom) }
            assertTrue("$ranked", ranked.zipWithNext().all { (a, b) -> a >= b })
        }
        assertNotEquals(roadWidthDp(RoadClass.MINOR, 12.0), roadWidthDp(RoadClass.MINOR, 18.0))
    }

    @Test
    fun `the width of a drawn road follows the zoom it was asked for`() {
        val narrow = road(view, "minor_road", "residential", zoom = 12.0)!!
        val wide = road(view, "minor_road", "residential", zoom = 18.0)!!
        assertTrue(wide.widthDp > narrow.widthDp)
    }

    @Test
    fun `layers the style does not know are not drawn`() {
        for (layer in listOf("earth", "buildings", "places", "pois", "boundaries", "transit", "invented")) {
            assertNull(layer, view.op(layer, poly("kind" to "building"), 15.0))
            assertNull(layer, view.op(layer, line("kind" to "highway"), 15.0))
        }
    }

    // ── Stetige Stufe ─────────────────────────────────────────────────────────

    @Test
    fun `the effective zoom is the zoom whose 512 pixel tiles match the scale`() {
        for (z in listOf(3, 10, 15, 19)) assertEquals(z.toDouble(), effectiveZoom(512.0 / tileSizeUnits(z)), 1e-9)
        assertEquals(0.0, effectiveZoom(0.0), 0.0)
        assertEquals(0.0, effectiveZoom(Double.NaN), 0.0)
        assertEquals(0.0, effectiveZoom(-3.0), 0.0)
        assertTrue(effectiveZoom(1e9) > effectiveZoom(1e8))
    }
}
