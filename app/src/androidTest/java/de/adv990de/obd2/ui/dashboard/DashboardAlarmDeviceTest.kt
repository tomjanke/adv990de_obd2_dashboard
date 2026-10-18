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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.alarm.Alarm
import de.adv990de.obd2.alarm.AlarmController
import de.adv990de.obd2.alarm.AlarmKind
import de.adv990de.obd2.alarm.AlarmLog
import de.adv990de.obd2.alarm.AlarmNotifier
import de.adv990de.obd2.alarm.AlarmSeverity
import de.adv990de.obd2.alarm.alarmMessage
import de.adv990de.obd2.bluetooth.BluetoothException
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.di.AlarmModule
import de.adv990de.obd2.di.TerrainModule
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.terrain.GpsFix
import de.adv990de.obd2.terrain.TerrainInput
import de.adv990de.obd2.terrain.TerrainInputs
import de.adv990de.obd2.ui.components.FUEL_GAUGE_ROW_TEST_TAG
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.pow

/**
 * Das Alarmband im **echten** Dashboard (Hilt, echtes `DashboardViewModel` mit gespielten `livePids`): ein Alarm erscheint als Band, der Tipp quittiert, zwei Alarme zeigen den wichtigeren mit
 * „+1“, und die Kachelreihe schrumpft unter dem Band nicht unter die dokumentierte Mindesthoehe — im Quer- UND im Hochformat, auch gestapelt mit dem Vorschau-Band (ohne Verbindung) und mit der
 * Gelaende-Kachel. Der Alarm entsteht aus Messwerten (Kuehlmittel ab 100 °C ueber 5 s, Spannung unter 12,2 V ueber 10 s nach 15 s Einschwingzeit), nicht aus einem eingespielten `Alarm`: der ganze
 * Weg Pipeline → ViewModel → Controller → Band laeuft. Einzige Ausnahme sind die Faelle ohne Verbindung: ein Fehler-Alarm ueberlebt das Ende der Sitzung (Absicht, `AlarmTracker.onSessionEnded`),
 * und der Test erzeugt ihn ueber den oeffentlichen Weg des Controllers (`onFaultCodes`: Basis, dann zwei Auslesungen mit einem neuen Code — so wie die Pipeline es meldet).
 *
 * **Die Zeit gehoert dem Test:** die Dauern der Regeln (5 s, 10 s, 15 s) laufen auf der Uhr des ViewModels (`nowMillis`, schon heute eine Naht fuer die JVM-Tests). Der Test setzt sie je Zyklus von
 * Hand, statt Sekunden zu warten — und wartet, bis das ViewModel den Zyklus verarbeitet hat, bevor er die Uhr weiterstellt (sonst sähe dieser Zyklus schon die neue Zeit). Die Benachrichtigung ist eine
 * Attrappe (`@UninstallModules(AlarmModule)`, wie `DashboardTerrainSlotTest` fuer `TerrainModule`): sie zeichnet `show`/`clear` auf, damit der Test sieht, was das Quittieren an sie meldet. Der
 * `AlarmController` kommt als `@BindValue` aus dem Test (mit dieser Attrappe), damit der Test ihm vor dem Start des Dashboards einen Fehler-Alarm geben kann.
 *
 * **Bewusst nicht abgedeckt — gemessen am 2026-10-18 (Pixel 8 Pro, Querformat, Tankzeile 40 dp, Menue-Ersatz 65 dp), Entscheidung offen:** (1) Vorschau-Band MIT Grundzeile + Fehler-Alarm: die Kachel ist
 * 105,3 dp hoch (161,3 dp ohne Band), unter den 120 dp; (2) Gelaende-Kachel unter dem Band: die Kachel ist 156,0 dp hoch, aber das Peilungs-Feld nur 16,0 dp (24,0 dp ohne Band) fuer eine
 * 24 dp hohe Textzeile — das Textlayout meldet Ueberlauf, die Zeichen selbst sind vollstaendig (die unterste Tintenzeile liegt 3 Bildpunkte, rund 1 dp, ueber dem Feldrand, ohne Band dieselbe), bei
 * groesserer Systemschrift wuerde sie geschnitten. Im Hochformat halten beide Faelle (170 dp). Solange das offen ist, steht hier kein Test, der es als in Ordnung festschriebe.
 */
@HiltAndroidTest
@UninstallModules(AlarmModule::class, TerrainModule::class)
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class DashboardAlarmDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository {
        every { supportedPidIds } returns MutableStateFlow(null)
        every { lastConnectedDevice } returns MutableStateFlow(null)
        every { latestReadings } returns MutableStateFlow(emptyList())
    }

    /** Zeichnet auf, was der Controller der Systembenachrichtigung sagt — in der Reihenfolge, als „show KIND SEVERITY“ bzw. „clear KIND“. */
    class RecordingNotifier : AlarmNotifier {
        val calls = CopyOnWriteArrayList<String>()
        override fun show(alarm: Alarm) { calls += "show ${alarm.kind} ${alarm.severity}" }
        override fun clear(kind: AlarmKind) { calls += "clear $kind" }
    }

    // Mit dem Modul fallen beide Bindungen weg (Notifier und Log): beide kommen hier.
    @BindValue @JvmField val alarmNotifier: AlarmNotifier = RecordingNotifier()
    @BindValue @JvmField val alarmLog: AlarmLog = AlarmLog.None

    // Der Controller des ViewModels (sonst baut Hilt einen eigenen, den der Test nicht erreicht). Muss nach den beiden Attrappen stehen.
    @BindValue @JvmField val alarmController: AlarmController = AlarmController(alarmNotifier, alarmLog)

    /** Die Eingaben der Gelaende-Kachel (GPS-Fix und Barometer), vom Test von Hand eingespielt. */
    class FakeTerrainInputs : TerrainInputs {
        val source = MutableSharedFlow<TerrainInput>(extraBufferCapacity = 64)
        override val inputs: Flow<TerrainInput> = source
    }

    @BindValue @JvmField val terrainInputs: TerrainInputs = FakeTerrainInputs()

    @Inject lateinit var appPreferences: AppPreferences

    /** Die Zyklen, die der Test dem Dashboard einspielt (Sitzung laeuft, sobald jemand sammelt). */
    private val cycles = MutableSharedFlow<List<SensorValue>>(extraBufferCapacity = 16)

    private val clock = AtomicLong(1_000)
    private var marker = 0
    private lateinit var vm: DashboardViewModel

    private val notifier get() = alarmNotifier as RecordingNotifier
    private val terrain get() = (terrainInputs as FakeTerrainInputs).source

    @Before
    fun setup() {
        hiltRule.inject()
        runBlocking {
            appPreferences.setSlotPid(0, PidRegistry.RPM.id)
            appPreferences.setSlotPid(1, PidRegistry.SPEED.id)
            appPreferences.setSlotPid(2, PidRegistry.COOLANT_TEMP.id)
            appPreferences.setSlotPid(3, "")
        }
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(true)
        coEvery { obdRepository.readVin() } returns null
        coEvery { obdRepository.readDtcs() } returns emptyList()
        every { obdRepository.livePids(any()) } returns cycles
    }

    @After
    fun unlockOrientation() {
        // Die Activity endet mit dem Test; der Wunsch nach einer Ausrichtung soll das Geraet nicht festhalten, falls ein Test vorher scheitert.
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    private fun requestOrientation(orientation: Int) {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        composeRule.waitForIdle()
    }

    /**
     * Das Dashboard mit dem Hauptmenue-Ersatz darunter: die echte `BottomNav` braucht Wetter-Abruf und Standort, und ihre Hoehe ist festgelegt (Trennlinie 1 dp + 64 dp + Systemleiste, s.
     * `BottomNavTest.theMainMenuAndTheAmbientStatusRowAreExactlyAsTall`) — ohne sie waeren die Kacheln im Test um diese Hoehe grosszuegiger als in der App.
     *
     * [waitForSession]: wartet, bis die Sitzung die Zyklen **abonniert** hat (`subscriptionCount`). `tryEmit` auf einen `MutableSharedFlow` ohne Abonnenten verwirft den Wert still — ein
     * Zeichen "die Sitzung ist gestartet" (etwa `onStart`) kommt vor der Anmeldung des Abonnenten und schuetzt davor nicht. Ohne Sitzung (nicht verbunden) gibt es nichts zu abonnieren.
     */
    private fun launch(waitForSession: Boolean = true) {
        composeRule.setContent {
            val navController = rememberNavController()
            val viewModel: DashboardViewModel = hiltViewModel()
            vm = viewModel
            viewModel.nowMillis = { clock.get() }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { DashboardScreen(navController = navController, vm = viewModel) }
                Box(Modifier.fillMaxWidth().navigationBarsPadding().height(65.dp))
            }
        }
        composeRule.waitForIdle()
        if (waitForSession) composeRule.waitUntil(timeoutMillis = 5_000) { cycles.subscriptionCount.value > 0 }
    }

    /**
     * Ein OBD-Zyklus zur Zeit [atMs] der Uhr des ViewModels (Drehzahl 2500: der Motor laeuft); kehrt zurueck, sobald das ViewModel ihn verarbeitet hat. Der Kuehlmittelwert traegt eine Marke
     * (`+ n/100`), an der der Test den Zyklus erkennt — bleibt im selben Band und zeigt als ganze Zahl dasselbe.
     */
    private fun cycle(atMs: Long, coolantC: Float, volts: Float? = null) {
        marker++
        val coolantValue = coolantC + marker / 100f
        clock.set(atMs)
        val values = buildList {
            add(SensorValue(PidRegistry.RPM, 2500f))
            add(SensorValue(PidRegistry.COOLANT_TEMP, coolantValue))
            if (volts != null) add(SensorValue(PidRegistry.CONTROL_MODULE_VOLTAGE, volts))
        }
        check(cycles.tryEmit(values)) { "der Zyklus liess sich nicht einspielen" }
        composeRule.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.readings.any { it.pid.id == PidRegistry.COOLANT_TEMP.id && it.value == coolantValue } }
        // Die Anzeige und die Alarme eines Zyklus werden in EINEM Schritt des Haupt-Threads verarbeitet; dieser Rundlauf kehrt erst nach ihm zurueck.
        composeRule.runOnUiThread { }
        composeRule.waitForIdle()
    }

    /** Die Kuehlmittel-Warnung: Probe bei 1 s und bei 7 s (6 s ueber 100 °C, die Regel will 5 s). */
    private fun raiseCoolantWarning() {
        cycle(atMs = 1_000, coolantC = 105f)
        cycle(atMs = 7_000, coolantC = 105f)
    }

    private fun bannerCount() = composeRule.onAllNodesWithTag(ALARM_BANNER_TEST_TAG).fetchSemanticsNodes().size

    private fun waitForBanner() = composeRule.waitUntil(timeoutMillis = 5_000) { bannerCount() > 0 }

    private fun waitForNoBanner() = composeRule.waitUntil(timeoutMillis = 5_000) { bannerCount() == 0 }

    private fun message(alarm: Alarm) = alarmMessage(composeRule.activity.resources, alarm)

    private fun coolantMessage() = message(Alarm(AlarmKind.COOLANT, AlarmSeverity.WARNING, value = 105f))

    private fun voltageMessage() = message(Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 11.9f))

    private fun more() = composeRule.onAllNodesWithTag(ALARM_BANNER_MORE_TEST_TAG, useUnmergedTree = true)

    // --- Erscheinen, Quittieren ---

    @Test
    fun withoutAnAlarm_thereIsNoBanner() {
        launch()

        cycle(atMs = 1_000, coolantC = 90f)
        cycle(atMs = 7_000, coolantC = 90f)

        assertEquals(0, bannerCount())
        assertEquals(emptyList<String>(), notifier.calls.toList())
    }

    @Test
    fun aCoolantAlarmAppearsAsABanner_theTapAcknowledgesIt_andTheNotifierSawOneShowAndOneClear() {
        launch()
        cycle(atMs = 1_000, coolantC = 105f)
        assertEquals("nach 0 s Dauer noch kein Band", 0, bannerCount())

        cycle(atMs = 7_000, coolantC = 105f)

        waitForBanner()
        composeRule.onNodeWithTag(ALARM_BANNER_TEST_TAG).assertIsDisplayed()
        composeRule.onNode(hasText(coolantMessage())).assertIsDisplayed()
        assertEquals("ein einzelner Alarm hat keinen Mehr-Zaehler", 0, more().fetchSemanticsNodes().size)
        assertEquals(listOf("show COOLANT WARNING"), notifier.calls.toList())

        composeRule.onNodeWithTag(ALARM_BANNER_TEST_TAG).performClick()

        waitForNoBanner()
        // Der Alarm besteht weiter (nur verborgen) und neue Messwerte zeigen ihn nicht erneut — weder als Band noch als zweiter `show`.
        cycle(atMs = 12_000, coolantC = 105f)
        cycle(atMs = 13_000, coolantC = 106f)
        assertEquals("ein quittierter Alarm kommt mit dem naechsten Messwert nicht zurueck", 0, bannerCount())
        assertEquals(listOf("show COOLANT WARNING", "clear COOLANT"), notifier.calls.toList())
    }

    @Test
    fun twoAlarmsShowTheMoreImportantOneWithPlusOne_andTheSecondOneAfterAcknowledgingTheFirst() {
        launch()
        // Der Motor laeuft seit 1 s; die Spannung zaehlt erst nach 15 s Einschwingzeit (ab 17 s), die Warnung braucht danach 10 s (ab 28 s).
        cycle(atMs = 1_000, coolantC = 105f, volts = 11.9f)
        cycle(atMs = 17_000, coolantC = 105f, volts = 11.9f)
        waitForBanner()
        composeRule.onNode(hasText(coolantMessage())).assertIsDisplayed()
        assertEquals("erst der Kuehlmittel-Alarm: kein Mehr-Zaehler", 0, more().fetchSemanticsNodes().size)

        cycle(atMs = 28_000, coolantC = 105f, volts = 11.9f)

        composeRule.waitUntil(timeoutMillis = 5_000) { more().fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText(coolantMessage())).assertIsDisplayed() // Warnung vor Warnung: nach der Art, Kuehlmittel zuerst
        composeRule.onNodeWithTag(ALARM_BANNER_MORE_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertEquals("+1", more().fetchSemanticsNodes().single().config[SemanticsProperties.Text].single().text)

        composeRule.onNodeWithTag(ALARM_BANNER_TEST_TAG).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodes(hasText(voltageMessage())).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("nur noch ein Alarm: kein Mehr-Zaehler", 0, more().fetchSemanticsNodes().size)

        composeRule.onNodeWithTag(ALARM_BANNER_TEST_TAG).performClick()

        waitForNoBanner()
        assertEquals(
            listOf("show COOLANT WARNING", "show VOLTAGE WARNING", "clear COOLANT", "clear VOLTAGE"),
            notifier.calls.toList(),
        )
    }

    // --- Platz: die Kachelreihe schrumpft, bleibt aber mindestens so hoch wie dokumentiert ---

    private fun coolantTile() = composeRule.onNode(
        hasContentDescription(composeRule.activity.getString(R.string.pid_coolant_temp) + ": ", substring = true),
    )

    private fun dp(px: Float) = with(Density(composeRule.activity)) { px.toDp() }.value

    private fun heightDp(node: SemanticsNodeInteraction): Float = fetchBounds(node).let { dp(it.bottom - it.top) }

    private fun fetchBounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot

    private fun giveTheDashboardAFuelRow() = runBlocking {
        appPreferences.setFuelRangeFullKm(200)
        appPreferences.setFuelKmSinceFill(40f)
    }

    private fun logHeights(label: String, withoutBanner: Float, withBanner: Float, banner: Float) =
        Log.i("DashboardAlarmHeights", "$label: Kachel ohne Band %.1f dp, mit Band %.1f dp, Band %.1f dp".format(withoutBanner, withBanner, banner))

    /**
     * Misst die Kachelreihe ohne und mit Band (und mit Tankzeile): die Kachel muss mit dem Band mindestens [MIN_TILE_DP] hoch bleiben; Band, Kachel und Tankzeile stehen in dieser
     * Reihenfolge uebereinander. Die gemessenen Hoehen gehen ins Log (Tag `DashboardAlarmHeights`) — sie stehen im Bericht, nicht nur in einer Schwelle.
     */
    private fun checkTilesStayVisibleUnderTheBanner(label: String) {
        giveTheDashboardAFuelRow()
        launch()
        cycle(atMs = 1_000, coolantC = 105f)
        coolantTile().assertIsDisplayed()
        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).assertIsDisplayed()
        val heightBefore = heightDp(coolantTile())

        cycle(atMs = 7_000, coolantC = 105f)
        waitForBanner()

        assertTileUnderTheBanner(label, heightBefore)
    }

    /** Die gemeinsame Pruefung: Band, Kachel und Tankzeile in der Reihenfolge, das Band 56 dp, die Kachel mit dem Band mindestens [MIN_TILE_DP] und um hoechstens die Bandhoehe kleiner. */
    private fun assertTileUnderTheBanner(label: String, heightWithoutBanner: Float) {
        val banner = fetchBounds(composeRule.onNodeWithTag(ALARM_BANNER_TEST_TAG))
        val tile = fetchBounds(coolantTile())
        val fuel = fetchBounds(composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG))
        val heightWith = dp(tile.bottom - tile.top)
        val bannerDp = dp(banner.bottom - banner.top)
        logHeights(label, heightWithoutBanner, heightWith, bannerDp)

        coolantTile().assertIsDisplayed()
        composeRule.onNodeWithTag(ALARM_BANNER_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).assertIsDisplayed()
        assertTrue("$label: Band ($banner) liegt ueber der Kachel ($tile)", banner.bottom <= tile.top + 1f)
        assertTrue("$label: Kachel ($tile) liegt ueber der Tankzeile ($fuel)", tile.bottom <= fuel.top + 1f)
        assertTrue("$label: das Band ist mindestens 56 dp hoch, war $bannerDp", bannerDp >= 55.5f)
        assertTrue("$label: die Kachel bleibt mit dem Band mindestens ${MIN_TILE_DP.toInt()} dp hoch, war $heightWith (ohne Band $heightWithoutBanner)", heightWith >= MIN_TILE_DP)
        assertTrue("$label: das Band nimmt der Kachel hoechstens seine eigene Hoehe: $heightWithoutBanner -> $heightWith", heightWithoutBanner - heightWith <= bannerDp + 1f)
    }

    @Test
    fun inLandscape_theTilesStayVisibleUnderTheBanner() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

        checkTilesStayVisibleUnderTheBanner("Querformat")
    }

    @Test
    fun inPortrait_theTilesStayVisibleUnderTheBanner() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        checkTilesStayVisibleUnderTheBanner("Hochformat")
    }

    // --- Gestapelt: Vorschau-Band (ohne Verbindung) + ein Fehler-Alarm, der das Ende der Sitzung ueberlebt ---

    /**
     * Ein Fehler-Alarm, der besteht, ohne dass eine Sitzung laeuft: die Fahrt ist vorbei, ein neuer Code wurde gefunden und ist noch nicht quittiert (er ueberlebt das Ende der Sitzung mit Absicht).
     * Gemeldet wird er wie von der Pipeline: erst die Basis beim Verbinden, dann zwei Auslesungen hintereinander mit demselben neuen Code (das Entprellen der Fehlerwache).
     */
    private fun givenAPersistingFaultAlarm() = runBlocking {
        alarmController.onFaultCodes(emptyList(), isBaseline = true)
        val read = listOf(DtcEntry("P0301", isPending = false))
        alarmController.onFaultCodes(read, isBaseline = false)
        alarmController.onFaultCodes(read, isBaseline = false)
        check(alarmController.alarms.value.single().kind == AlarmKind.FAULT_CODE) { "der Fehler-Alarm ist nicht entstanden: ${alarmController.alarms.value}" }
    }

    /** Der Zustand "Vorschau · nicht verbunden", ohne Grund. */
    private fun givenOffline() {
        every { obdRepository.isConnectedFlow } returns MutableStateFlow(false)
    }

    /**
     * Der Zustand eines gescheiterten Verbindungsaufbaus: der Adapter meldet "verbunden", die Sitzung scheitert (wie nach einem Fehler im echten Betrieb), das Vorschau-Band nennt den Grund in
     * einer zweiten Zeile (`previewBannerReason`).
     */
    private fun givenAFailedConnection() {
        every { obdRepository.livePids(any()) } returns flow { throw BluetoothException.ConnectionFailed("test") }
    }

    /** Misst im Zustand "Fehler-Alarm ohne Sitzung" die Kachel mit Band und, nach dem Quittieren, ohne Band — im selben Zustand des Vorschau-Bandes. */
    private fun checkTilesUnderTheBannerWithThePreviewBand(label: String, withReason: Boolean) {
        giveTheDashboardAFuelRow()
        givenAPersistingFaultAlarm()
        if (withReason) givenAFailedConnection() else givenOffline()
        launch(waitForSession = false)
        waitForBanner()
        val reason = composeRule.activity.getString(R.string.obd_err_connection_failed)
        if (withReason) {
            composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithText(reason).fetchSemanticsNodes().isNotEmpty() }
        } else {
            composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.dashboard_preview_banner).uppercase()).assertCountAtLeastOne()
            assertEquals("kein Grund im Band", 0, composeRule.onAllNodesWithText(reason).fetchSemanticsNodes().size)
        }
        composeRule.waitForIdle()
        val banner = fetchBounds(composeRule.onNodeWithTag(ALARM_BANNER_TEST_TAG))
        val withBanner = heightDp(coolantTile())

        composeRule.onNodeWithTag(ALARM_BANNER_TEST_TAG).performClick()
        waitForNoBanner()
        composeRule.waitForIdle()
        val withoutBanner = heightDp(coolantTile())

        logHeights(label, withoutBanner, withBanner, dp(banner.bottom - banner.top))
        assertTrue("$label: die Kachel bleibt mit dem Band mindestens ${MIN_TILE_DP.toInt()} dp hoch, war $withBanner (ohne Band $withoutBanner)", withBanner >= MIN_TILE_DP)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountAtLeastOne() {
        assertTrue("kein Knoten gefunden", fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun inLandscape_withThePreviewBandAndAPersistingFaultAlarm_theTilesStayVisible() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

        checkTilesUnderTheBannerWithThePreviewBand("Querformat, Vorschau-Band + Fehler-Alarm", withReason = false)
    }

    @Test
    fun inPortrait_withThePreviewBandAndAPersistingFaultAlarm_theTilesStayVisible() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        checkTilesUnderTheBannerWithThePreviewBand("Hochformat, Vorschau-Band + Fehler-Alarm", withReason = false)
    }

    @Test
    fun inPortrait_withThePreviewBandItsReasonLineAndAPersistingFaultAlarm_theTilesStayVisible() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        checkTilesUnderTheBannerWithThePreviewBand("Hochformat, Vorschau-Band + Grund + Fehler-Alarm", withReason = true)
    }

    // --- Gelaende-Kachel unter dem Band ---

    private fun terrainBounds(tag: String): Rect {
        val b = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
        return Rect(b.left.value, b.top.value, b.right.value, b.bottom.value)
    }

    /** Die Layout-Daten der Peilungs-Zeile: Hoehe des Feldes, Hoehe des gesetzten Textes und ob er ueberlaeuft (alles aus dem Textlayout, nicht aus dem Quelltext). */
    private class HeadingLayout(val fieldDp: Float, val textDp: Float, val overflows: Boolean, val fontSizeSp: Float)

    private fun headingLayout(): HeadingLayout {
        val node = composeRule.onNodeWithTag(TERRAIN_HEADING_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.single()
        return HeadingLayout(
            fieldDp = dp(node.boundsInRoot.height),
            textDp = dp(layout.size.height.toFloat()),
            overflows = layout.hasVisualOverflow,
            fontSizeSp = layout.layoutInput.style.fontSize.value,
        )
    }

    /** Der Druck in hPa auf der Hoehe [altitudeM] nach der Standardatmosphaere (die Umkehrung von `pressureAltitudeM`). */
    private fun pressureAt(altitudeM: Float): Float = (1013.25 * (1.0 - altitudeM / 44330.0).pow(5.255)).toFloat()

    /** Spielt 15 Fixe im Abstand von 1 s ein (50 km/h, Nordost, +4 % Steigung) und wartet, bis die Kachel Hoehe, Steigung und Peilung zeigt. */
    private fun feedTerrainFixes(fromMs: Long) {
        composeRule.waitUntil(timeoutMillis = 5_000) { terrain.subscriptionCount.value > 0 }
        for (i in 0 until 15) {
            val now = fromMs + i * 1_000L
            clock.set(now)
            val altitude = 1234f + 0.556f * i
            val fix = GpsFix(elapsedMs = now, speedKmh = 50f, altitudeM = altitude, verticalAccuracyM = 5f, bearingDeg = 45f, bearingAccuracyDeg = 10f)
            check(terrain.tryEmit(TerrainInput(fix, pressureAt(altitude - 100f))))
            composeRule.waitForIdle()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { vm.terrain.value?.let { it.altitudeM != null && it.gradePercent != null && it.headingDeg != null } == true }
        composeRule.waitForIdle()
    }

    private fun assertInside(inner: Rect, outer: Rect, what: String) {
        val slack = 0.5f
        assertTrue("$what $inner liegt nicht in der Kachel $outer", inner.left >= outer.left - slack && inner.top >= outer.top - slack &&
            inner.right <= outer.right + slack && inner.bottom <= outer.bottom + slack)
    }

    private fun assertNoOverlap(a: Rect, b: Rect, what: String) = assertFalse("$what: $a und $b ueberlappen", a.overlaps(b.deflate(0.5f)))

    /**
     * Die Gelaende-Kachel (Slot 4) unter dem Band: dieselben Feldpruefungen wie `TerrainTileDeviceTest.checkLayout` (jedes Feld in der Kachel, keine Ueberlappung, nichts zusammengedrueckt), hier im
     * echten Dashboard, und zusaetzlich: der Peilungstext hat mit dem Band **dieselbe Schrift** wie ohne (er schrumpft, wenn ihm die Hoehe fehlt — die Kachel hat 102 dp fuer Windrose und
     * Peilung, s. `GRADE_BAND_HEIGHT`). Der Vergleich mit dem Zustand ohne Band (Tipp auf das Band) ist selbstkalibrierend: er braucht keine feste Schriftgroesse.
     */
    private fun checkTerrainTileUnderTheBanner(label: String) {
        giveTheDashboardAFuelRow()
        runBlocking {
            appPreferences.setSlotPid(3, DerivedPids.TERRAIN_ID)
            appPreferences.setUseGpsSpeed(true)
        }
        launch()
        raiseCoolantWarning()
        waitForBanner()
        feedTerrainFixes(fromMs = 8_000)
        val tileNode = composeRule.onNodeWithTag(TERRAIN_TILE_TEST_TAG)
        tileNode.assertIsDisplayed()

        val tile = terrainBounds(TERRAIN_TILE_TEST_TAG)
        val fields = listOf(
            "Hoehe" to terrainBounds(TERRAIN_ALTITUDE_TEST_TAG), "Windrose" to terrainBounds(TERRAIN_ROSE_TEST_TAG), "Peilung" to terrainBounds(TERRAIN_HEADING_TEST_TAG),
            "Graph" to terrainBounds(TERRAIN_GRAPH_TEST_TAG), "Steigung" to terrainBounds(TERRAIN_GRADE_TEST_TAG),
        )
        val heading = headingLayout()
        val tileWithBanner = tile.height

        composeRule.onNodeWithTag(ALARM_BANNER_TEST_TAG).performClick()
        waitForNoBanner()
        composeRule.waitForIdle()
        val headingWithoutBanner = headingLayout()
        val tileWithoutBanner = terrainBounds(TERRAIN_TILE_TEST_TAG).height
        Log.i(
            "DashboardAlarmHeights",
            "$label (Gelaende): Kachel ohne Band %.1f dp, mit Band %.1f dp; Peilung Feld %.1f dp (ohne Band %.1f), Text %.1f dp (ohne %.1f), Schrift %.1f sp (ohne %.1f), Ueberlauf %s"
                .format(tileWithoutBanner, tileWithBanner, heading.fieldDp, headingWithoutBanner.fieldDp, heading.textDp, headingWithoutBanner.textDp, heading.fontSizeSp, headingWithoutBanner.fontSizeSp, heading.overflows),
        )

        for ((name, rect) in fields) assertInside(rect, tile, name)
        val altitude = fields[0].second; val rose = fields[1].second; val headingBox = fields[2].second; val graph = fields[3].second; val grade = fields[4].second
        assertTrue("$label: Kachel mit Band ${tileWithBanner} dp, erwartet mindestens ${MIN_TILE_DP.toInt()}", tileWithBanner >= MIN_TILE_DP)
        assertTrue("$label: Windrose ${rose.width} x ${rose.height} dp ist nicht quadratisch", abs(rose.width - rose.height) < 1f)
        assertTrue("$label: Windrose ${rose.width} dp ist zu klein oder zu gross", rose.width in 66f..78f)
        assertTrue("$label: Hoehe ist zusammengedrueckt: ${altitude.height} dp", altitude.height >= 20f)
        assertTrue("$label: Peilung ist zusammengedrueckt: ${headingBox.height} dp", headingBox.height >= 12f)
        assertTrue("$label: Steigung ist zusammengedrueckt: ${grade.height} dp", grade.height >= 14f)
        assertTrue("$label: Graph ist zusammengedrueckt: ${graph.height} dp", graph.height >= 40f && graph.width >= 60f)
        assertNoOverlap(altitude, rose, "$label: Hoehe und Windrose")
        assertNoOverlap(rose, graph, "$label: Windrose und Graph")
        assertNoOverlap(headingBox, graph, "$label: Peilung und Graph")
        assertNoOverlap(altitude, graph, "$label: Hoehe und Graph")
        assertNoOverlap(graph, grade, "$label: Graph und Steigung")
        assertNoOverlap(rose, headingBox, "$label: Windrose und Peilung")
        assertFalse("$label: der Peilungstext laeuft ueber (Feld ${heading.fieldDp} dp, Text ${heading.textDp} dp)", heading.overflows)
        assertTrue(
            "$label: der Peilungstext ist mit dem Band kleiner als ohne: Text ${heading.textDp} dp gegen ${headingWithoutBanner.textDp} dp ohne Band (Feld ${heading.fieldDp} gegen ${headingWithoutBanner.fieldDp})",
            heading.textDp >= headingWithoutBanner.textDp - 0.5f,
        )
    }

    @Test
    fun inPortrait_theTerrainTileKeepsEveryFieldUnderTheBanner() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        checkTerrainTileUnderTheBanner("Hochformat")
    }

    private companion object {
        /**
         * Die Mindesthoehe einer Kachel: CLAUDE.md, „Layout-Patterns“ — „Höhe: mindestens 120 dp“. Im Querformat sind die 200 dp nur eine Obergrenze (die Reihe nimmt die verfuegbare Hoehe, wie bei der
         * Tankzeile), aber nie weniger als dieses Minimum: darunter faellt Label, Verlauf und Wert zusammen. Jeder Fall in dieser Klasse haelt diese Grenze.
         */
        const val MIN_TILE_DP = 120f
    }
}
