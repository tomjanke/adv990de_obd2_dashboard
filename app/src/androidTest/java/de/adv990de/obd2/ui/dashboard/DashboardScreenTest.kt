package de.adv990de.obd2.ui.dashboard

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.obd.model.SensorValue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

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
        every { userDisconnectCount } returns MutableStateFlow(0)
        every { isMovingFlow }         returns MutableStateFlow(false)
        every { supportedPidIds }      returns MutableStateFlow(null)
        every { lastConnectedDevice }  returns MutableStateFlow(null)
        every { latestReadings }       returns MutableStateFlow(emptyList())
    }

    /**
     * Echtes AppPreferences (In-Process-DataStore, ueber Hilt injiziert) — gebraucht fuer die drei
     * neuen Tests unten (Long-Press/SlotPicker-Verdrahtung, Keep-Screen-On, Landscape-Grid), um
     * Slot-Zuordnungen deterministisch vorzugeben und `setSlotPid()`s Persistenz direkt am
     * DataStore zu verifizieren statt nur indirekt ueber die Oberflaeche. Gleiches Muster wie
     * [de.adv990de.obd2.simulator.SimulatedDashboardTest].
     */
    @Inject
    lateinit var appPreferences: AppPreferences

    @Before
    fun setup() {
        hiltRule.inject()
        // Der DataStore in diesem Testlauf ist der echte, geraeteweite Debug-DataStore (s.
        // resetSlotPidsToDefault()-KDoc unten) und persistiert damit ueber Testklassen und -laeufe
        // hinweg. Alle Tests dieser Klasse setzten die Default-Belegung (RPM/SPEED/COOLANT_TEMP)
        // bereits stillschweigend voraus (z.B. dashboard_showsSlotCards); dieser Reset macht die
        // Annahme explizit und deterministisch statt sie vom Zufall der Testausfuehrungsreihenfolge
        // bzw. einer vorherigen manuellen Geraetenutzung abhaengig zu machen. Noetig wurde das erst
        // mit dashboard_longPressOnTile_..., das die Belegung selbst absichtlich dauerhaft aendert.
        resetSlotPidsToDefault()
    }

    private fun launchDashboardScreen() {
        composeRule.setContent {
            val navController = rememberNavController()
            DashboardScreen(navController = navController)
        }
        composeRule.waitForIdle()
    }

    /**
     * Rendert DashboardScreen mit einer ueberschriebenen [LocalConfiguration], deren `orientation`
     * auf [Configuration.ORIENTATION_LANDSCAPE] steht — DashboardScreen liest fuer seinen
     * `isLandscape`-Zweig ausschliesslich `LocalConfiguration.current.orientation` (s.
     * DashboardScreen.kt), alles andere (Ressourcen-Strings etc.) haengt nicht daran. Vermeidet
     * eine echte Geraete-Rotation ueber `Activity.setRequestedOrientation()`, die auf dem
     * angeschlossenen physischen Geraet eine echte Aktivitaets-Neuerstellung ausloesen wuerde —
     * unnoetiges Risiko fuer einen reinen Layout-Zweig, der keine Konfigurationsaenderungs-Logik
     * enthaelt. Kein Praezedenzfall fuer echte Rotation existiert in diesem Projekt (kein
     * UiAutomator als Dependency), diese Technik ist die uebliche Compose-Alternative (vgl. wie
     * `@Preview`-Funktionen unterschiedliche Konfigurationen simulieren).
     */
    private fun launchDashboardScreenInLandscape() {
        composeRule.setContent {
            val navController = rememberNavController()
            val landscapeConfiguration = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            CompositionLocalProvider(LocalConfiguration provides landscapeConfiguration) {
                DashboardScreen(navController = navController)
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Setzt die Slot-Belegung auf die Registry-Defaults zurueck (RPM/SPEED/COOLANT_TEMP/leer).
     * Der DataStore in diesem Testlauf ist der echte, geraeteweite Debug-DataStore (s.
     * [de.adv990de.obd2.simulator.SimulatedDashboardTest]) und persistiert damit ueber Testklassen und
     * -laeufe hinweg. Ohne diesen Reset waeren die drei neuen Tests unten von der
     * Ausfuehrungsreihenfolge abhaengig — einer von ihnen aendert die Belegung selbst absichtlich
     * dauerhaft.
     */
    private fun resetSlotPidsToDefault() = runBlocking {
        appPreferences.setSlotPid(0, PidRegistry.RPM.id)
        appPreferences.setSlotPid(1, PidRegistry.SPEED.id)
        appPreferences.setSlotPid(2, PidRegistry.COOLANT_TEMP.id)
        appPreferences.setSlotPid(3, "")
    }

    /** Ein einzelner, feststehender Messwertzyklus mit den drei Default-Slot-PIDs — genug, damit
     *  echte Kacheln (nicht die Vorschau-Platzhalter) im Baum stehen. */
    private fun stubConnectedSessionWithRealReadings() {
        coEvery { obdRepository.readVin() } returns null
        coEvery { obdRepository.readDtcs() } returns emptyList()
        every { obdRepository.livePids(any()) } returns flowOf(
            listOf(
                SensorValue(PidRegistry.RPM, 2500f),
                SensorValue(PidRegistry.SPEED, 87f),
                SensorValue(PidRegistry.COOLANT_TEMP, 90f),
            )
        )
    }

    /**
     * Smoke test: the dashboard renders without crashing when the OBD adapter is disconnected.
     * The disconnected state must be visibly marked and the placeholder sensor cards must be
     * present.
     */
    @Test
    fun dashboard_rendersWithoutCrash_whenDisconnected() {
        launchDashboardScreen()
        // Seit Task 5 zeigt die RpmBar keine Drehzahl-Zahl mehr, sondern eine Gang-Ziffer neben
        // dem Balken; die Demo-Animation bewegt zwar den Balken, darf aber keine Drehzahl
        // vortaeuschen. Die Gang-Ziffer selbst ("–") ist als "nicht live"-Anker ungeeignet: sie
        // erscheint auch VERBUNDEN, sobald der Gang unbekannt ist (z.B. Stillstand). Der
        // Vorschau-Banner ist dagegen exklusiv an isConnected=false gebunden und damit der
        // zuverlaessige Anker. Das waitForIdle() oben stellt sicher, dass alle Animationen stehen
        // und der Knoten im Baum ist; assertIsDisplayed() prueft zusaetzlich, dass er nicht
        // verdeckt ist.
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.dashboard_preview_banner).uppercase())
            .assertIsDisplayed()
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
     * Ende-zu-Ende fuer die Leerlauf-Anzeige: eine gestubbte Sitzung mit laufendem Motor (1200 rpm)
     * und ECU-Geschwindigkeit 0 muss in der Gang-Box neben dem RPM-Balken "N" statt "–" ergeben.
     * Die ECU-Geschwindigkeit reicht — GPS ist hier nicht im Spiel (kein Fix noetig).
     */
    @Test
    fun dashboard_showsNeutral_whenStandingWithTheEngineRunning() {
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(true)
        coEvery { obdRepository.readVin() } returns null
        coEvery { obdRepository.readDtcs() } returns emptyList()
        every { obdRepository.livePids(any()) } returns flowOf(
            listOf(
                SensorValue(PidRegistry.RPM, 1200f),
                SensorValue(PidRegistry.SPEED, 0f),
                SensorValue(PidRegistry.COOLANT_TEMP, 90f),
            )
        )

        launchDashboardScreen()

        val neutralDescription = composeRule.activity.getString(R.string.dashboard_gear_neutral_desc)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(neutralDescription).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(neutralDescription).assertIsDisplayed()
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

    /**
     * ConnectionCoordinator.autoConnect() only reads AppPreferences.lastDevice (real, empty
     * DataStore in this test), so it never attempts anything on its own. What DOES reliably
     * drive isReconnecting=true is the same path DashboardViewModelTest's "reconnect loop starts
     * isReconnecting state before first delay" test uses: a connected session whose livePids()
     * throws ConnectionLost, which DashboardViewModel.onSessionEvent maps to
     * connectionCoordinator.requestReconnect() — and lastConnectedDevice being non-null lets the
     * reconnect loop skip the cold-launch AppPreferences lookup entirely.
     */
    @Test
    fun dashboard_showsConnectingDot_whenReconnecting() {
        val isConnectedFlow = MutableStateFlow(false)
        every { obdRepository.isConnectedFlow } returns isConnectedFlow
        every { obdRepository.lastConnectedDevice } returns MutableStateFlow(mockk<ScannedDevice>())
        coEvery { obdRepository.readVin() } returns null
        coEvery { obdRepository.readDtcs() } returns emptyList()
        every { obdRepository.livePids(any()) } returns flow {
            // Deliberately NOT setting isConnectedFlow.value = false here (unlike the mirrored
            // DashboardViewModelTest recipe): DashboardScreen's rendering depends only on
            // DashboardUiState.isReconnecting, not on obdRepository.isConnectedFlow directly, and
            // flipping that flow from within this same call frame races the ReadingsPipeline
            // collector reentrantly on the real Main dispatcher this instrumented test runs on
            // (see task-3-report.md for the full root-cause analysis).
            throw ObdException.ConnectionLost()
        }
        coEvery { obdRepository.reconnect() } throws Exception("reconnect failed")

        launchDashboardScreen()
        isConnectedFlow.value = true
        composeRule.waitForIdle()

        // A single waitForIdle() only settles Compose's own recomposition/layout/draw queue; it
        // does not span the real DataStore reads and Room writes that ReadingsPipeline.runSession
        // awaits before the ConnectionLost throw is even reached, so isReconnecting can still be
        // false at this point. waitUntil polls until that async chain has actually landed.
        val connectingDesc = composeRule.activity.getString(R.string.dashboard_bt_connecting_desc)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(connectingDesc)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule
            .onNodeWithContentDescription(connectingDesc)
            .assertIsDisplayed()
    }

    // ── Backlog-Sweep 2026-09-24: verbleibende Teilabdeckungs-Luecken ───────────────────────────
    //
    // Prioritaet 4 (RPM-Demo-Sweep-Animation, die displayRpm/displayThreshold im komponierten Baum
    // treibt) ist bewusst NICHT als Test unten vertreten: Die `RpmBar` zeichnet ihre Segmente in
    // einem `Canvas` (reine Pixel, kein Text/keine Semantics-Property) — es gibt in diesem Baum
    // nichts, an dem eine Compose-Assertion den animierten Zwischenwert ablesen koennte, ohne
    // entweder (a) Screenshot-Diffing einzufuehren (kein Praezedenzfall, keine Dependency in
    // diesem Projekt), (b) eine rein testgetriebene Semantics-Eigenschaft in Produktionscode zu
    // ergaenzen (mehr als der erlaubte "minimale testTag fuer einen stabilen Selektor" — hier ginge
    // es um das Freilegen eines internen Animationszustands, nicht um Adressierbarkeit), oder (c)
    // echte Zeit-Sleeps mit Naeherungs-Fenstern zu verwenden (die dieses Projekt an anderer Stelle
    // bewusst vermeidet, s. Auftrag, und auf einem echten Geraet mit variabler Frame-Timing anfaellig
    // fuer Flakiness waeren). Die reine Entscheidungslogik (`shouldStartRpmDemoSweep`) ist bereits
    // separat JVM-getestet (DashboardComponentsTest.kt) — was hier fehlt, ist ausschliesslich die
    // Verdrahtung dieser Entscheidung mit dem echten `Animatable` im komponierten Baum, und die ist
    // aus obigen Gruenden mit den in diesem Projekt verfuegbaren Werkzeugen nicht sinnvoll pruefbar.

    /**
     * Prioritaet 1 (Hauptaufgabe dieses Sweeps): SlotPickerSheetTest deckt die Composable bisher
     * nur isoliert mit einer statischen Liste ab, nie die echte Verdrahtung ab DashboardScreen
     * selbst — Long-Press auf eine Kachel → `pickerSlot`-State → `SlotPickerSheet` erscheint →
     * Auswahl → `vm.setSlotPid()`.
     *
     * Bewusste Abweichung von der im Auftrag vorgeschlagenen Verifikation ueber ein
     * gemocktes/gespietes ViewModel bzw. ein gemocktes AppPreferences: DashboardViewModel und
     * AppPreferences bleiben echt (wie in [de.adv990de.obd2.simulator.SimulatedDashboardTest]), nur
     * ObdRepository bleibt gemockt (wie in der gesamten uebrigen Klasse hier). Die Persistenz wird
     * direkt am echten, In-Process-DataStore verifiziert — das ist der staerkere Beweis: ein Mock
     * haette nur gezeigt, dass `setSlotPid` mit den erwarteten Argumenten AUFGERUFEN wurde, nicht
     * dass die Slot-Zuordnung dadurch TATSAECHLICH persistiert. AppPreferences komplett zu mocken
     * haette zudem bedeutet, jeden der zahlreichen in `DashboardViewModel.init{}`/`stateIn(Eagerly)`
     * gelesenen Flows (slotPids, rpmBlinkThreshold, rpmDynamicThreshold, wheelCircumferenceMm,
     * primaryRatio, secondaryRatio, gearRatios, useGpsSpeed, …) manuell zu stubben — ein fragiles
     * Duplikat der echten Klasse ohne Mehrwert gegenueber dem ohnehin vorhandenen In-Process-Fake.
     */
    @Test
    fun dashboard_longPressOnTile_opensSlotPicker_andSelectionPersistsViaSetSlotPid() {
        // Slot-Defaults sind bereits durch @Before/setup() garantiert.
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(true)
        stubConnectedSessionWithRealReadings()

        launchDashboardScreen()

        val rpmLabel = composeRule.activity.getString(R.string.pid_rpm)
        // Erster Beweis, dass echte (nicht Platzhalter-)Kacheln stehen: die contentDescription
        // traegt den echten Messwert, nicht "--" (vgl. dashboard_showsDashesInsteadOfZeroWhenDisconnected).
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("$rpmLabel: 2500 rpm")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Long-Press auf die RPM-Kachel (Slot 0) — dasselbe Gestenmuster wie LiveValueTileTest
        // (performTouchInput { longClick() }), hier gegen die echte Verdrahtung in DashboardScreen
        // statt gegen eine isoliert instanziierte LiveValueTile.
        composeRule.onNodeWithText(rpmLabel.uppercase()).performTouchInput { longClick() }
        composeRule.waitForIdle()

        // Der Picker zeigt echte Daten aus dem ViewModel: der aktuelle Coolant-Messwert (90) taucht
        // als eigener Listeneintrag auf — das beweist, dass SlotPickerSheet tatsaechlich mit
        // state.readings/availablePids aus DashboardScreen komponiert wurde, nicht nur irgendein
        // Bottom Sheet offen ist. availablePids ist hier (supportedPidIds=null) die volle, nach
        // Name alphabetisch sortierte PidRegistry (19 Eintraege) — RPM selbst stuende als "RPM"
        // erst an 15. Stelle und waere ohne Scrollen nicht sichtbar; "Coolant Temp" (6. Stelle)
        // ist ein verlässlicherer Anker.
        val coolantLabel = composeRule.activity.getString(R.string.pid_coolant_temp)
        composeRule.onNodeWithTag(SLOT_PICKER_LIST_TEST_TAG)
            .performScrollToNode(hasText("$coolantLabel: 90 (°C)"))
        composeRule.onNodeWithText("$coolantLabel: 90 (°C)").assertIsDisplayed()

        // Intake Air Temp ist in keinem Slot belegt und liefert in diesem Zyklus auch keinen
        // Messwert → "--"-Fallback, identisches Format wie
        // SlotPickerSheetTest.showsDashFallback_forNaNAndInfiniteReadings. Position 11 alphabetisch
        // — noch weiter unten als Coolant Temp, also derselbe performScrollToNode-Fix noetig.
        val intakeLabel = composeRule.activity.getString(R.string.pid_intake_air_temp)
        composeRule.onNodeWithTag(SLOT_PICKER_LIST_TEST_TAG)
            .performScrollToNode(hasText("$intakeLabel: -- (°C)"))
        composeRule.onNodeWithText("$intakeLabel: -- (°C)").performClick()
        composeRule.waitForIdle()

        // Der eigentliche Beweis: vm.setSlotPid(0, INTAKE_AIR_TEMP.id) wurde nicht nur aufgerufen,
        // sondern hat auch tatsaechlich persistiert — am echten DataStore gelesen, nicht am Mock
        // verifiziert. waitUntil statt einer einzelnen Pruefung, weil setSlotPid() den DataStore-
        // Write asynchron in viewModelScope anstoesst (vgl. Kommentar bei
        // dashboard_showsConnectingDot_whenReconnecting oben).
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { appPreferences.slotPids.first() }[0] == PidRegistry.INTAKE_AIR_TEMP.id
        }
    }

    /**
     * Der Slot-Picker oeffnet in voller Hoehe (`skipPartiallyExpanded = true`): Ein halbhohes
     * ModalBottomSheet liess im Querformat (~1008 px Fensterhoehe) anfangs nur ~3 Zeilen sichtbar, alle
     * weiteren erst nach Hochziehen. Das Querformat wird ueber die Test-Activity erzwungen (nicht ueber
     * LocalConfiguration — es geht um die echte Fensterhoehe), damit der Test unabhaengig davon laeuft,
     * wie das Geraet gerade gehalten wird. Ohne `skipPartiallyExpanded` liegt die Zeile unter dem
     * Bildschirmrand und `assertIsDisplayed()` scheitert.
     */
    @Test
    fun dashboard_slotPicker_opensFullyExpanded_soLowerRowsAreVisibleInLandscape() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitForIdle()
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(true)
        stubConnectedSessionWithRealReadings()

        launchDashboardScreen()

        val rpmLabel = composeRule.activity.getString(R.string.pid_rpm)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("$rpmLabel: 2500 rpm")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(rpmLabel.uppercase()).performTouchInput { longClick() }
        composeRule.waitForIdle()

        val coolantLabel = composeRule.activity.getString(R.string.pid_coolant_temp)
        composeRule.onNodeWithTag(SLOT_PICKER_LIST_TEST_TAG)
            .performScrollToNode(hasText("$coolantLabel: 90 (°C)"))
        composeRule.onNodeWithText("$coolantLabel: 90 (°C)").assertIsDisplayed()
    }

    /**
     * Prioritaet 2: die `DisposableEffect(Unit)`-Verdrahtung in DashboardScreen setzt
     * `FLAG_KEEP_SCREEN_ON` auf dem Activity-Window, sobald der Screen komponiert wird, und
     * loescht es erst wieder beim Verlassen — seit User-Wunsch 2026-09-29 unabhaengig vom
     * Verbindungsstatus (vorher nur bei `isConnected=true`, s. MEMORY.md). Prueft alle drei
     * relevanten Zustaende: sofort gesetzt bei sichtbarem, aber getrenntem Screen; bleibt gesetzt
     * bei einer echten Verbindung; geloescht erst, wenn der Screen die Komposition verlaesst
     * (`onDispose`) — externes `showDashboard`-State-Flag statt eines zweiten `setContent()`-Aufrufs,
     * da dieser Compose-Test-Rule pro Test nur einmal unterstuetzt.
     */
    @Test
    fun dashboard_keepsScreenOnRegardlessOfConnection_andClearsFlagOnDispose() {
        // Slot-Defaults sind bereits durch @Before/setup() garantiert.
        val isConnectedFlow = MutableStateFlow(false)
        every { obdRepository.isConnectedFlow } returns isConnectedFlow
        stubConnectedSessionWithRealReadings()

        val showDashboard = mutableStateOf(true)
        composeRule.setContent {
            if (showDashboard.value) {
                DashboardScreen(navController = rememberNavController())
            }
        }
        composeRule.waitForIdle()

        assertTrue(
            "expected FLAG_KEEP_SCREEN_ON to already be set while disconnected",
            composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
        )

        isConnectedFlow.value = true
        val rpmLabel = composeRule.activity.getString(R.string.pid_rpm)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("$rpmLabel: 2500 rpm")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "expected FLAG_KEEP_SCREEN_ON to still be set while connected",
            composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
        )

        showDashboard.value = false
        composeRule.waitForIdle()
        assertTrue(
            "expected FLAG_KEEP_SCREEN_ON to be cleared after leaving the Dashboard screen",
            composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0,
        )
    }

    /**
     * Prioritaet 3: der `isLandscape`-Zweig (vier Kacheln in einer Reihe statt 2×2) lief bisher nie
     * in Instrumentation-Tests, da diese durchgehend im Standard-Portrait der Test-Umgebung laufen
     * (kein Rotations-Praezedenzfall/-Tooling in diesem Projekt, s. [launchDashboardScreenInLandscape]).
     *
     * Unterscheidungsmerkmal Portrait vs. Landscape: Slot 0 (RPM) und Slot 2 (COOLANT_TEMP) liegen
     * im Portrait-2×2-Raster in unterschiedlichen Zeilen (unterschiedliches `top`), im
     * Landscape-4er-Raster dagegen in derselben Zeile (gleiches `top`). Slot 0 vs. Slot 1 waere
     * kein geeigneter Anker, weil beide Layouts sie in derselben Zeile zeigen.
     */
    @Test
    fun dashboard_landscape_showsAllFourSlotsInOneRow() {
        // Slot-Defaults sind bereits durch @Before/setup() garantiert.
        launchDashboardScreenInLandscape()

        val rpmTop = composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.pid_rpm).uppercase())
            .getBoundsInRoot().top
        val coolantTop = composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.pid_coolant_temp).uppercase())
            .getBoundsInRoot().top

        assertTrue(
            "expected slot 0 (RPM) and slot 2 (COOLANT_TEMP) to share one row in landscape, " +
                    "was RPM top=$rpmTop COOLANT top=$coolantTop",
            rpmTop == coolantTop,
        )
    }
}
