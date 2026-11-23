package de.adv990de.obd2.ui.dashboard

import android.content.pm.ActivityInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.alarm.Alarm
import de.adv990de.obd2.alarm.AlarmController
import de.adv990de.obd2.alarm.AlarmKind
import de.adv990de.obd2.alarm.AlarmLog
import de.adv990de.obd2.alarm.AlarmNotifier
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.di.AlarmModule
import de.adv990de.obd2.di.TerrainModule
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.terrain.TerrainInput
import de.adv990de.obd2.terrain.TerrainInputs
import de.adv990de.obd2.track.AutoZoom
import de.adv990de.obd2.track.FollowDirection
import de.adv990de.obd2.track.FollowInfo
import de.adv990de.obd2.track.FollowLine
import de.adv990de.obd2.track.FollowReading
import de.adv990de.obd2.track.FollowSession
import de.adv990de.obd2.track.FollowTracker
import de.adv990de.obd2.track.RoadbookTracker
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.detectManeuvers
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.amberColorScheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * **Keine Pruefung, sondern ein Werkzeug:** spielt eine simulierte Fahrt ueber einen erfundenen Track durch das **echte** Dashboard (Hilt, echtes `DashboardViewModel`,
 * `FollowStatusViewModel` und Oberflaeche), damit man sie filmen kann (`adb shell screenrecord`). Die Sitzung ist eine Attrappe, deren Messwert-Fluss hier aus den **echten** Klassen
 * gespeist wird (`detectManeuvers`, `FollowTracker`, `RoadbookTracker`, `AutoZoom`): je Sekunde ein Fix mit einer Geschwindigkeit, die vor Kurven sinkt.
 *
 * Laeuft nur mit dem Instrumentation-Argument `simulateRide=true` (sonst uebersprungen), optional `orientation=portrait` und `speedUp=1.5` (simulierte Sekunden je echter Sekunde):
 * `adb shell am instrument -w -e class de.adv990de.obd2.ui.dashboard.FollowRideSimulationDeviceTest -e simulateRide true de.adv990de.obd2.debug.test/de.adv990de.obd2.HiltTestRunner`.
 * Nie am Fahrzeug, nie mit echten Positionen: der Track ist erfunden.
 */
@HiltAndroidTest
@UninstallModules(AlarmModule::class, TerrainModule::class)
@RunWith(AndroidJUnit4::class)
class FollowRideSimulationDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository {
        every { supportedPidIds } returns MutableStateFlow(null)
        every { lastConnectedDevice } returns MutableStateFlow(null)
    }

    class NoopNotifier : AlarmNotifier {
        override fun show(alarm: Alarm) = Unit
        override fun clear(kind: AlarmKind) = Unit
    }

    @BindValue @JvmField val alarmNotifier: AlarmNotifier = NoopNotifier()
    @BindValue @JvmField val alarmLog: AlarmLog = AlarmLog.None
    @BindValue @JvmField val alarmController: AlarmController = AlarmController(alarmNotifier, alarmLog)

    class NoTerrainInputs : TerrainInputs {
        override val inputs: Flow<TerrainInput> = MutableSharedFlow(extraBufferCapacity = 1)
    }

    @BindValue @JvmField val terrainInputs: TerrainInputs = NoTerrainInputs()

    private val info = MutableStateFlow<FollowInfo?>(FollowInfo(7, "Sonntagsrunde"))
    private val reading = MutableStateFlow<FollowReading>(FollowReading.Searching)

    @BindValue @JvmField val followSession: FollowSession = mockk(relaxed = true) {
        every { info } returns this@FollowRideSimulationDeviceTest.info
        every { reading } returns this@FollowRideSimulationDeviceTest.reading
    }

    @Before
    fun setup() {
        hiltRule.inject()
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(false)
    }

    @javax.inject.Inject lateinit var mapRepository: de.adv990de.obd2.data.MapRepository

    private val metresPerDegLat = 111_195.0

    /** Der Ursprung der erfundenen Strecke: 50 N, 10 E; mit `mapFile` die Mitte der eingespielten Karte (sonst laege die Strecke ausserhalb ihrer Kacheln). */
    private var originLat = 50.0
    private var originLon = 10.0

    /** Eine erfundene Strecke in Metern (Norden, Osten) ab dem Ursprung. */
    private fun at(northM: Double, eastM: Double) =
        TrackPoint(null, originLat + northM / metresPerDegLat, originLon + eastM / (metresPerDegLat * cos(Math.toRadians(originLat))), null, null, null)

    /** Der Track: Geraden und Boegen (Radius 25 m, bei der Kehre 12 m), Punkte alle 10 m. */
    private fun inventedTrack(): List<TrackPoint> {
        data class Leg(val turnDeg: Double, val radiusM: Double, val lengthM: Double)
        val legs = listOf(
            Leg(0.0, 0.0, 300.0),
            Leg(90.0, 25.0, 250.0), // rechts
            Leg(-70.0, 30.0, 220.0), // links
            Leg(40.0, 40.0, 200.0), // leicht rechts
            Leg(150.0, 12.0, 160.0), // Kehre rechts
            Leg(-60.0, 30.0, 180.0), // links
            Leg(-90.0, 25.0, 150.0), // scharf links
            Leg(80.0, 25.0, 140.0), // S-Kurve: rechts, kurz danach links
            Leg(-80.0, 25.0, 200.0),
        )
        var n = 0.0
        var e = 0.0
        var heading = 0.0 // Grad im Uhrzeigersinn ab Norden
        val dense = ArrayList<Pair<Double, Double>>()
        dense.add(n to e)
        fun straight(len: Double) {
            val steps = max(1, (len / 10.0).toInt())
            val d = len / steps
            repeat(steps) {
                n += d * cos(Math.toRadians(heading)); e += d * sin(Math.toRadians(heading)); dense.add(n to e)
            }
        }
        for (leg in legs) {
            if (leg.turnDeg != 0.0) {
                val steps = max(3, (kotlin.math.abs(leg.turnDeg) / 10.0).toInt())
                val stepDeg = leg.turnDeg / steps
                val arc = Math.toRadians(kotlin.math.abs(stepDeg)) * leg.radiusM
                repeat(steps) {
                    heading += stepDeg / 2
                    n += arc * cos(Math.toRadians(heading)); e += arc * sin(Math.toRadians(heading))
                    heading += stepDeg / 2
                    dense.add(n to e)
                }
            }
            straight(leg.lengthM)
        }
        return dense.map { at(it.first, it.second) }
    }

    @Test
    fun ride() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("nur mit -e simulateRide true", args.getString("simulateRide") == "true")
        val portrait = args.getString("orientation") == "portrait"
        val speedUp = args.getString("speedUp")?.toDoubleOrNull() ?: 1.5
        // Mit `mapFile` (Name einer Datei im App-Ordner `files/`, per `adb shell run-as … cp` dorthin gelegt) faehrt die Simulation ueber einer echten Karte: Tests nie mit Daten des Nutzers im Repository.
        args.getString("mapFile")?.let { name ->
            val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name)
            val imported = kotlinx.coroutines.runBlocking { mapRepository.import(android.net.Uri.fromFile(file)) }
            check(imported is de.adv990de.obd2.map.MapImportResult.Success) { "Karte nicht eingespielt: $imported" }
            val ready = kotlinx.coroutines.runBlocking { mapRepository.state.first { it is de.adv990de.obd2.map.MapState.Ready } } as de.adv990de.obd2.map.MapState.Ready
            originLat = (ready.info.minLat + ready.info.maxLat) / 2
            originLon = (ready.info.minLon + ready.info.maxLon) / 2
        }

        val line = FollowLine.of(inventedTrack(), FollowDirection.FORWARD)
        val maneuvers = detectManeuvers(line)
        val tracker = FollowTracker(line)
        val roadbook = RoadbookTracker(maneuvers, line)
        val zoom = AutoZoom()

        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            // Erst die Ausrichtung: steht das Geraet anders, wird die Activity neu erzeugt, und ein davor gesetzter Inhalt ginge mit der alten Instanz verloren.
            scenario.onActivity { it.requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(2_000)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                activity.setContent {
                    val navController = rememberNavController()
                    AppTheme(colorScheme = amberColorScheme, typography = AppTypography) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            Box(Modifier.weight(1f)) {
                                NavHost(navController, startDestination = "dash") {
                                    composable("dash") { DashboardScreen(navController = navController, vm = hiltViewModel()) }
                                    composable(Screen.TrackFollow.route) { }
                                }
                            }
                            Box(Modifier.fillMaxWidth().navigationBarsPadding().height(65.dp).background(MaterialTheme.colorScheme.surface))
                        }
                    }
                }
            }
            Thread.sleep(2_000) // Anlaufzeit
            marker("ride_start") // der Rechner startet die Aufnahme, sobald diese Datei da ist
            Thread.sleep(2_000)

            // Die Geschwindigkeit sinkt vor jeder Richtungsaenderung (km/h): Kehre 20, scharf 28, sonst 35 bis 38, dazwischen bis 62.
            fun targetSpeed(s: Double): Double {
                var v = 62.0
                for (m in maneuvers) {
                    if (m.isDestination) continue
                    val cap = when {
                        kotlin.math.abs(m.turnDeg) >= 120 -> 20.0
                        kotlin.math.abs(m.turnDeg) >= 75 -> 30.0
                        else -> 42.0
                    }
                    v = min(v, cap + 0.13 * max(0.0, kotlin.math.abs(m.apexAlongM - s) - 25.0))
                }
                return min(v, min(5.0 + 0.2 * s, 6.0 + 0.1 * (line.totalM - s)))
            }

            var s = 0.0
            var nowMs = 0L
            var speed = 0.0
            while (line.totalM - s > 8.0) {
                speed = targetSpeed(s).coerceAtLeast(6.0)
                val dt = speedUp // simulierte Sekunden je Fix
                s = min(line.totalM, s + speed / 3.6 * dt)
                nowMs += (dt * 1_000).toLong()
                // Ein Fix mit ein wenig Rauschen (rund 1,5 m seitlich), wie ihn ein GPS liefert.
                val clean = line.pointAt(s)
                val noisy = TrackPoint(nowMs, clean.lat + 0.000012 * sin(s / 7.0), clean.lon + 0.000012 * cos(s / 5.0), null, null, null)
                val result = tracker.update(noisy)
                if (result != null) {
                    val hud = roadbook.update(result, noisy, speed.toFloat(), nowMs, 60.0)
                    reading.value = FollowReading.Following(noisy, result, speed.toFloat(), zoom.update(speed.toFloat(), nowMs), hud)
                }
                Thread.sleep(1_000)
            }
            Thread.sleep(4_000) // das Ziel ist erreicht, das HUD blendet aus
            marker("ride_end") // der Rechner beendet die Aufnahme, bevor die Activity schliesst
            Thread.sleep(4_000)
        }
    }

    /** Eine leere Datei im App-Ordner auf dem Geraet: der Rechner (adb) liest sie als Zeichen. */
    private fun marker(name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null) ?: return
        java.io.File(dir, name).writeText("")
    }
}
