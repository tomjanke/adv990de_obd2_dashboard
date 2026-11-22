package de.adv990de.obd2.ui.tracks

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.map.ByteArraySource
import de.adv990de.obd2.map.MapInfo
import de.adv990de.obd2.map.MapPalette
import de.adv990de.obd2.map.MapProfile
import de.adv990de.obd2.map.MapTiles
import de.adv990de.obd2.map.PmTilesArchive
import de.adv990de.obd2.track.TrackBounds
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.MapColors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Die Kartenebene der Trackansicht, als Bildpunkte gelesen: aus einem erfundenen Archiv (`assets/map/sample.pmtiles`, Aufbau in `DeviceMapFixture` der JVM-Tests) erscheinen Wald, Gewaesser
 * und Strasse an den Orten, die die Projektion hinrechnet, in den Farben der abgeleiteten Palette, **unter** der Linie des Tracks. Ohne Karte bleibt der Grund; liegt der Track ausserhalb der
 * Karte, steht der Hinweis statt der Quellenangabe. Nur erfundene Koordinaten.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MapTileLayerDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private val colors = lightColorScheme()

    // Die Mitte der mittleren Kachel: dort stossen Wald (oben, rechts) und Gewaesser (links unten) aneinander.
    private val lat0 = centerLat()
    private val lon0 = centerLon()
    private fun centerLat() = unprojectLat((de.adv990de.obd2.map.tileNorthY(TILE_Y, ZOOM) + de.adv990de.obd2.map.tileNorthY(TILE_Y + 1, ZOOM)) / 2)
    private fun centerLon() = (de.adv990de.obd2.map.tileWestDeg(TILE_X, ZOOM) + de.adv990de.obd2.map.tileWestDeg(TILE_X + 1, ZOOM)) / 2

    private val points = listOf(
        TrackPoint(null, lat0 + 0.004, lon0 - 0.006, null, null, null),
        TrackPoint(null, lat0 - 0.004, lon0, null, null, null),
        TrackPoint(null, lat0 + 0.004, lon0 + 0.006, null, null, null),
    )
    private val bounds = TrackBounds(minLat = lat0 - 0.004, maxLat = lat0 + 0.004, minLon = lon0 - 0.006, maxLon = lon0 + 0.006)

    private val palette = MapPalette.derive(colors.surface.toArgb(), colors.onSurface.toArgb(), MapColors.tints(), MapProfile.TRACK_VIEW, colors.primary.toArgb())

    private fun sampleBytes(): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open("map/sample.pmtiles").use { it.readBytes() }

    private fun tiles(info: MapInfo? = null): MapTiles {
        val archive = runBlocking { PmTilesArchive.open(ByteArraySource(sampleBytes())) }
        assertNotNull(archive)
        return MapTiles(archive!!, info ?: MapInfo("Testkarte", null, lon0 - 0.2, lat0 - 0.2, lon0 + 0.2, lat0 + 0.2, ZOOM, ZOOM, 1))
    }

    private fun show(tiles: MapTiles?) {
        composeRule.setContent {
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                TrackMapView(points, bounds, Modifier.size(width = 400.dp, height = 300.dp), tiles = tiles)
            }
        }
        composeRule.waitForIdle()
    }

    private fun canvas() = composeRule.onNodeWithTag(TRACK_CANVAS_TEST_TAG)

    /** Der Bildpunkt eines Ortes in der angepassten Ansicht. */
    private fun pixelOf(lat: Double, lon: Double): Pair<Int, Int> {
        val image = canvas().captureToImage()
        val f = trackFrame(bounds, image.width.toDouble(), image.height.toDouble(), paddingPx = 32 * composeRule.density.density.toDouble())
        val p = toScreen(TrackViewState().resolved(f), f.widthPx, f.heightPx, projectLon(lon, bounds.minLon), projectLat(lat))
        return p.x.roundToInt() to p.y.roundToInt()
    }

    private fun Color.near(argb: Int, tolerance: Float = 0.03f): Boolean {
        val other = Color(argb)
        return abs(red - other.red) < tolerance && abs(green - other.green) < tolerance && abs(blue - other.blue) < tolerance
    }

    private fun PixelMap.near(x: Int, y: Int, argb: Int, radius: Int = 0): Boolean =
        (-radius..radius).any { dy -> (-radius..radius).any { dx -> x + dx in 0 until width && y + dy in 0 until height && this[x + dx, y + dy].near(argb) } }

    /** Wartet, bis die Kachel des Ortes gezeichnet ist (die Kacheln laden im Hintergrund). */
    private fun waitForColourAt(lat: Double, lon: Double, argb: Int, radius: Int = 0) {
        composeRule.waitUntil(10_000) {
            val (x, y) = pixelOf(lat, lon)
            canvas().captureToImage().toPixelMap().near(x, y, argb, radius)
        }
    }

    @Test
    fun theMapShowsWoodWaterAndRoadInThePaletteColoursAtTheirPlaces() {
        show(tiles())
        val water = lat0 - 0.002 to lon0 - 0.003
        val woodLeft = lat0 + 0.001 to lon0 - 0.003
        // Mitte oben (zwischen den beiden Armen des V): unten rechts liegen die Bedienknoepfe der Karte.
        val woodMiddle = lat0 + 0.0015 to lon0
        waitForColourAt(water.first, water.second, palette.water)
        val map = canvas().captureToImage().toPixelMap()
        pixelOf(woodLeft.first, woodLeft.second).let { assertTrue("Wald oben links", map.near(it.first, it.second, palette.wood)) }
        pixelOf(woodMiddle.first, woodMiddle.second).let { assertTrue("Wald oben in der Mitte", map.near(it.first, it.second, palette.wood)) }
        // Die Hauptstrasse liegt bei 7/16 der Kachelhoehe von oben, also 1/16 ueber der Mitte; ein paar Bildpunkte Spielraum fuer die Strichbreite und das Runden.
        val roadLat = unprojectLat(planeY(TILE_Y, 7.0 / 16))
        pixelOf(roadLat, lon0 + 0.003).let { assertTrue("Hauptstrasse", map.near(it.first, it.second, palette.roadMajor, radius = 4)) }
    }

    @Test
    fun theLineAndTheStartMarkerStayOnTopOfTheMap() {
        show(tiles())
        waitForColourAt(lat0 - 0.002, lon0 - 0.003, palette.water)
        val map = canvas().captureToImage().toPixelMap()
        val start = pixelOf(points[0].lat, points[0].lon)
        assertTrue("Startmarke", map.near(start.first, start.second, colors.tertiary.toArgb(), radius = 2))
        val onLine = (0 until map.height).any { y -> (0 until map.width).any { x -> map[x, y].near(colors.primary.toArgb()) } }
        assertTrue("Linie", onLine)
    }

    @Test
    fun withoutAMapThereAreNoMapColoursAndNoNotices() {
        show(null)
        val map = canvas().captureToImage().toPixelMap()
        val (x, y) = pixelOf(lat0 - 0.002, lon0 - 0.003)
        assertFalse(map.near(x, y, palette.water))
        assertTrue("Grund", map[x, y].near(colors.surface.toArgb()))
        composeRule.onNodeWithTag(MAP_ATTRIBUTION_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(MAP_NOT_COVERING_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun aMapThatCoversTheTrackShowsTheAttributionAndNoHint() {
        show(tiles())
        composeRule.onNodeWithTag(MAP_ATTRIBUTION_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(MAP_NOT_COVERING_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun aMapAwayFromTheTrackShowsTheHintAndNoMapColours() {
        show(tiles(MapInfo("Weit weg", null, -20.0, -10.0, -19.0, -9.0, ZOOM, ZOOM, 1)))
        composeRule.onNodeWithTag(MAP_NOT_COVERING_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(MAP_ATTRIBUTION_TEST_TAG).assertDoesNotExist()
    }

    private fun planeY(tileY: Int, fraction: Double): Double {
        val top = de.adv990de.obd2.map.tileNorthY(tileY, ZOOM)
        val bottom = de.adv990de.obd2.map.tileNorthY(tileY + 1, ZOOM)
        return top + (bottom - top) * fraction
    }

    private companion object {
        const val ZOOM = 12
        const val TILE_X = 2162
        const val TILE_Y = 1383
    }
}
