package de.adv990de.obd2.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Die Einstellungen der Tankanzeige in der ECHTEN [AppPreferences] gegen einen Temp-DataStore: UNTER WELCHEM NAMEN sie liegen, in beide Richtungen. Die Schluesselnamen stehen als Literale — sie sind
 * persistierter Zustand, ein umbenannter Schluessel wuerde Reichweite, gezaehlte Kilometer, Reserve und Tankinhalt bei jedem Nutzer still loeschen. Das Verhalten (Grenzen, Standardwerte, Ablehnen) steht
 * in `AppPreferencesTest`; dort lesen Tests rohe Werte nur, um „ausserhalb der Grenzen = nicht festgelegt“ zu pruefen — das gilt auch fuer einen umbenannten Schluessel und haelt den Namen nicht fest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelPreferencesTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun makePreferencesWithDataStore(): Pair<AppPreferences, DataStore<Preferences>> {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher()),
            produceFile = { tempFolder.newFile("fuel.preferences_pb") },
        )
        return AppPreferences(dataStore, mockk(relaxed = true)) to dataStore
    }

    @Test
    fun `the five settings are read from their fixed names`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()
        store.edit {
            it[intPreferencesKey("fuel_range_full_km")] = 300
            it[floatPreferencesKey("fuel_km_since_fill")] = 42.5f
            it[intPreferencesKey("fuel_reserve_km")] = 40
            it[intPreferencesKey("fuel_tank_capacity_dl")] = 180
            it[intPreferencesKey("fuel_range_learned_km")] = 275
        }

        assertEquals(300, prefs.fuelRangeFullKm.first())
        assertEquals(42.5f, prefs.fuelKmSinceFill.first(), 0f)
        assertEquals(40, prefs.fuelReserveKm.first())
        assertEquals(180, prefs.fuelTankCapacityDl.first())
        assertEquals(275, prefs.fuelRangeLearnedKm.first())
    }

    @Test
    fun `the setters write under the fixed names and under no other`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()

        prefs.setFuelRangeFullKm(310)
        prefs.setFuelKmSinceFill(12.5f)
        prefs.setFuelReserveKm(45)
        prefs.setFuelTankCapacityDl(190)
        prefs.setFuelRangeLearnedKm(280)

        val raw = store.data.first()
        assertEquals(310, raw[intPreferencesKey("fuel_range_full_km")])
        assertEquals(12.5f, raw[floatPreferencesKey("fuel_km_since_fill")])
        assertEquals(45, raw[intPreferencesKey("fuel_reserve_km")])
        assertEquals(190, raw[intPreferencesKey("fuel_tank_capacity_dl")])
        assertEquals(280, raw[intPreferencesKey("fuel_range_learned_km")])
        assertEquals("nothing else is written", 5, raw.asMap().size)
    }

    @Test
    fun `each setter changes only its own key`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()
        prefs.setFuelRangeFullKm(300)
        prefs.setFuelKmSinceFill(50f)

        prefs.setFuelReserveKm(40)

        val raw = store.data.first()
        assertEquals(300, raw[intPreferencesKey("fuel_range_full_km")])
        assertEquals(50f, raw[floatPreferencesKey("fuel_km_since_fill")])
        assertEquals(40, raw[intPreferencesKey("fuel_reserve_km")])
    }
}
