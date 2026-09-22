package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [DashboardScreen].
 *
 * Strategy:
 * - [TestAppModule] (TestInstallIn) provides BluetoothAdapter (relaxed mock), BluetoothDeviceScanner
 *   (relaxed mock), DataStore (real, in-process), and AppDatabase / AppLogDao (in-memory Room).
 * - From those, Hilt auto-constructs: AppPreferences, AppLogRepository, SessionRepository,
 *   DisplaySettingsViewModel, DriveStateViewModel, and DashboardViewModel.
 * - [ObdRepository] has hardware-coupled constructor deps (ConnectionFactory, PidParser, DtcParser)
 *   that are not available in instrumented tests. It is therefore supplied via @BindValue, which
 *   replaces the @Singleton binding for this test class only.
 * - CrashReporter is bound by CrashReporterModule (not replaced); SentryCrashReporter has an
 *   empty @Inject constructor and its methods are no-ops when Sentry is not initialised.
 *
 * The required StateFlow stubs on ObdRepository:
 * - isConnectedFlow  — consumed by DashboardViewModel.launchSessionLoop() and launchAutoConnect()
 *   in init{}, and by DriveStateViewModel.isNavHidden (SharingStarted.Eagerly).
 * - isMovingFlow     — consumed by DriveStateViewModel.isNavHidden (SharingStarted.Eagerly).
 * - supportedPidIds  — consumed by DashboardViewModel.availablePids (stateIn Eagerly).
 * - lastConnectedDevice — consumed by DashboardViewModel.launchAutoConnect / launchReconnectLoop.
 * - latestReadings   — exposed as StateFlow; not directly collected by DashboardScreen but
 *   must be non-null to avoid NPE in the relaxed mock.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /**
     * Replaces the ObdRepository @Singleton binding for this test class.
     * All StateFlow properties must be stubbed because ViewModels subscribe to them
     * eagerly in init{} via stateIn(SharingStarted.Eagerly) and will crash on null.
     */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow }      returns MutableStateFlow(false)
        every { isMovingFlow }         returns MutableStateFlow(false)
        every { supportedPidIds }      returns MutableStateFlow(null)
        every { lastConnectedDevice }  returns MutableStateFlow(null)
        every { latestReadings }       returns MutableStateFlow(emptyList())
    }

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private fun launchDashboardScreen() {
        composeRule.setContent {
            val navController = rememberNavController()
            DashboardScreen(navController = navController)
        }
        composeRule.waitForIdle()
    }

    /**
     * Smoke test: the dashboard renders without crashing when the OBD adapter is disconnected.
     * A non-empty RpmBar ("0 rpm") and the placeholder sensor cards must be present.
     */
    @Test
    fun dashboard_rendersWithoutCrash_whenDisconnected() {
        launchDashboardScreen()
        // Ohne Verbindung zeigt die RpmBar "-- rpm" statt einer Zahl: die Demo-Animation
        // bewegt zwar den Balken, darf aber keine Drehzahl vortaeuschen. Das waitForIdle()
        // oben stellt sicher, dass alle Animationen stehen und der Knoten im Baum ist;
        // assertIsDisplayed() prueft zusaetzlich, dass er nicht verdeckt ist.
        composeRule.onNodeWithText("-- rpm").assertIsDisplayed()
    }

    /**
     * Verifies that the slot sensor cards use the default PID assignments from PidRegistry.
     * When disconnected, DashboardViewModel.placeholderReadings maps each default slot PID to a
     * SensorValue(pid, 0f). SensorCard rendert das lokalisierte Label (pid.labelRes) als
     * Text-Knoten — Slot 0 ist PidRegistry.RPM (defaults = [RPM, SPEED, COOLANT_TEMP]).
     */
    @Test
    fun dashboard_showsSlotCards() {
        launchDashboardScreen()
        // Das Kachel-Label kommt aus den Ressourcen und wird in ALL-CAPS gerendert — auf einem
        // deutschen Geraet also "DREHZAHL". Die Erwartung muss denselben Weg gehen, sonst
        // haengt der Test an der Geraetesprache.
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.pid_rpm).uppercase())
            .assertIsDisplayed()
    }

    /**
     * Smoke test: the dashboard renders without crashing when the OBD adapter is connected but
     * no readings have arrived yet. The screen should show a CircularProgressIndicator while
     * DashboardUiState.readings is still empty and isConnected is true.
     * The test verifies no exception is thrown during composition and that the UI settles.
     */
    @Test
    fun dashboard_rendersWithoutCrash_whenConnected() {
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(true)
        launchDashboardScreen()
        // While connected and readings are empty, DashboardScreen shows a full-screen spinner.
        // There is no assertable static text, so "no crash + idle" is the success criterion.
        // The composeRule.waitForIdle() call inside launchDashboardScreen() ensures all
        // coroutines launched by the ViewModel have settled before we return.
    }
    /**
     * Ohne Verbindung zeigt das Dashboard eine Vorschau der Slot-Belegung. Das Band sagt das
     * ausdruecklich — bis 1.11.0 standen dort Kacheln mit dem Wert 0, die sich von echten
     * Messwerten nicht unterscheiden liessen.
     */
    @Test
    fun dashboard_marksItsPreviewWhenDisconnected() {
        launchDashboardScreen()
        composeRule
            .onNodeWithText(
                composeRule.activity.getString(R.string.dashboard_preview_banner).uppercase()
            )
            .assertIsDisplayed()
    }

    /**
     * Und die Kacheln selbst tragen "--" statt einer Zahl. Geprueft ueber die
     * contentDescription, weil der Wert im Baum aus mehreren Textknoten besteht.
     */
    @Test
    fun dashboard_showsDashesInsteadOfZeroWhenDisconnected() {
        launchDashboardScreen()
        val rpmLabel = composeRule.activity.getString(R.string.pid_rpm)
        composeRule.onNodeWithContentDescription("$rpmLabel: -- rpm").assertIsDisplayed()
    }
}