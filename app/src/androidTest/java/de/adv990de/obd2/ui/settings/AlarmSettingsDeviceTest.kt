package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.alarm.AlarmSettings
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.theme.AppTypography
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Die Einstellungsgruppe "Alarme" auf der Einstellungsseite: Lage zwischen den Nachbargruppen, drei Schalter (Standard an), Segmentzeilen nur bei eingeschaltetem
 * Alarm, Schreiben in den DataStore, Kühlmittel immer in °C, Mindesthöhen (Zeilen 64 dp, Segmente 56 dp, Knopf des Hinweises 56 dp) und die Hinweiszeile.
 *
 * Die Seite läuft mit den echten ViewModels und dem Test-DataStore (`TestAppModule`: ein eigener Speicher je Test, alles beginnt bei den Standardwerten). Die
 * Hinweiszeile unter echten Systembedingungen lässt sich nicht erzwingen — die Berechtigung zur Laufzeit zu entziehen beendet den Testprozess: ihre Entscheidung
 * prüft `AlarmSettingsLogicTest`, hier steht die Zeile mit festen Eingaben (zustandslose `alarmSection`) und die Seite folgt dem echten Zustand des Geräts.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AlarmSettingsDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Inject
    lateinit var appPreferences: AppPreferences

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private fun launchSettingsScreen() {
        composeRule.setContent { SettingsScreen(navController = rememberNavController()) }
        composeRule.waitForIdle()
    }

    private fun str(id: Int) = composeRule.activity.getString(id)

    private val locale get() = composeRule.activity.resources.configuration.locales[0]

    private fun volt(deciV: Int) = formatVoltageOption(deciV, locale)

    private fun list() = composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG)

    /**
     * Bringt die Zeile in die MITTE der Liste. `performScrollToNode` lässt eine Zeile je nach Lage der Liste am oberen ODER am unteren Rand stehen (hier am unteren: ein
     * fester Rückweg wie in `SettingsScreenDeviceTest.scrollToClearView` schöbe sie aus dem Bild), und die Liste reicht unter die Kopfleiste. In der Mitte ist die
     * Zeile ganz sichtbar und ihre Nachbarn darüber und darunter sind komponiert — im Quer- wie im Hochformat.
     */
    private fun scrollToClearView(matcher: SemanticsMatcher) {
        list().performScrollToNode(matcher)
        composeRule.waitForIdle()
        val row = composeRule.onNode(matcher).fetchSemanticsNode().boundsInRoot
        val viewport = list().fetchSemanticsNode().boundsInRoot
        list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, row.center.y - viewport.center.y) }
        composeRule.waitForIdle()
    }

    private fun scrollToClearView(testTag: String) = scrollToClearView(hasTestTag(testTag))

    /** Gibt es die Zeile in der Liste? `performScrollToNode` wirft, wenn keine Zeile passt — außerhalb des Bildes ist eine Zeile nicht komponiert, also reicht `assertExists` nicht. */
    private fun rowExists(testTag: String): Boolean = runCatching { list().performScrollToNode(hasTestTag(testTag)) }.isSuccess

    private fun groupHeader(textId: Int) = hasText(str(textId).uppercase())

    private fun segment(groupTag: String, label: String) =
        composeRule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(groupTag)))

    private fun storedSettings(): AlarmSettings = runBlocking { appPreferences.alarmSettings.first() }

    private fun waitUntilStored(expected: (AlarmSettings) -> Boolean) =
        composeRule.waitUntil(timeoutMillis = 5_000) { expected(storedSettings()) }

    private val switchTags = listOf(ALARM_VOLTAGE_SWITCH_TEST_TAG, ALARM_COOLANT_SWITCH_TEST_TAG, ALARM_FAULTS_SWITCH_TEST_TAG)
    private val choiceTags = listOf(
        ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG, ALARM_VOLTAGE_WARNING_CHOICE_TEST_TAG,
        ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG, ALARM_COOLANT_WARNING_CHOICE_TEST_TAG,
    )

    // ── Lage auf der Seite ──────────────────────────────────────────────────────────────────

    @Test
    fun theAlarmGroup_sitsBetweenDisplayAndRecording() {
        launchSettingsScreen()

        // Davor: die letzte Zeile von "Dashboard & Anzeige" (der Getriebe-Eintrag) steht über der Überschrift "Alarme".
        scrollToClearView(groupHeader(R.string.settings_alarms_section))
        val gearRatios = composeRule.onNode(hasText(str(R.string.settings_gear_ratios))).fetchSemanticsNode().positionInRoot.y
        val alarmHeader = composeRule.onNode(groupHeader(R.string.settings_alarms_section)).fetchSemanticsNode().positionInRoot.y
        assertTrue("Getriebe ($gearRatios) steht nicht über der Überschrift Alarme ($alarmHeader)", gearRatios < alarmHeader)

        // Dahinter: "Aufzeichnung" beginnt erst nach der letzten Alarm-Zeile (dem dritten Schalter oder der Hinweiszeile).
        scrollToClearView(groupHeader(R.string.settings_recording_section))
        val recordingHeader = composeRule.onNode(groupHeader(R.string.settings_recording_section)).fetchSemanticsNode().positionInRoot.y
        val lastAlarmSwitch = composeRule.onNodeWithTag(ALARM_FAULTS_SWITCH_TEST_TAG).fetchSemanticsNode().positionInRoot.y
        assertTrue("der Fehler-Schalter ($lastAlarmSwitch) steht nicht über der Überschrift Aufzeichnung ($recordingHeader)", lastAlarmSwitch < recordingHeader)
    }

    @Test
    fun theThreeSwitches_areOnByDefault() {
        launchSettingsScreen()
        for (tag in switchTags) {
            scrollToClearView(tag)
            composeRule.onNodeWithTag(tag).assertIsOn()
        }
    }

    @Test
    fun everyRowOfTheGroupCanBeScrolledIntoView() {
        launchSettingsScreen()
        for (tag in switchTags + choiceTags) {
            scrollToClearView(tag)
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    // ── Schalter und Segmentzeilen ──────────────────────────────────────────────────────────

    @Test
    fun theSegmentRowsOfAnAlarmAreShownOnlyWhileItsSwitchIsOn() {
        launchSettingsScreen()
        assertTrue("Spannung (an): Hinweis-Zeile fehlt", rowExists(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG))
        assertTrue("Spannung (an): Warn-Zeile fehlt", rowExists(ALARM_VOLTAGE_WARNING_CHOICE_TEST_TAG))
        assertTrue("Kühlmittel (an): Hinweis-Zeile fehlt", rowExists(ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG))
        assertTrue("Kühlmittel (an): Warn-Zeile fehlt", rowExists(ALARM_COOLANT_WARNING_CHOICE_TEST_TAG))

        scrollToClearView(ALARM_VOLTAGE_SWITCH_TEST_TAG)
        composeRule.onNodeWithTag(ALARM_VOLTAGE_SWITCH_TEST_TAG).performClick()
        waitUntilStored { !it.voltageEnabled }
        composeRule.waitForIdle()

        assertFalse("Spannung (aus): Hinweis-Zeile steht noch", rowExists(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG))
        assertFalse("Spannung (aus): Warn-Zeile steht noch", rowExists(ALARM_VOLTAGE_WARNING_CHOICE_TEST_TAG))
        // Die Zeilen des anderen Alarms bleiben.
        assertTrue("Kühlmittel-Hinweis-Zeile verschwand mit dem Spannungsalarm", rowExists(ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG))
        assertTrue("Kühlmittel-Warn-Zeile verschwand mit dem Spannungsalarm", rowExists(ALARM_COOLANT_WARNING_CHOICE_TEST_TAG))
        scrollToClearView(ALARM_VOLTAGE_SWITCH_TEST_TAG)
        composeRule.onNodeWithTag(ALARM_VOLTAGE_SWITCH_TEST_TAG).assertIsOff()

        // Wieder an: die Zeilen kommen zurück.
        composeRule.onNodeWithTag(ALARM_VOLTAGE_SWITCH_TEST_TAG).performClick()
        waitUntilStored { it.voltageEnabled }
        composeRule.waitForIdle()
        assertTrue(rowExists(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG))
        assertTrue(rowExists(ALARM_VOLTAGE_WARNING_CHOICE_TEST_TAG))
    }

    @Test
    fun turningTheCoolantSwitchOff_hidesOnlyTheCoolantRows() {
        launchSettingsScreen()
        scrollToClearView(ALARM_COOLANT_SWITCH_TEST_TAG)
        composeRule.onNodeWithTag(ALARM_COOLANT_SWITCH_TEST_TAG).performClick()
        waitUntilStored { !it.coolantEnabled }
        composeRule.waitForIdle()

        assertFalse(rowExists(ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG))
        assertFalse(rowExists(ALARM_COOLANT_WARNING_CHOICE_TEST_TAG))
        assertTrue(rowExists(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG))
        assertTrue(rowExists(ALARM_VOLTAGE_WARNING_CHOICE_TEST_TAG))
        assertEquals(AlarmSettings(coolantEnabled = false), storedSettings())
    }

    @Test
    fun theFaultSwitch_writesOnlyTheFaultSetting() {
        launchSettingsScreen()
        scrollToClearView(ALARM_FAULTS_SWITCH_TEST_TAG)
        composeRule.onNodeWithTag(ALARM_FAULTS_SWITCH_TEST_TAG).performClick()
        waitUntilStored { !it.faultsEnabled }

        assertEquals(AlarmSettings(faultsEnabled = false), storedSettings())
    }

    @Test
    fun theSegmentsStartOnTheDefaultsAndTheChosenOneIsMarked() {
        launchSettingsScreen()
        scrollToClearView(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG)
        segment(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG, volt(125)).assertIsSelected()
        segment(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG, volt(123)).assertIsNotSelected()
        scrollToClearView(ALARM_VOLTAGE_WARNING_CHOICE_TEST_TAG)
        segment(ALARM_VOLTAGE_WARNING_CHOICE_TEST_TAG, volt(122)).assertIsSelected()
        scrollToClearView(ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG)
        segment(ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG, "95").assertIsSelected()
        scrollToClearView(ALARM_COOLANT_WARNING_CHOICE_TEST_TAG)
        segment(ALARM_COOLANT_WARNING_CHOICE_TEST_TAG, "100").assertIsSelected()
    }

    @Test
    fun tappingASegmentWritesExactlyThatThresholdToTheDataStore() {
        launchSettingsScreen()

        scrollToClearView(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG)
        segment(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG, volt(128)).performClick()
        waitUntilStored { it.voltageNoticeDeciV == 128 }
        segment(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG, volt(128)).assertIsSelected()

        scrollToClearView(ALARM_VOLTAGE_WARNING_CHOICE_TEST_TAG)
        segment(ALARM_VOLTAGE_WARNING_CHOICE_TEST_TAG, volt(118)).performClick()
        waitUntilStored { it.voltageWarningDeciV == 118 }

        scrollToClearView(ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG)
        segment(ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG, "92").performClick()
        waitUntilStored { it.coolantNoticeC == 92 }

        scrollToClearView(ALARM_COOLANT_WARNING_CHOICE_TEST_TAG)
        segment(ALARM_COOLANT_WARNING_CHOICE_TEST_TAG, "110").performClick()
        waitUntilStored { it.coolantWarningC == 110 }

        // Jeder Tipp änderte nur seine eigene Schwelle, die Schalter blieben an.
        assertEquals(
            AlarmSettings(voltageNoticeDeciV = 128, voltageWarningDeciV = 118, coolantNoticeC = 92, coolantWarningC = 110),
            storedSettings(),
        )
    }

    @Test
    fun theVoltageSegmentsAreWrittenWithOneDecimalInTheLanguageOfTheDevice() {
        launchSettingsScreen()
        scrollToClearView(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG)
        for (deciV in AlarmSettings.VOLTAGE_NOTICE_OPTIONS_DV) {
            val label = volt(deciV)
            assertTrue("$label hat keine Nachkommastelle", Regex("""\d+[.,]\d""").matches(label))
            segment(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG, label).assertIsDisplayed()
        }
    }

    @Test
    fun theCoolantSegmentsStayInCelsius_alsoWithImperialUnits() {
        runBlocking { appPreferences.setUseImperial(true) }
        launchSettingsScreen()

        scrollToClearView(ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG)
        for (celsius in AlarmSettings.COOLANT_NOTICE_OPTIONS_C) segment(ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG, celsius.toString()).assertIsDisplayed()
        // 95 °C wären 203 °F: eine Umrechnung wäre hier sofort sichtbar.
        composeRule.onNode(hasText("203") and hasAnyAncestor(hasTestTag(ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG))).assertDoesNotExist()
        scrollToClearView(ALARM_COOLANT_WARNING_CHOICE_TEST_TAG)
        for (celsius in AlarmSettings.COOLANT_WARNING_OPTIONS_C) segment(ALARM_COOLANT_WARNING_CHOICE_TEST_TAG, celsius.toString()).assertIsDisplayed()
    }

    // ── Mindesthöhen ────────────────────────────────────────────────────────────────────────

    @Test
    fun everySwitchRowAndEverySegmentRowIsAtLeast64dpHigh() {
        launchSettingsScreen()
        for (tag in switchTags + choiceTags) {
            scrollToClearView(tag)
            composeRule.onNodeWithTag(tag).assertHeightIsAtLeast(64.dp)
        }
    }

    @Test
    fun everySegmentIsAtLeast56dpHigh() {
        launchSettingsScreen()
        scrollToClearView(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG)
        for (deciV in AlarmSettings.VOLTAGE_NOTICE_OPTIONS_DV) segment(ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG, volt(deciV)).assertHeightIsAtLeast(56.dp)
        scrollToClearView(ALARM_COOLANT_WARNING_CHOICE_TEST_TAG)
        for (celsius in AlarmSettings.COOLANT_WARNING_OPTIONS_C) segment(ALARM_COOLANT_WARNING_CHOICE_TEST_TAG, celsius.toString()).assertHeightIsAtLeast(56.dp)
    }

    // ── Hinweiszeile ────────────────────────────────────────────────────────────────────────

    /** Die zustandslose Gruppe mit festen Eingaben: [settings] und ob Benachrichtigungen erlaubt sind (die Entscheidung geht durch dieselbe Funktion wie die Seite). */
    private fun launchSection(settings: AlarmSettings, notificationsEnabled: Boolean, onOpenAppSettings: () -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme(typography = AppTypography) {
                LazyColumn(Modifier.testTag(SETTINGS_LIST_TEST_TAG)) {
                    alarmSection(
                        settings = settings,
                        showNotificationHint = alarmNotificationHint(settings, notificationsEnabled),
                        onOpenAppSettings = onOpenAppSettings,
                        onVoltageEnabledChange = {}, onCoolantEnabledChange = {}, onFaultsEnabledChange = {},
                        onVoltageNoticeChange = {}, onVoltageWarningChange = {}, onCoolantNoticeChange = {}, onCoolantWarningChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun blockedNotifications_showTheHintWithAButtonToTheAppSettings() {
        var opened = 0
        launchSection(AlarmSettings(), notificationsEnabled = false, onOpenAppSettings = { opened++ })

        assertTrue(rowExists(ALARM_NOTIFICATION_HINT_TEST_TAG))
        composeRule.onNodeWithTag(ALARM_NOTIFICATION_HINT_TEST_TAG).assertIsDisplayed()
        composeRule.onNode(hasText(str(R.string.settings_alarm_notifications_hint))).assertIsDisplayed()
        composeRule.onNode(hasText(str(R.string.settings_permission_open_settings))).assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun enabledNotifications_showNoHint() {
        launchSection(AlarmSettings(), notificationsEnabled = true)
        assertFalse(rowExists(ALARM_NOTIFICATION_HINT_TEST_TAG))
    }

    @Test
    fun withEveryAlarmOff_blockedNotificationsShowNoHint() {
        launchSection(AlarmSettings(voltageEnabled = false, coolantEnabled = false, faultsEnabled = false), notificationsEnabled = false)
        assertFalse(rowExists(ALARM_NOTIFICATION_HINT_TEST_TAG))
    }

    @Test
    fun theHintStandsAtTheEndOfTheGroup_underTheLastSwitch() {
        launchSection(AlarmSettings(), notificationsEnabled = false)
        scrollToClearView(ALARM_NOTIFICATION_HINT_TEST_TAG)
        val faults = composeRule.onNodeWithTag(ALARM_FAULTS_SWITCH_TEST_TAG).fetchSemanticsNode().positionInRoot.y
        val hint = composeRule.onNodeWithTag(ALARM_NOTIFICATION_HINT_TEST_TAG).fetchSemanticsNode().positionInRoot.y
        assertTrue("der Hinweis ($hint) steht nicht unter dem Fehler-Schalter ($faults)", faults < hint)
    }

    @Test
    fun theHintOnThePageFollowsTheRealNotificationStateOfTheDevice() {
        launchSettingsScreen()
        val enabled = NotificationManagerCompat.from(composeRule.activity).areNotificationsEnabled()

        // Alle drei Alarme sind an (Standard): der Hinweis steht genau dann da, wenn das Gerät Benachrichtigungen sperrt.
        assertEquals("Hinweis passt nicht zum Zustand des Geräts (Benachrichtigungen erlaubt: $enabled)", !enabled, rowExists(ALARM_NOTIFICATION_HINT_TEST_TAG))
    }
}
