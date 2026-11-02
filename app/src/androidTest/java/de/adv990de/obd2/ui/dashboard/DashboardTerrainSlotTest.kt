package de.adv990de.obd2.ui.dashboard

import android.os.SystemClock
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onLast
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.di.TerrainModule
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.terrain.GpsFix
import de.adv990de.obd2.terrain.TerrainInput
import de.adv990de.obd2.terrain.TerrainInputs
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Die Gelaende-Kachel im echten Dashboard: ein Slot mit `TERRAIN` zeigt sie, mit eingeschaltetem GPS-Schalter und Verbindung kommen die Werte an, ohne den Schalter
 * steht der Hinweis, und die Slot-Auswahl bietet den Eintrag an und speichert die Belegung. Die Eingaben (GPS-Fix und Barometer) liefert eine Attrappe — der echte
 * Sensor-Pfad steht in `AndroidPressureProviderDeviceTest` und den JVM-Tests von `TerrainSource`; ein GPS-Fix laesst sich im Test-Raum nicht erzeugen.
 * Eigene Klasse mit `@UninstallModules(TerrainModule)`, damit die Attrappe nur hier die Bindung ersetzt und `DashboardScreenTest` unberuehrt bleibt.
 */
@HiltAndroidTest
@UninstallModules(TerrainModule::class)
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class DashboardTerrainSlotTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository {
        every { supportedPidIds } returns MutableStateFlow(null)
        every { lastConnectedDevice } returns MutableStateFlow(null)
    }

    /** Die Eingaben der Kachel: der Test spielt sie von Hand ein und sieht, ob jemand sammelt. */
    class FakeTerrainInputs : TerrainInputs {
        val source = MutableSharedFlow<TerrainInput>(extraBufferCapacity = 16)
        @Volatile var started = 0
        override val inputs: Flow<TerrainInput> = source.onStart { started++ }
    }

    @BindValue @JvmField val terrainInputs: TerrainInputs = FakeTerrainInputs()

    @Inject lateinit var appPreferences: AppPreferences

    private val fake get() = terrainInputs as FakeTerrainInputs

    @Before
    fun setup() {
        hiltRule.inject()
        runBlocking {
            appPreferences.setSlotPid(0, PidRegistry.RPM.id)
            appPreferences.setSlotPid(1, PidRegistry.SPEED.id)
            appPreferences.setSlotPid(2, PidRegistry.COOLANT_TEMP.id)
            appPreferences.setSlotPid(3, DerivedPids.TERRAIN_ID)
        }
    }

    private fun connectWithLiveValues() {
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(true)
        coEvery { obdRepository.readVin() } returns null
        coEvery { obdRepository.readDtcs() } returns emptyList()
        every { obdRepository.livePids(any()) } returns flow {
            emit(listOf(SensorValue(PidRegistry.RPM, 2500f), SensorValue(PidRegistry.COOLANT_TEMP, 90f)))
            awaitCancellation()
        }
    }

    private fun launch() {
        composeRule.setContent { DashboardScreen(navController = rememberNavController()) }
        composeRule.waitForIdle()
    }

    private fun aFix() = GpsFix(
        elapsedMs = SystemClock.elapsedRealtime(), speedKmh = 50f, altitudeM = 1234f, verticalAccuracyM = 5f,
        bearingDeg = 45f, bearingAccuracyDeg = 10f,
    )

    @Test
    fun withTheSlot_theGpsSwitchAndAConnection_theTileShowsTheValues() {
        runBlocking { appPreferences.setUseGpsSpeed(true) }
        connectWithLiveValues()
        launch()

        composeRule.waitUntil(timeoutMillis = 5_000) { fake.started > 0 }
        fake.source.tryEmit(TerrainInput(aFix(), 1013.25f))

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTagCount(TERRAIN_TILE_TEST_TAG) > 0
        }
        // Die Kachel ist anklickbar (Long-Press) und fasst ihre Kinder zu einem Knoten zusammen: das Feld steht im ungemergten Baum.
        composeRule.onNodeWithTag(TERRAIN_ALTITUDE_TEST_TAG, useUnmergedTree = true).assertTextEquals(formatAltitude(1234f, false).first)
        composeRule.onNodeWithTag(TERRAIN_TILE_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun withoutAnyConnectionToTheVehicle_theTileStillShowsTheValues() {
        // Die Werte kommen vom Telefon: kein Adapter, kein Steuergeraet — die Kachel zeigt sie trotzdem, sobald GPS Werte liefert.
        runBlocking { appPreferences.setUseGpsSpeed(true) }
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(false)
        launch()

        composeRule.waitUntil(timeoutMillis = 5_000) { fake.started > 0 }
        fake.source.tryEmit(TerrainInput(aFix(), 1013.25f))

        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithTagCount(TERRAIN_TILE_TEST_TAG) > 0 }
        composeRule.onNodeWithTag(TERRAIN_ALTITUDE_TEST_TAG, useUnmergedTree = true).assertTextEquals(formatAltitude(1234f, false).first)
        composeRule.onNodeWithTag(TERRAIN_TILE_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun withoutAnyConnectionAndWithoutTheGpsSwitch_theSlotStillSaysWhatIsMissing() {
        runBlocking { appPreferences.setUseGpsSpeed(false) }
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(false)
        launch()

        val hint = composeRule.activity.getString(R.string.dashboard_slot_terrain_gps_off)
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithText(hint).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, fake.started)
    }

    @Test
    fun withoutTheGpsSwitch_theSlotSaysWhatIsMissing_andNothingIsCollected() {
        runBlocking { appPreferences.setUseGpsSpeed(false) }
        connectWithLiveValues()
        launch()

        val hint = composeRule.activity.getString(R.string.dashboard_slot_terrain_gps_off)
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithText(hint).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("ohne GPS-Schalter wird nichts gesammelt", 0, fake.started)
    }

    @Test
    fun withTheSwitchButNoFixYet_theSlotWaitsForGps() {
        runBlocking { appPreferences.setUseGpsSpeed(true) }
        connectWithLiveValues()
        launch()

        // Die Erlaubnis der Debug-App am Geraet ist hier offen; ohne Fix gibt es noch keine Werte: der Hinweis nennt Warten oder fehlende Berechtigung, nie "nicht vom Fahrzeug".
        val waiting = composeRule.activity.getString(R.string.dashboard_slot_waiting_gps)
        val permission = composeRule.activity.getString(R.string.dashboard_slot_gps_permission)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(waiting).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText(permission).fetchSemanticsNodes().isNotEmpty()
        }
        val unsupported = composeRule.activity.getString(R.string.dashboard_slot_unsupported)
        assertTrue(composeRule.onAllNodesWithText(unsupported).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theSlotPickerOffersTheTerrainEntry_andSelectingItPersists() {
        runBlocking { appPreferences.setSlotPid(3, "") }
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(true)
        connectWithLiveValues()
        launch()

        val empty = composeRule.activity.getString(R.string.dashboard_slot_empty)
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithText(empty).fetchSemanticsNodes().isNotEmpty() }
        // Auch der Geschwindigkeits-Slot zeigt den Hinweis (kein Wert im Zyklus): der letzte Treffer ist der vierte Slot, den dieser Test belegen will.
        composeRule.onAllNodesWithText(empty).onLast().performTouchInput { longClick() }
        composeRule.waitForIdle()

        val label = composeRule.activity.getString(R.string.pid_terrain)
        composeRule.onNodeWithTag(SLOT_PICKER_LIST_TEST_TAG).performScrollToNode(hasText(label))
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { appPreferences.slotPids.first() }[3] == DerivedPids.TERRAIN_ID
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
