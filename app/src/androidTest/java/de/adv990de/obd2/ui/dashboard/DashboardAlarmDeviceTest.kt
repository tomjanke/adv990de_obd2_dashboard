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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import de.adv990de.obd2.alarm.AlarmKind
import de.adv990de.obd2.alarm.AlarmLog
import de.adv990de.obd2.alarm.AlarmNotifier
import de.adv990de.obd2.alarm.AlarmSeverity
import de.adv990de.obd2.alarm.alarmMessage
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.di.AlarmModule
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.components.FUEL_GAUGE_ROW_TEST_TAG
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Das Alarmband im **echten** Dashboard (Hilt, echtes `DashboardViewModel` mit gespielten `livePids`): ein Alarm erscheint als Band, der Tipp quittiert, zwei Alarme zeigen den wichtigeren mit
 * „+1“, und die Kachelreihe schrumpft unter dem Band nicht unter eine sichtbare Kachel — im Quer- UND im Hochformat. Der Alarm entsteht aus Messwerten (Kuehlmittel ab 100 °C ueber 5 s, Spannung
 * unter 12,2 V ueber 10 s nach 15 s Einschwingzeit), nicht aus einem eingespielten `Alarm`: der ganze Weg Pipeline → ViewModel → Controller → Band laeuft.
 *
 * **Die Zeit gehoert dem Test:** die Dauern der Regeln (5 s, 10 s, 15 s) laufen auf der Uhr des ViewModels (`nowMillis`, schon heute eine Naht fuer die JVM-Tests). Der Test setzt sie je Zyklus von
 * Hand, statt Sekunden zu warten — und wartet, bis das ViewModel den Zyklus verarbeitet hat, bevor er die Uhr weiterstellt (sonst sähe dieser Zyklus schon die neue Zeit). Die Benachrichtigung ist eine
 * Attrappe (`@UninstallModules(AlarmModule)`, wie `DashboardTerrainSlotTest` fuer `TerrainModule`): sie zeichnet `show`/`clear` auf, damit der Test sieht, was das Quittieren an sie meldet.
 */
@HiltAndroidTest
@UninstallModules(AlarmModule::class)
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

    @Inject lateinit var appPreferences: AppPreferences

    /** Die Zyklen, die der Test dem Dashboard einspielt (Sitzung laeuft, sobald jemand sammelt). */
    private val cycles = MutableSharedFlow<List<SensorValue>>(extraBufferCapacity = 16)
    @Volatile private var started = 0

    private val clock = AtomicLong(1_000)
    private var marker = 0
    private lateinit var vm: DashboardViewModel

    private val notifier get() = alarmNotifier as RecordingNotifier

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
        every { obdRepository.livePids(any()) } returns cycles.onStart { started++ }
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
     */
    private fun launch() {
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
        composeRule.waitUntil(timeoutMillis = 5_000) { started > 0 }
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

    // --- Platz: die Kachelreihe schrumpft, bleibt aber sichtbar ---

    private fun coolantTile() = composeRule.onNode(
        hasContentDescription(composeRule.activity.getString(R.string.pid_coolant_temp) + ": ", substring = true),
    )

    private fun heightDp(top: Float, bottom: Float) = with(Density(composeRule.activity)) { (bottom - top).toDp() }.value

    /**
     * Misst die Kachelreihe ohne und mit Band (und mit Tankzeile): die Kachel muss mit dem Band sichtbar und nicht zusammengedrueckt bleiben; Band, Kachel und Tankzeile stehen in dieser
     * Reihenfolge uebereinander. Die gemessenen Hoehen gehen ins Log (Tag `DashboardAlarmHeights`) — sie stehen im Bericht, nicht nur in einer Schwelle.
     */
    private fun checkTilesStayVisibleUnderTheBanner(label: String) {
        runBlocking {
            appPreferences.setFuelRangeFullKm(200)
            appPreferences.setFuelKmSinceFill(40f)
        }
        launch()
        cycle(atMs = 1_000, coolantC = 105f)
        coolantTile().assertIsDisplayed()
        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).assertIsDisplayed()
        val before = coolantTile().fetchSemanticsNode().boundsInRoot
        val heightBefore = heightDp(before.top, before.bottom)

        cycle(atMs = 7_000, coolantC = 105f)
        waitForBanner()

        val banner = composeRule.onNodeWithTag(ALARM_BANNER_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val tile = coolantTile().fetchSemanticsNode().boundsInRoot
        val fuel = composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val heightWith = heightDp(tile.top, tile.bottom)
        val bannerDp = heightDp(banner.top, banner.bottom)
        Log.i("DashboardAlarmHeights", "$label: Kachel ohne Band %.1f dp, mit Band %.1f dp, Band %.1f dp, Tankzeile %.1f dp"
            .format(heightBefore, heightWith, bannerDp, heightDp(fuel.top, fuel.bottom)))

        coolantTile().assertIsDisplayed()
        composeRule.onNodeWithTag(ALARM_BANNER_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(FUEL_GAUGE_ROW_TEST_TAG).assertIsDisplayed()
        assertTrue("$label: Band ($banner) liegt ueber der Kachel ($tile)", banner.bottom <= tile.top + 1f)
        assertTrue("$label: Kachel ($tile) liegt ueber der Tankzeile ($fuel)", tile.bottom <= fuel.top + 1f)
        assertTrue("$label: das Band ist mindestens 56 dp hoch, war $bannerDp", bannerDp >= 55.5f)
        assertTrue("$label: die Kachel bleibt mit dem Band mindestens ${MIN_VISIBLE_TILE_DP.toInt()} dp hoch, war $heightWith (ohne Band $heightBefore)", heightWith >= MIN_VISIBLE_TILE_DP)
        assertTrue("$label: das Band nimmt der Kachel hoechstens seine eigene Hoehe: $heightBefore -> $heightWith", heightBefore - heightWith <= bannerDp + 1f)
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

    private companion object {
        /**
         * Die kleinste Kachelhoehe, die als „sichtbar“ gilt: Label, Verlauf und Wert brauchen zusammen etwa so viel (CLAUDE.md nennt 120 dp als Mindesthoehe der Kachel; im Querformat gilt wie bei der
         * Tankzeile, dass die 200 dp nur eine Obergrenze sind). Eine Kachel unter diesem Wert waere zusammengedrueckt.
         */
        const val MIN_VISIBLE_TILE_DP = 100f
    }
}
