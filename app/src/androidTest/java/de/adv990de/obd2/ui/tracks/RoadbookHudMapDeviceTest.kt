package de.adv990de.obd2.ui.tracks

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import de.adv990de.obd2.map.tileNorthY
import de.adv990de.obd2.map.tileWestDeg
import de.adv990de.obd2.track.Maneuver
import de.adv990de.obd2.track.ManeuverKind
import de.adv990de.obd2.track.RoadbookHud
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.MapColors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Die Karte hinter der Linie der Mini-Karte des Roadbook-HUD, als Bildpunkte gelesen: aus dem erfundenen Archiv (`assets/map/sample.pmtiles`) erscheinen **Wald und Gewaesser** in den Farben
 * der HUD-Palette (das HUD malt auf `background`), ohne Karte bleibt der Grund. Die Position liegt in der Mitte der mittleren Kachel, dort stossen Wald und Gewaesser aneinander; der
 * Ausschnitt ist wenige Dutzend Meter breit, zeigt also beide. Nur erfundene Koordinaten.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RoadbookHudMapDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private val colors = lightColorScheme()
    private val zoom = 12
    private val tileX = 2162
    private val tileY = 1383
    private val lat0 = unprojectLat((tileNorthY(tileY, zoom) + tileNorthY(tileY + 1, zoom)) / 2)
    private val lon0 = (tileWestDeg(tileX, zoom) + tileWestDeg(tileX + 1, zoom)) / 2

    private fun at(northM: Double, eastM: Double) =
        TrackPoint(null, lat0 + northM / 111_195.0, lon0 + eastM / (111_195.0 * kotlin.math.cos(Math.toRadians(lat0))), null, null, null)

    private val path = listOf(at(-20.0, 0.0), at(20.0, 0.0), at(60.0, 0.0), at(80.0, 25.0), at(80.0, 80.0))

    /** Die Entfernung 0 ergibt Deckkraft 1: die Farben werden nicht mit dem Grund gemischt. */
    private val hud = RoadbookHud(Maneuver(60.0, 80.0, 100.0, 95.0, ManeuverKind.NORMAL), 0.0, 400.0, null, null, path, path[3], path[0], 0.0)

    private val palette = MapPalette.derive(colors.background.toArgb(), colors.onSurface.toArgb(), MapColors.tints(), MapProfile.HUD, colors.primary.toArgb())

    private fun tiles(): MapTiles {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("map/sample.pmtiles").use { it.readBytes() }
        val archive = runBlocking { PmTilesArchive.open(ByteArraySource(bytes)) }
        assertNotNull(archive)
        return MapTiles(archive!!, MapInfo("Testkarte", null, lon0 - 0.2, lat0 - 0.2, lon0 + 0.2, lat0 + 0.2, zoom, zoom, 1))
    }

    private fun show(withMap: Boolean) {
        val tiles = if (withMap) tiles() else null
        composeRule.setContent {
            AppTheme(colorScheme = colors, typography = AppTypography) {
                val layer = rememberMapTileLayer(tiles, MapProfile.HUD, HUD_TILE_CACHE_SIZE)
                RoadbookHudView(hud, compact = false, tall = false, mapLayer = layer)
            }
        }
        composeRule.waitForIdle()
    }

    private fun Color.near(argb: Int, tolerance: Float = 0.03f): Boolean {
        val other = Color(argb)
        return abs(red - other.red) < tolerance && abs(green - other.green) < tolerance && abs(blue - other.blue) < tolerance
    }

    private fun PixelMap.count(argb: Int): Int {
        var n = 0
        for (y in 0 until height) for (x in 0 until width) if (this[x, y].near(argb)) n++
        return n
    }

    /** Der Knoten der Karte steht nur im ungemergten Baum (das HUD fasst seine Teile zu einer Beschreibung zusammen). */
    private fun mapPixels() = composeRule.onNodeWithTag(ROADBOOK_MAP_TEST_TAG, useUnmergedTree = true).captureToImage().toPixelMap()

    @Test
    fun theMiniMapShowsWoodAndWaterBehindTheLine() {
        show(withMap = true)
        // Die Kacheln laden im Hintergrund: warten, bis beide Farben da sind.
        composeRule.waitUntil(10_000) { mapPixels().let { it.count(palette.wood) > 50 && it.count(palette.water) > 50 } }
        val px = mapPixels()
        // Der Wald hat die weitaus groessere Flaeche (nur ein Viertel der Kachel ist Gewaesser, und das erst unterhalb der Mitte).
        assert(px.count(palette.wood) > px.count(palette.water))
        // Die Linie des Tracks liegt ueber der Karte: ihre Farbe (`primary`) ist da.
        assert(px.count(colors.primary.toArgb()) > 20)
    }

    @Test
    fun withoutAMapTheMiniMapKeepsItsPlainGround() {
        show(withMap = false)
        val px = mapPixels()
        assertFalse(px.count(palette.wood) > 0)
        assertFalse(px.count(palette.water) > 0)
    }
}
