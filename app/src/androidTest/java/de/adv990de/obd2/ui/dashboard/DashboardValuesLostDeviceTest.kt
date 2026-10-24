package de.adv990de.obd2.ui.dashboard

import android.content.pm.ActivityInfo
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Das laute Band und die GPS-Geschwindigkeit im **echten** Dashboard (Hilt, echtes `DashboardViewModel` und `DashboardScreen`): welches Band bei welchem Zustand steht, dass das schmale
 * Vorschau-Band bei "eingefroren" und "keine aktuellen Werte" nicht mehr erscheint, dass die Geschwindigkeits-Kachel in "veraltet" die GPS-Geschwindigkeit zeigt und sonst "--" (Entscheidung D3),
 * und dass das Band den Kacheln im Querformat nicht mehr Hoehe nimmt, als die 120-dp-Regel erlaubt.
 *
 * **Der Zustand kommt vom Test:** dass die Pipeline ihn richtig erzeugt (Frozen, Stale, GpsSpeed, Ursache des Neuverbindens), prueft `ReadingsPipelineTest` und `DashboardViewModelValuesLostTest` auf dem JVM;
 * hier geht es um die Darstellung. Der Test setzt den `DashboardUiState` des ViewModels (`uiState` ist ein `MutableStateFlow`, nur als `StateFlow` herausgegeben) — der Zustand "eingefroren" liesse sich
 * sonst nur ueber eine Sitzung mit 20 gleichen Zyklen, der Watchdog-Zeit und einem Neuverbinden herbeifuehren. Das Fahrzeug ist nicht verbunden (die Sitzung meldet nur `Idle`); der Test setzt den Zustand
 * nach diesem ersten Ereignis und wiederholt ihn kurz danach, falls das Ereignis sich verspaetet hat.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class DashboardValuesLostDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository {
        every { supportedPidIds } returns MutableStateFlow(null)
        every { lastConnectedDevice } returns MutableStateFlow(null)
        every { latestReadings } returns MutableStateFlow(emptyList())
    }

    @Inject lateinit var appPreferences: AppPreferences

    private lateinit var vm: DashboardViewModel

    @Before
    fun setup() {
        hiltRule.inject()
        runBlocking {
            appPreferences.setSlotPid(0, PidRegistry.RPM.id)
            appPreferences.setSlotPid(1, PidRegistry.SPEED.id)
            appPreferences.setSlotPid(2, PidRegistry.COOLANT_TEMP.id)
            appPreferences.setSlotPid(3, "")
        }
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(false)
        // Die Ausrichtung gehoert diesem Test, nicht dem vorigen: nach einem Querformat-Test (die Klassen laufen hintereinander) dreht das Geraet erst zurueck — der erste Test haette ein
        // halb gedrehtes Fenster. Die Querformat-Tests unten bitten selbst um das Querformat.
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    }

    @After
    fun unlockOrientation() {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    private fun requestOrientation(orientation: Int) {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        composeRule.waitForIdle()
    }

    /** Das Dashboard mit einer Flaeche statt des Hauptmenues darunter (die echte `BottomNav` braucht Wetter und Standort): so haben die Kacheln in etwa die Hoehe wie in der App. */
    private fun launch() {
        composeRule.setContent {
            val navController = rememberNavController()
            val viewModel: DashboardViewModel = hiltViewModel()
            vm = viewModel
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { DashboardScreen(navController = navController, vm = viewModel) }
                Box(Modifier.fillMaxWidth().navigationBarsPadding().height(65.dp))
            }
        }
        composeRule.waitForIdle()
    }

    private fun applyState(transform: (DashboardUiState) -> DashboardUiState) {
        composeRule.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            val state = vm.uiState as MutableStateFlow<DashboardUiState>
            state.value = transform(state.value)
        }
        composeRule.waitForIdle()
    }

    /** Setzt den Zustand und wiederholt ihn nach einer kurzen Pause: ein verspaetetes `Idle` der Sitzung setzte ihn sonst zurueck. */
    private fun setState(transform: (DashboardUiState) -> DashboardUiState) {
        applyState(transform)
        Thread.sleep(400)
        applyState(transform)
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    /** Werte fliessen: alle drei belegten Kacheln haben einen Wert (ohne einen zeigt eine Kachel nur ihren Hinweis, nicht "Name: Wert"). */
    private val liveState = { s: DashboardUiState ->
        s.copy(
            isConnected = true, awaitingFirstValues = false,
            readings = listOf(SensorValue(PidRegistry.RPM, 2500f), SensorValue(PidRegistry.SPEED, 87f), SensorValue(PidRegistry.COOLANT_TEMP, 90f)),
        )
    }

    private val frozenState = { s: DashboardUiState ->
        s.copy(isConnected = true, awaitingFirstValues = false, isStale = true, valuesLost = ValuesLostCause.FROZEN, gpsSpeedKmh = 87.4f)
    }

    private fun bandCount() = composeRule.onAllNodesWithTag(VALUES_LOST_BANNER_TEST_TAG).fetchSemanticsNodes().size

    private fun smallBandCount() =
        composeRule.onAllNodesWithText(string(R.string.dashboard_preview_banner).uppercase()).fetchSemanticsNodes().size +
            composeRule.onAllNodesWithText(string(R.string.dashboard_waiting_banner).uppercase()).fetchSemanticsNodes().size

    private fun speedTile(valueText: String) = composeRule.onNodeWithContentDescription("${string(R.string.pid_speed_gps)}: $valueText km/h")

    // --- Band je Zustand ---

    @Test
    fun frozenValuesShowTheLoudBandWithItsDetail_andNoSmallBand() {
        launch()

        setState(frozenState)

        composeRule.onNodeWithText(string(R.string.values_lost_frozen_title)).assertIsDisplayed()
        assertEquals(1, bandCount())
        assertEquals("das schmale Band erscheint nicht zusaetzlich", 0, smallBandCount())
    }

    @Test
    fun noCurrentValuesShowTheLoudBandWithoutADetailLine() {
        launch()

        setState { it.copy(isConnected = true, awaitingFirstValues = false, isStale = true, valuesLost = ValuesLostCause.SILENT) }

        composeRule.onNodeWithText(string(R.string.values_lost_silent_title)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.values_lost_frozen_detail), substring = true).assertCountEquals(0)
        assertEquals(0, smallBandCount())
    }

    @Test
    fun theReconnectAfterFrozenValuesShowsTheLoudBandWithTheAttempt_insteadOfThePreviewBand() {
        launch()

        setState {
            it.copy(
                isConnected = false, isReconnecting = true, reconnectAttempt = 2, reconnectTotal = 5,
                reconnectCause = ConnectionLostCause.FROZEN_VALUES,
            )
        }

        composeRule.onNodeWithText("Werte eingefroren · verbinde neu 2/5").assertIsDisplayed()
        assertEquals(0, smallBandCount())
    }

    @Test
    fun aPlainReconnectKeepsTheSmallPreviewBand_noLoudOne() {
        launch()

        setState { it.copy(isConnected = false, isReconnecting = true, reconnectAttempt = 2, reconnectTotal = 5, reconnectCause = ConnectionLostCause.LINK) }

        composeRule.onNodeWithText("VORSCHAU · VERBINDUNGSVERSUCH 2/5").assertIsDisplayed()
        assertEquals(0, bandCount())
    }

    @Test
    fun waitingForTheFirstValuesKeepsTheSmallBand_noLoudOne() {
        launch()

        setState { it.copy(isConnected = true, awaitingFirstValues = true) }

        composeRule.onNodeWithText(string(R.string.dashboard_waiting_banner).uppercase()).assertIsDisplayed()
        assertEquals(0, bandCount())
    }

    // --- D3: die GPS-Geschwindigkeit ---

    @Test
    fun inTheStaleStateTheSpeedTileShowsTheGpsSpeed_andEveryOtherTileStaysADash() {
        launch()

        setState(frozenState)

        speedTile("87").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("${string(R.string.pid_rpm)}: -- rpm").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("${string(R.string.pid_coolant_temp)}: -- °C").assertIsDisplayed()
    }

    @Test
    fun withoutAGpsFixTheSpeedTileShowsDashes_evenInTheStaleState() {
        launch()

        setState { frozenState(it).copy(gpsSpeedKmh = null) }

        composeRule.onNodeWithContentDescription("${string(R.string.pid_speed)}: -- km/h").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("${string(R.string.pid_speed_gps)}:", substring = true).assertCountEquals(0)
    }

    @Test
    fun withoutAConnectionAGpsSpeedIsNotShown_aPreviewWithNumbersWasOnceTheCauseOfABugHunt() {
        launch()

        setState { it.copy(isConnected = false, gpsSpeedKmh = 50f) }

        composeRule.onNodeWithContentDescription("${string(R.string.pid_speed)}: -- km/h").assertIsDisplayed()
    }

    // --- Platz: das Band kostet im Querformat Kachelhoehe, aber nie unter die 120 dp ---

    private fun rpmTileHeightDp(): Float {
        val node = composeRule.onNode(hasContentDescription("${string(R.string.pid_rpm)}: ", substring = true)).fetchSemanticsNode()
        return with(Density(composeRule.activity)) { node.boundsInRoot.height.toDp() }.value
    }

    /** Die Tankzeile (40 dp) steht nur, wenn eine Reichweite hinterlegt ist; sie kostet im Querformat ebenfalls Kachelhoehe, und beides zusammen ist der enge Fall. */
    private fun giveTheDashboardAFuelRow() = runBlocking {
        appPreferences.setFuelRangeFullKm(200)
        appPreferences.setFuelKmSinceFill(40f)
    }

    @Test
    fun inLandscapeTheLoudBandLeavesTheTilesAtLeast120dp_alsoWithTheFuelRow() {
        giveTheDashboardAFuelRow()
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        launch()
        setState(liveState)
        val without = rpmTileHeightDp()

        setState(frozenState)
        val stacked = rpmTileHeightDp()

        Log.i("ValuesLostBanner", "Querformat, Kachelhoehe: ohne Band %.1f dp, mit dem lauten Band %.1f dp".format(without, stacked))
        assertTrue("mit dem lauten Band hat die Kachel nur $stacked dp (Regel: mindestens 120 dp), ohne $without dp", stacked >= 120f)
    }

    @Test
    fun inPortraitTheTilesKeepTheirHeight_theGridHasRoomForTheBand() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        launch()
        setState(liveState)
        val without = rpmTileHeightDp()

        setState(frozenState)
        val with = rpmTileHeightDp()

        Log.i("ValuesLostBanner", "Hochformat, Kachelhoehe: ohne Band %.1f dp, mit dem lauten Band %.1f dp".format(without, with))
        assertEquals("die Kacheln sind im Hochformat fest hoch", without, with, 0.5f)
    }
}
