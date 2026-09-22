package de.adv990de.obd2.simulator

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ConnectionFactory
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.DtcParser
import de.adv990de.obd2.obd.PidParser
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.ui.dashboard.DashboardScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Der Weg vom simulierten Steuergeraet bis in die Kacheln — der einzige Test, der die gesamte
 * Kette am Stueck faehrt: Verbindung, Discovery, Messwert-Strom, ViewModel, Compose-Baum.
 *
 * Im Unterschied zu `DashboardScreenTest` steht hier das *echte* [ObdRepository] im Graphen, nicht
 * ein Mock mit gestubbten Flows. Ein Mock beweist nur, dass die Oberflaeche einen vorgegebenen
 * Zustand richtig zeichnet; dieser Test beweist, dass der Zustand ueberhaupt entsteht. Genau diese
 * Unterscheidung fehlte, als die GPS-Geschwindigkeit zwei Releases lang nie ankam.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SimulatedDashboardTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /**
     * Das echte Repository, verdrahtet mit der echten [ConnectionFactory]. Der Simulator-Zweig
     * braucht weder Bluetooth-Hardware noch eine Berechtigung, deshalb ist das im
     * Instrumentationstest ohne Weiteres konstruierbar.
     */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = ObdRepository(
        ConnectionFactory(ApplicationProvider.getApplicationContext<Context>()),
        PidParser(),
        DtcParser(),
    )

    /** Der DataStore des Tests ist der des Debug-Builds — die Slots werden deshalb gesetzt. */
    @Inject
    lateinit var appPreferences: AppPreferences

    private val simulator = ScannedDevice(device = null, type = BtType.SIMULATOR, name = "OBD Sim")

    @Before
    fun setup() {
        hiltRule.inject()
        // Ohne das haenge der Test an der Slot-Belegung, die der Nutzer zuletzt auf dem Geraet
        // eingestellt hat — der DataStore ist derselbe.
        runBlocking {
            appPreferences.setSlotPid(0, PidRegistry.RPM.id)
            appPreferences.setSlotPid(1, PidRegistry.SPEED.id)
            appPreferences.setSlotPid(2, PidRegistry.COOLANT_TEMP.id)
        }
    }

    @After
    fun teardown() = runBlocking {
        runCatching { obdRepository.disconnect() }
        Unit
    }

    private fun launchDashboard() {
        composeRule.setContent {
            val navController = rememberNavController()
            DashboardScreen(navController = navController)
        }
        composeRule.waitForIdle()
    }

    // --- Test 1 ---

    @Test
    fun readings_from_the_simulator_reach_the_dashboard() {
        launchDashboard()
        // Ausgangszustand ohne Verbindung: die RpmBar zeigt "-- rpm" statt einer Zahl.
        composeRule.onNodeWithText(NO_RPM).assertIsDisplayed()

        runBlocking { obdRepository.connect(simulator) }

        awaitLiveTiles()
    }

    // --- Test 2 ---

    @Test
    fun the_speed_tile_is_filled_from_the_simulator() {
        launchDashboard()
        runBlocking { obdRepository.connect(simulator) }
        awaitLiveTiles()

        composeRule.onNodeWithText(speedLabel()).assertIsDisplayed()
    }

    /**
     * Wartet auf den einzigen Zustand, in dem beides zugleich gilt: Der Drehzahl-Platzhalter ist
     * verschwunden *und* die Kacheln sind gezeichnet.
     *
     * Beides einzeln waere zu schwach. Zwischen Verbindungsaufbau und erstem Messwert zeigt der
     * Screen einen Vollbild-Spinner — dann fehlt "-- rpm" ebenfalls, ohne dass ein Wert geflossen
     * waere. Und die Kachel-Labels stehen auch ohne Verbindung da, weil das Dashboard dann
     * Demo-Kacheln mit dem Wert 0 zeichnet.
     */
    private fun awaitLiveTiles() {
        val label = speedLabel()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(NO_RPM).fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Das Label kommt aus den Ressourcen und wird in ALL-CAPS gezeichnet — sonst haengt der
     *  Test an der Sprache des Geraets. */
    private fun speedLabel(): String =
        composeRule.activity.getString(R.string.pid_speed).uppercase()

    private companion object {
        const val NO_RPM = "-- rpm"

        /** Verbindungsaufbau, Discovery und der erste Poll-Zyklus laufen ueber Pipes im Prozess. */
        const val TIMEOUT_MS = 15_000L
    }
}
