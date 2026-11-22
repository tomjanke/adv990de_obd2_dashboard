package de.adv990de.obd2.ui.dashboard

import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.alarm.AlarmController
import de.adv990de.obd2.alarm.AlarmKind
import de.adv990de.obd2.alarm.AlarmLog
import de.adv990de.obd2.alarm.AlarmNotifier
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.di.AlarmModule
import de.adv990de.obd2.di.TerrainModule
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.terrain.TerrainInput
import de.adv990de.obd2.terrain.TerrainInputs
import de.adv990de.obd2.track.FollowInfo
import de.adv990de.obd2.track.FollowReading
import de.adv990de.obd2.track.FollowResult
import de.adv990de.obd2.track.FollowSession
import de.adv990de.obd2.track.Maneuver
import de.adv990de.obd2.track.ManeuverKind
import de.adv990de.obd2.track.RoadbookHud
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.tracks.FOLLOW_MENU_OPEN_MAP_TEST_TAG
import de.adv990de.obd2.ui.tracks.FOLLOW_MENU_STOP_TEST_TAG
import de.adv990de.obd2.ui.tracks.RECORDING_BUTTON_TEST_TAG
import de.adv990de.obd2.ui.tracks.ROADBOOK_DISTANCE_TEST_TAG
import de.adv990de.obd2.ui.tracks.ROADBOOK_HUD_TEST_TAG
import de.adv990de.obd2.ui.tracks.ROADBOOK_MAP_TEST_TAG
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Das Nachfahren im **echten** Dashboard (Hilt, echtes `DashboardViewModel` und `FollowStatusViewModel`; die Sitzung selbst ist eine Attrappe mit zwei Fluessen): der gruene Knopf mit dem
 * Play-Dreieck, der Dialog „Zur Karte / Nachfahren beenden“, das Roadbook-HUD ueber dem Kachelraster (Querformat 380 dp breit, Hochformat ueber die Breite) und der Vorrang des Alarmbandes.
 * Die Kartenseite ist ein Platzhalter-Ziel: geprueft wird nur, dass „Zur Karte“ dorthin navigiert.
 */
@HiltAndroidTest
@UninstallModules(AlarmModule::class, TerrainModule::class)
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class DashboardRoadbookDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository {
        every { supportedPidIds } returns MutableStateFlow(null)
        every { lastConnectedDevice } returns MutableStateFlow(null)
    }

    class NoopNotifier : AlarmNotifier {
        override fun show(alarm: de.adv990de.obd2.alarm.Alarm) = Unit
        override fun clear(kind: AlarmKind) = Unit
    }

    @BindValue @JvmField val alarmNotifier: AlarmNotifier = NoopNotifier()
    @BindValue @JvmField val alarmLog: AlarmLog = AlarmLog.None
    @BindValue @JvmField val alarmController: AlarmController = AlarmController(alarmNotifier, alarmLog)

    class NoTerrainInputs : TerrainInputs {
        override val inputs: Flow<TerrainInput> = MutableSharedFlow(extraBufferCapacity = 1)
    }

    @BindValue @JvmField val terrainInputs: TerrainInputs = NoTerrainInputs()

    private val info = MutableStateFlow<FollowInfo?>(null)
    private val reading = MutableStateFlow<FollowReading>(FollowReading.Searching)

    @BindValue @JvmField val followSession: FollowSession = mockk(relaxed = true) {
        every { info } returns this@DashboardRoadbookDeviceTest.info
        every { reading } returns this@DashboardRoadbookDeviceTest.reading
        every { stop() } answers { this@DashboardRoadbookDeviceTest.info.value = null }
    }

    private fun at(lat: Double, lon: Double) = TrackPoint(null, lat, lon, null, null, null)
    private val path = listOf(at(50.0000, 10.0000), at(50.0010, 10.0000), at(50.0020, 10.0000), at(50.0023, 10.0005), at(50.0023, 10.0020))

    private fun hud() = RoadbookHud(Maneuver(220.0, 240.0, 260.0, 95.0, ManeuverKind.NORMAL), 240.0, 400.0, null, null, path, path[3], path[0], 0.0)

    private fun following(hud: RoadbookHud?) = FollowReading.Following(
        position = path[0],
        result = FollowResult(distanceToTrackM = 3.0, alongM = 10.0, remainingM = 500.0, totalM = 510.0, segmentIndex = 0, bearingDeg = 0.0, foot = path[0]),
        hud = hud,
    )

    @Before
    fun setup() {
        hiltRule.inject()
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(false)
        runBlocking { /* Standard-Belegung genuegt */ }
    }

    @After
    fun unlockOrientation() {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    private fun requestOrientation(orientation: Int) {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        composeRule.waitForIdle()
    }

    private var navigatedToFollow: Boolean = false

    private fun launch() {
        composeRule.setContent {
            val navController = rememberNavController()
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    NavHost(navController, startDestination = "dash") {
                        composable("dash") { DashboardScreen(navController = navController, vm = hiltViewModel()) }
                        composable(Screen.TrackFollow.route, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                            navigatedToFollow = true
                            Text("follow-page")
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().navigationBarsPadding().height(65.dp))
            }
        }
        composeRule.waitForIdle()
    }

    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun waitFor(tag: String, present: Boolean) =
        composeRule.waitUntil(timeoutMillis = 5_000) { (count(tag) > 0) == present }

    // ── Der gruene Knopf ──────────────────────────────────────────────────────

    @Test
    fun withoutASession_theButtonIsTheRecordingButton_andThereIsNoHud() {
        launch()
        composeRule.onNodeWithTag(RECORDING_BUTTON_TEST_TAG).assertContentDescriptionEquals(composeRule.activity.getString(R.string.dashboard_track_start_cd))
        assertEquals(0, count(ROADBOOK_HUD_TEST_TAG))
    }

    @Test
    fun whileFollowing_theButtonShowsItAndTheTapOpensTheMenu() {
        info.value = FollowInfo(7, "Sonntagsrunde")
        launch()
        composeRule.onNodeWithTag(RECORDING_BUTTON_TEST_TAG).assertContentDescriptionEquals(composeRule.activity.getString(R.string.dashboard_track_follow_cd))

        composeRule.onNodeWithTag(RECORDING_BUTTON_TEST_TAG).performClick()
        composeRule.onNodeWithTag(FOLLOW_MENU_OPEN_MAP_TEST_TAG).assertExists()
        composeRule.onNodeWithTag(FOLLOW_MENU_STOP_TEST_TAG).assertExists()
    }

    @Test
    fun theMenuEntryOpenMap_navigatesToTheFollowPage() {
        info.value = FollowInfo(7, "Sonntagsrunde")
        launch()
        composeRule.onNodeWithTag(RECORDING_BUTTON_TEST_TAG).performClick()
        composeRule.onNodeWithTag(FOLLOW_MENU_OPEN_MAP_TEST_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { navigatedToFollow }
        assertTrue(navigatedToFollow)
    }

    @Test
    fun theMenuEntryStop_endsTheSession_andTheButtonIsTheRecordingButtonAgain() {
        info.value = FollowInfo(7, "Sonntagsrunde")
        launch()
        composeRule.onNodeWithTag(RECORDING_BUTTON_TEST_TAG).performClick()
        composeRule.onNodeWithTag(FOLLOW_MENU_STOP_TEST_TAG).performClick()
        verify(exactly = 1) { followSession.stop() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithTag(RECORDING_BUTTON_TEST_TAG).assertContentDescriptionEquals(composeRule.activity.getString(R.string.dashboard_track_start_cd)) }.isSuccess
        }
    }

    // ── Das HUD ───────────────────────────────────────────────────────────────

    @Test
    fun theHudAppearsAndDisappearsWithTheSession_inPortrait() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        info.value = FollowInfo(7, "Sonntagsrunde")
        launch()
        assertEquals(0, count(ROADBOOK_HUD_TEST_TAG))

        reading.value = following(hud())
        waitFor(ROADBOOK_HUD_TEST_TAG, present = true)
        assertEquals(1, count(ROADBOOK_MAP_TEST_TAG))
        val bounds = composeRule.onNodeWithTag(ROADBOOK_HUD_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()
        // Hochformat: ueber die Breite des Dashboards, nicht hoeher als rund 90 dp (sonst deckt es die Kachelwerte).
        assertTrue("HUD zu hoch: ${bounds.height}", bounds.height.value <= 96f)
        val root = composeRule.onNodeWithTag(RECORDING_BUTTON_TEST_TAG).getBoundsInRoot()
        assertTrue(bounds.width.value > 300f)
        assertTrue(root.width.value > 0f)

        reading.value = following(null)
        waitFor(ROADBOOK_HUD_TEST_TAG, present = false)
    }

    @Test
    fun theHudIs380DpWide_inLandscape() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        info.value = FollowInfo(7, "Sonntagsrunde")
        launch()
        reading.value = following(hud())
        waitFor(ROADBOOK_HUD_TEST_TAG, present = true)
        val bounds = composeRule.onNodeWithTag(ROADBOOK_HUD_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertEquals(380f, bounds.width.value, 20f)
    }

    @Test
    fun anAlarmHidesTheHud() {
        info.value = FollowInfo(7, "Sonntagsrunde")
        launch()
        reading.value = following(hud())
        waitFor(ROADBOOK_HUD_TEST_TAG, present = true)

        runBlocking {
            alarmController.onFaultCodes(emptyList(), isBaseline = true)
            val read = listOf(DtcEntry("P0301", isPending = false))
            alarmController.onFaultCodes(read, isBaseline = false)
            alarmController.onFaultCodes(read, isBaseline = false)
        }
        waitFor(ROADBOOK_HUD_TEST_TAG, present = false)
        assertEquals(0, count(ROADBOOK_DISTANCE_TEST_TAG))
    }
}
