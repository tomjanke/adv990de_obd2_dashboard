// app/src/test/java/de/adv990de/obd2/data/AlarmPreferencesTest.kt
package de.adv990de.obd2.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import de.adv990de.obd2.alarm.AlarmSettings
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Die Alarm-Einstellungen in der ECHTEN [AppPreferences] gegen einen Temp-DataStore (gleicher Aufbau wie `AppPreferencesTest`). Die
 * Schluesselnamen stehen hier als Literale: sie sind persistierter Zustand, ein umbenannter Schluessel wuerde die Einstellung bei
 * jedem Nutzer still auf den Standard zuruecksetzen — dieser Test ist die Wache dagegen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmPreferencesTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val voltageEnabledKey = booleanPreferencesKey("alarm_voltage_enabled")
    private val coolantEnabledKey = booleanPreferencesKey("alarm_coolant_enabled")
    private val faultsEnabledKey = booleanPreferencesKey("alarm_faults_enabled")
    private val voltageNoticeKey = intPreferencesKey("alarm_voltage_notice_dv")
    private val voltageWarningKey = intPreferencesKey("alarm_voltage_warning_dv")
    private val coolantNoticeKey = intPreferencesKey("alarm_coolant_notice_c")
    private val coolantWarningKey = intPreferencesKey("alarm_coolant_warning_c")

    private fun makePreferencesWithDataStore(): Pair<AppPreferences, DataStore<Preferences>> {
        // UnconfinedTestDispatcher: s. AppPreferencesTest — der Speicher laeuft auf einem eigenen Test-Scope.
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher()),
            produceFile = { tempFolder.newFile("alarm.preferences_pb") },
        )
        return AppPreferences(dataStore, mockk(relaxed = true)) to dataStore
    }

    private fun makePreferences(): AppPreferences = makePreferencesWithDataStore().first

    // ── Standardwerte ─────────────────────────────────────────────────────────

    @Test
    fun `alarmSettings is AlarmSettings() when nothing is stored`() = runTest {
        val settings = makePreferences().alarmSettings.first()

        assertEquals(AlarmSettings(), settings)
        // Die Zahlen des Auftrags ausgeschrieben: alle drei Schalter an, 12,5 V / 12,2 V, 95 °C / 100 °C.
        assertEquals(AlarmSettings(true, true, true, 125, 122, 95, 100), settings)
    }

    // ── Setter: je Feld, die uebrigen bleiben unveraendert ────────────────────

    @Test
    fun `setAlarmVoltageEnabled is read back and leaves the other fields`() = runTest {
        val prefs = makePreferences()
        prefs.setAlarmVoltageEnabled(false)
        assertEquals(AlarmSettings().copy(voltageEnabled = false), prefs.alarmSettings.first())
    }

    @Test
    fun `setAlarmCoolantEnabled is read back and leaves the other fields`() = runTest {
        val prefs = makePreferences()
        prefs.setAlarmCoolantEnabled(false)
        assertEquals(AlarmSettings().copy(coolantEnabled = false), prefs.alarmSettings.first())
    }

    @Test
    fun `setAlarmFaultsEnabled is read back and leaves the other fields`() = runTest {
        val prefs = makePreferences()
        prefs.setAlarmFaultsEnabled(false)
        assertEquals(AlarmSettings().copy(faultsEnabled = false), prefs.alarmSettings.first())
    }

    @Test
    fun `setAlarmVoltageNoticeDeciV is read back and leaves the other fields`() = runTest {
        val prefs = makePreferences()
        prefs.setAlarmVoltageNoticeDeciV(128)
        assertEquals(AlarmSettings().copy(voltageNoticeDeciV = 128), prefs.alarmSettings.first())
    }

    @Test
    fun `setAlarmVoltageWarningDeciV is read back and leaves the other fields`() = runTest {
        val prefs = makePreferences()
        prefs.setAlarmVoltageWarningDeciV(118)
        assertEquals(AlarmSettings().copy(voltageWarningDeciV = 118), prefs.alarmSettings.first())
    }

    @Test
    fun `setAlarmCoolantNoticeC is read back and leaves the other fields`() = runTest {
        val prefs = makePreferences()
        prefs.setAlarmCoolantNoticeC(92)
        assertEquals(AlarmSettings().copy(coolantNoticeC = 92), prefs.alarmSettings.first())
    }

    @Test
    fun `setAlarmCoolantWarningC is read back and leaves the other fields`() = runTest {
        val prefs = makePreferences()
        prefs.setAlarmCoolantWarningC(110)
        assertEquals(AlarmSettings().copy(coolantWarningC = 110), prefs.alarmSettings.first())
    }

    @Test
    fun `a later setter does not undo an earlier one`() = runTest {
        val prefs = makePreferences()
        prefs.setAlarmVoltageEnabled(false)
        prefs.setAlarmCoolantNoticeC(98)
        prefs.setAlarmVoltageWarningDeciV(120)

        assertEquals(
            AlarmSettings().copy(voltageEnabled = false, coolantNoticeC = 98, voltageWarningDeciV = 120),
            prefs.alarmSettings.first(),
        )
    }

    // ── snapToOption beim Lesen ───────────────────────────────────────────────
    // Regel (`snapToOption`): die naechste Option; bei exaktem Gleichstand die GROESSERE.

    @Test
    fun `a stored voltage notice off the list snaps to the nearest option`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()

        // Optionen 123/125/128: 126 liegt 1 von 125 und 2 von 128 entfernt -> 125; 127 liegt 2 von 125 und 1 von 128 entfernt -> 128.
        dataStore.edit { it[voltageNoticeKey] = 126 }
        assertEquals(125, prefs.alarmSettings.first().voltageNoticeDeciV)
        dataStore.edit { it[voltageNoticeKey] = 127 }
        assertEquals(128, prefs.alarmSettings.first().voltageNoticeDeciV)
        // 124 liegt je 1 von 123 und 125 entfernt: Gleichstand -> die groessere, 125.
        dataStore.edit { it[voltageNoticeKey] = 124 }
        assertEquals(125, prefs.alarmSettings.first().voltageNoticeDeciV)
        // Weit ausserhalb: die naechste Randoption.
        dataStore.edit { it[voltageNoticeKey] = 0 }
        assertEquals(123, prefs.alarmSettings.first().voltageNoticeDeciV)
        dataStore.edit { it[voltageNoticeKey] = 500 }
        assertEquals(128, prefs.alarmSettings.first().voltageNoticeDeciV)
    }

    @Test
    fun `a stored voltage warning off the list snaps to the nearest option`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()

        // Optionen 118/120/122: 121 liegt je 1 von 120 und 122 entfernt: Gleichstand -> 122; 119 je 1 von 118 und 120: Gleichstand -> 120.
        dataStore.edit { it[voltageWarningKey] = 121 }
        assertEquals(122, prefs.alarmSettings.first().voltageWarningDeciV)
        dataStore.edit { it[voltageWarningKey] = 119 }
        assertEquals(120, prefs.alarmSettings.first().voltageWarningDeciV)
        dataStore.edit { it[voltageWarningKey] = 100 }
        assertEquals(118, prefs.alarmSettings.first().voltageWarningDeciV)
    }

    @Test
    fun `a stored coolant notice off the list snaps to the nearest option`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()

        // Optionen 92/95/98: 97 liegt 1 von 98 und 2 von 95 entfernt -> 98; 90 liegt 2 von 92 entfernt -> 92; 94 liegt 1 von 95 -> 95.
        dataStore.edit { it[coolantNoticeKey] = 97 }
        assertEquals(98, prefs.alarmSettings.first().coolantNoticeC)
        dataStore.edit { it[coolantNoticeKey] = 90 }
        assertEquals(92, prefs.alarmSettings.first().coolantNoticeC)
        dataStore.edit { it[coolantNoticeKey] = 94 }
        assertEquals(95, prefs.alarmSettings.first().coolantNoticeC)
    }

    @Test
    fun `a stored coolant warning off the list snaps to the nearest option`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()

        // Optionen 100/105/110: 103 liegt 2 von 105 und 3 von 100 entfernt -> 105; 107 liegt 2 von 105 und 3 von 110 -> 105; 200 -> 110.
        dataStore.edit { it[coolantWarningKey] = 103 }
        assertEquals(105, prefs.alarmSettings.first().coolantWarningC)
        dataStore.edit { it[coolantWarningKey] = 107 }
        assertEquals(105, prefs.alarmSettings.first().coolantWarningC)
        dataStore.edit { it[coolantWarningKey] = 200 }
        assertEquals(110, prefs.alarmSettings.first().coolantWarningC)
    }

    @Test
    fun `snapping happens at read time and writes nothing back`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()
        dataStore.edit { it[voltageNoticeKey] = 126 }

        assertEquals(125, prefs.alarmSettings.first().voltageNoticeDeciV)
        assertEquals("der gespeicherte Wert bleibt, wie er war", 126, dataStore.data.first()[voltageNoticeKey])
    }

    // ── distinctUntilChanged ──────────────────────────────────────────────────

    @Test
    fun `alarmSettings does not emit again for an equal write or an unrelated key`() = runTest {
        // Der Alarm-Tracker haengt an diesem Fluss; jeder DataStore-Schreibvorgang (Theme, Einheiten, ...) wuerde ihn sonst mit
        // unveraenderten Werten erneut anstossen (wie bei useGpsSpeed, DAT-13).
        val prefs = makePreferences()
        val emissions = mutableListOf<AlarmSettings>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { prefs.alarmSettings.toList(emissions) }

        prefs.setUseImperial(true)
        prefs.setTheme(AppPreferences.THEME_BLUE)
        assertEquals("fremde Schluessel loesen nichts aus", listOf(AlarmSettings()), emissions)

        prefs.setAlarmFaultsEnabled(false)
        val changed = AlarmSettings().copy(faultsEnabled = false)
        assertEquals("eine echte Aenderung kommt an", listOf(AlarmSettings(), changed), emissions)

        prefs.setAlarmFaultsEnabled(false)
        assertEquals("dasselbe noch einmal schreiben ist keine Aenderung", listOf(AlarmSettings(), changed), emissions)
        job.cancel()
    }

    // ── Schluesselnamen (persistierter Zustand) ───────────────────────────────

    @Test
    fun `the persisted key names are exactly the documented ones (read side)`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()
        // Jedes Feld bekommt einen Wert ungleich dem Standard: ein umbenannter Schluessel liesse genau dieses Feld auf dem Standard stehen.
        dataStore.edit {
            it[booleanPreferencesKey("alarm_voltage_enabled")] = false
            it[booleanPreferencesKey("alarm_coolant_enabled")] = false
            it[booleanPreferencesKey("alarm_faults_enabled")] = false
            it[intPreferencesKey("alarm_voltage_notice_dv")] = 128
            it[intPreferencesKey("alarm_voltage_warning_dv")] = 118
            it[intPreferencesKey("alarm_coolant_notice_c")] = 98
            it[intPreferencesKey("alarm_coolant_warning_c")] = 110
        }

        assertEquals(
            AlarmSettings(
                voltageEnabled = false,
                coolantEnabled = false,
                faultsEnabled = false,
                voltageNoticeDeciV = 128,
                voltageWarningDeciV = 118,
                coolantNoticeC = 98,
                coolantWarningC = 110,
            ),
            prefs.alarmSettings.first(),
        )
    }

    @Test
    fun `the setters write exactly the documented key names (write side)`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()

        prefs.setAlarmVoltageEnabled(false)
        prefs.setAlarmCoolantEnabled(false)
        prefs.setAlarmFaultsEnabled(false)
        prefs.setAlarmVoltageNoticeDeciV(128)
        prefs.setAlarmVoltageWarningDeciV(118)
        prefs.setAlarmCoolantNoticeC(98)
        prefs.setAlarmCoolantWarningC(110)

        val stored = dataStore.data.first().asMap().mapKeys { it.key.name }
        assertEquals(
            mapOf<String, Any>(
                "alarm_voltage_enabled" to false,
                "alarm_coolant_enabled" to false,
                "alarm_faults_enabled" to false,
                "alarm_voltage_notice_dv" to 128,
                "alarm_voltage_warning_dv" to 118,
                "alarm_coolant_notice_c" to 98,
                "alarm_coolant_warning_c" to 110,
            ),
            stored,
        )
    }
}
