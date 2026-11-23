package de.adv990de.obd2.ui.tracks

import de.adv990de.obd2.map.TileKey
import de.adv990de.obd2.map.tileToScreen
import de.adv990de.obd2.track.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos

class RoadbookMapViewTest {

    private val metresPerDegLat = 111_195.0

    private fun at(base: TrackPoint, northM: Double, eastM: Double) =
        TrackPoint(null, base.lat + northM / metresPerDegLat, base.lon + eastM / (metresPerDegLat * cos(Math.toRadians(base.lat))), null, null, null)

    private val w = 540f
    private val h = 500f

    /** Der Weg: erst geradeaus nach Norden, dann nach rechts; die Position liegt am Anfang. */
    private fun scene(origin: TrackPoint): Triple<List<TrackPoint>, TrackPoint, TrackPoint> {
        val path = listOf(origin, at(origin, 60.0, 0.0), at(origin, 120.0, 5.0), at(origin, 150.0, 40.0), at(origin, 150.0, 120.0))
        return Triple(path, path[0], path[3])
    }

    @Test
    fun `the tile view puts every point of the small map on the same pixel`() {
        for (origin in listOf(TrackPoint(null, 50.11, 8.68, null, null, null), TrackPoint(null, -33.87, 151.2, null, null, null), TrackPoint(null, 64.1, -21.9, null, null, null))) {
            for (bearing in listOf(0.0, 37.0, 90.0, 200.0, 311.0)) {
                val (path, position, apex) = scene(origin)
                val map = roadbookMap(path, position, bearing, apex, w, h)
                val view = map.trackView(position, bearing, w, h)
                fun px(p: TrackPoint) = de.adv990de.obd2.ui.tracks.toScreen(view, w.toDouble(), h.toDouble(), projectLon(p.lon, position.lon), projectLat(p.lat))
                map.line.forEachIndexed { i, expected ->
                    val got = px(path[i])
                    assertEquals("x of point $i at ${origin.lat}, bearing $bearing", expected.x.toDouble(), got.x, 0.5)
                    assertEquals("y of point $i at ${origin.lat}, bearing $bearing", expected.y.toDouble(), got.y, 0.5)
                }
                val pos = px(position)
                assertEquals(map.position.x.toDouble(), pos.x, 0.5)
                assertEquals(map.position.y.toDouble(), pos.y, 0.5)
            }
        }
    }

    @Test
    fun `a tile that holds the position is drawn around the position pixel`() {
        val origin = TrackPoint(null, 50.11, 8.68, null, null, null)
        val (path, position, apex) = scene(origin)
        val map = roadbookMap(path, position, 0.0, apex, w, h)
        val view = map.trackView(position, 0.0, w, h)
        val key = de.adv990de.obd2.map.tileContaining(position.lat, position.lon, 15)
        val m = tileToScreen(view, w.toDouble(), h.toDouble(), position.lon, key, 4096)
        val corners = listOf(m.map(0.0, 0.0), m.map(4096.0, 0.0), m.map(4096.0, 4096.0), m.map(0.0, 4096.0))
        // Die Position liegt im Viereck der Kachel: links und rechts, oben und unten von ihr gibt es Ecken.
        assert(corners.any { it.x < map.position.x } && corners.any { it.x > map.position.x })
        assert(corners.any { it.y < map.position.y } && corners.any { it.y > map.position.y })
    }

    @Test
    fun `the scale is the one of the small map`() {
        val origin = TrackPoint(null, 50.11, 8.68, null, null, null)
        val (path, position, apex) = scene(origin)
        val map = roadbookMap(path, position, 0.0, apex, w, h)
        val view = map.trackView(position, 0.0, w, h)
        val east = at(position, 0.0, 100.0)
        val a = toScreen(view, w.toDouble(), h.toDouble(), projectLon(position.lon, position.lon), projectLat(position.lat))
        val b = toScreen(view, w.toDouble(), h.toDouble(), projectLon(east.lon, position.lon), projectLat(east.lat))
        assertEquals(100.0 / map.metresPerPx, b.x - a.x, 0.5)
    }
}
