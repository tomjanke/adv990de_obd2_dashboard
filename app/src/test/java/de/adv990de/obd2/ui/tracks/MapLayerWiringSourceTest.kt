package de.adv990de.obd2.ui.tracks

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Karte muss von der Seite bis zur Zeichnung durchgereicht werden. Das brach einmal still (1.49.0: die Detail-Ansicht holte die Karte, gab sie aber nie an `TrackDetailContent` weiter —
 * die Geraete-Tests der Teile waren gruen, am echten Gerat fehlte die Karte). Der Screen-Rahmen (ViewModel, Hilt) ist im Geraete-Test nicht ohne Weiteres zu bauen: dieser Test liest den Quelltext.
 */
class MapLayerWiringSourceTest {
    private fun source(name: String) = File(projectRoot, "app/src/main/java/de/adv990de/obd2/ui/tracks/$name").readText()

    @Test
    fun `the detail screen reads the map from the view model and hands it to the content`() {
        val s = source("TrackDetailScreen.kt")
        assertTrue(s.contains("mapVm.tiles.collectAsStateWithLifecycle()"))
        val call = s.substringAfter("    TrackDetailContent(\n").substringBefore("\n    )\n")
        assertTrue("TrackDetailContent bekommt die Karte nicht: $call", Regex("""\btiles\s*=\s*tiles\b""").containsMatchIn(call))
    }

    @Test
    fun `the detail content hands the map to the map view in both orientations`() {
        val s = source("TrackDetailScreen.kt")
        assertEquals(2, Regex("""TrackMapView\([^\n]*tiles = tiles\)""").findAll(s).count())
        assertTrue(s.contains("is TrackDetailState.Ready -> ReadyContent(state, tiles)"))
    }

    @Test
    fun `the follow screen reads the map from the view model and hands it down to the map view`() {
        val s = source("TrackFollowScreen.kt")
        assertTrue(s.contains("mapVm.tiles.collectAsStateWithLifecycle()"))
        val call = s.substringAfter("    TrackFollowContent(\n").substringBefore("\n    )\n")
        assertTrue("TrackFollowContent bekommt die Karte nicht: $call", Regex("""\btiles\s*=\s*tiles\b""").containsMatchIn(call))
        assertTrue(s.contains("ReadyContent(ready, direction, reading, permissionMissing, onDirection, onOpenSettings, onStop, tiles)"))
        assertTrue(Regex("""TrackMapView\([^\n]*tiles = tiles\)""").containsMatchIn(s))
    }

    @Test
    fun `the map view hands the map to the canvas and the canvas draws the layer`() {
        val s = source("TrackCanvas.kt")
        assertTrue(s.contains("tiles = tiles,\n            modifier = Modifier"))
        assertTrue(s.contains("drawMapTiles(layer, visible, view, bounds.minLon)"))
        assertTrue(s.contains("layer.RequestTiles(visible)"))
    }
}
