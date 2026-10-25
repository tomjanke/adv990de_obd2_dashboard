package de.adv990de.obd2.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import de.adv990de.obd2.logbook.GpsOdometer
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.MAINTENANCE_TYPES
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Die Wartungsintervalle und der GPS-Zaehler des Kilometerstands in der ECHTEN [AppPreferences] gegen einen Temp-DataStore. Die Schluesselnamen stehen als Literale: sie sind persistierter
 * Zustand, ein umbenannter Schluessel wuerde die Einstellung bei jedem Nutzer still loeschen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaintenancePreferencesTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun makePreferencesWithDataStore(): Pair<AppPreferences, DataStore<Preferences>> {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher()),
            produceFile = { tempFolder.newFile("maintenance.preferences_pb") },
        )
        return AppPreferences(dataStore, mockk(relaxed = true)) to dataStore
    }

    private fun makePreferences() = makePreferencesWithDataStore().first

    private fun intervalKey(name: String) = intPreferencesKey("maintenance_interval_${name}_km")

    // --- Intervalle ---

    @Test
    fun `nothing is set when nothing is stored`() = runTest {
        assertEquals(emptyMap<LogType, Int>(), makePreferences().maintenanceIntervals.first())
    }

    @Test
    fun `an interval is read back for its kind and leaves the others unset`() = runTest {
        val prefs = makePreferences()
        prefs.setMaintenanceInterval(LogType.CHAIN_SMALL, 1_000)
        prefs.setMaintenanceInterval(LogType.SERVICE_LARGE, 20_000)

        assertEquals(mapOf(LogType.CHAIN_SMALL to 1_000, LogType.SERVICE_LARGE to 20_000), prefs.maintenanceIntervals.first())
    }

    @Test
    fun `setting an interval again replaces it`() = runTest {
        val prefs = makePreferences()
        prefs.setMaintenanceInterval(LogType.TIRE_CHANGE, 15_000)
        prefs.setMaintenanceInterval(LogType.TIRE_CHANGE, 18_000)

        assertEquals(mapOf(LogType.TIRE_CHANGE to 18_000), prefs.maintenanceIntervals.first())
    }

    @Test
    fun `zero clears the interval`() = runTest {
        val prefs = makePreferences()
        prefs.setMaintenanceInterval(LogType.CHAIN_LARGE, 5_000)
        prefs.setMaintenanceInterval(LogType.CHAIN_LARGE, 0)

        assertEquals(emptyMap<LogType, Int>(), prefs.maintenanceIntervals.first())
    }

    @Test
    fun `the limits are inclusive, a value outside them is refused`() = runTest {
        val prefs = makePreferences()
        prefs.setMaintenanceInterval(LogType.CHAIN_SMALL, 50)
        prefs.setMaintenanceInterval(LogType.SERVICE_SMALL, 100_000)
        assertEquals(mapOf(LogType.CHAIN_SMALL to 50, LogType.SERVICE_SMALL to 100_000), prefs.maintenanceIntervals.first())

        for (bad in listOf(1, 49, 100_001, -1)) {
            try {
                prefs.setMaintenanceInterval(LogType.CHAIN_SMALL, bad)
                fail("$bad should be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(50, prefs.maintenanceIntervals.first()[LogType.CHAIN_SMALL])
    }

    @Test
    fun `only the six maintenance kinds have an interval`() = runTest {
        val prefs = makePreferences()
        for (type in listOf(LogType.FUEL, LogType.OIL, LogType.COOLANT)) {
            try {
                prefs.setMaintenanceInterval(type, 1_000)
                fail("$type should be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(emptyMap<LogType, Int>(), prefs.maintenanceIntervals.first())
    }

    @Test
    fun `a stored value outside the limits or for another kind reads as not set`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()
        dataStore.edit {
            it[intervalKey("chain_small")] = 10          // unter der Grenze
            it[intervalKey("chain_large")] = 1_000_000   // ueber der Grenze
            it[intervalKey("service_small")] = -5
            it[intervalKey("fuel")] = 500                // keine Wartungsart
            it[intervalKey("service_large")] = 12_000    // gueltig
        }

        assertEquals(mapOf(LogType.SERVICE_LARGE to 12_000), prefs.maintenanceIntervals.first())
    }

    @Test
    fun `the key names are the persisted ones`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()
        for ((i, type) in MAINTENANCE_TYPES.withIndex()) prefs.setMaintenanceInterval(type, 1_000 + i)

        val stored = dataStore.data.first()
        for ((i, name) in listOf("chain_small", "chain_large", "service_small", "service_large", "tire_change", "chain_kit_change").withIndex()) {
            assertEquals(name, 1_000 + i, stored[intervalKey(name)])
        }
    }

    // --- GPS-Zaehler ---

    private val anchorKey = intPreferencesKey("logbook_gps_anchor_km")
    private val gpsKey = floatPreferencesKey("logbook_gps_km")

    @Test
    fun `without a stored counter there is none`() = runTest {
        assertNull(makePreferences().gpsOdometer.first())
    }

    @Test
    fun `the counter is read back with its anchor`() = runTest {
        val prefs = makePreferences()
        prefs.setGpsOdometer(anchorKm = 40_000, gpsKm = 12.5f)

        assertEquals(GpsOdometer(40_000, 12.5f), prefs.gpsOdometer.first())
    }

    @Test
    fun `the counter key names are the persisted ones`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()
        prefs.setGpsOdometer(40_000, 12.5f)

        val stored = dataStore.data.first()
        assertEquals(40_000, stored[anchorKey])
        assertEquals(12.5f, stored[gpsKey])
    }

    @Test
    fun `a stored counter without an anchor is none, a negative or non-finite one reads as zero`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()
        dataStore.edit { it[gpsKey] = 7f }
        assertNull(prefs.gpsOdometer.first())

        dataStore.edit { it[anchorKey] = 40_000; it[gpsKey] = -3f }
        assertEquals(GpsOdometer(40_000, 0f), prefs.gpsOdometer.first())
        dataStore.edit { it[gpsKey] = Float.NaN }
        assertEquals(GpsOdometer(40_000, 0f), prefs.gpsOdometer.first())
    }

    @Test
    fun `an unusable counter is refused when writing`() = runTest {
        val prefs = makePreferences()
        for ((anchor, km) in listOf(-1 to 1f, 1_000_000 to 1f, 40_000 to -0.1f, 40_000 to Float.NaN, 40_000 to Float.POSITIVE_INFINITY)) {
            try {
                prefs.setGpsOdometer(anchor, km)
                fail("($anchor, $km) should be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertNull(prefs.gpsOdometer.first())
    }
}
