package de.adv990de.obd2.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Die Getriebe-Einstellungen (Radumfang, Primaer- und Sekundaeruebersetzung, sechs Gaenge) in der ECHTEN [AppPreferences] gegen einen Temp-DataStore. Die Schluesselnamen stehen als Literale:
 * sie sind persistierter Zustand, ein umbenannter Schluessel wuerde die Getriebedaten bei jedem Nutzer still auf die Standardwerte zuruecksetzen. Das Verhalten ueber die oeffentlichen Fluesse
 * deckt `AppPreferencesTest` ab; hier steht, UNTER welchem Namen es liegt, in beide Richtungen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GearPreferencesTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private var stores = 0

    // Jeder Aufruf bekommt eine eigene Datei: ein Test darf mehrere unabhaengige Speicher brauchen (eine Schleife ueber die Gaenge).
    private fun makePreferencesWithDataStore(): Pair<AppPreferences, DataStore<Preferences>> {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher()),
            produceFile = { tempFolder.newFile("gear-${stores++}.preferences_pb") },
        )
        return AppPreferences(dataStore, mockk(relaxed = true)) to dataStore
    }

    private suspend fun DataStore<Preferences>.raw(name: String): Float? = data.first()[floatPreferencesKey(name)]

    // --- Lesen unter dem festen Namen ---

    @Test
    fun `the wheel circumference is read from wheel_circumference_mm`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()
        store.edit { it[floatPreferencesKey("wheel_circumference_mm")] = 2050f }

        assertEquals(2050f, prefs.wheelCircumferenceMm.first(), 0f)
    }

    @Test
    fun `the primary and the secondary ratio are read from primary_ratio and secondary_ratio`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()
        store.edit {
            it[floatPreferencesKey("primary_ratio")] = 1.8f
            it[floatPreferencesKey("secondary_ratio")] = 2.7f
        }

        assertEquals(1.8f, prefs.primaryRatio.first(), 0f)
        assertEquals(2.7f, prefs.secondaryRatio.first(), 0f)
    }

    @Test
    fun `each gear is read from its own key gear_ratio_1 to gear_ratio_6 and the others keep their default`() = runTest {
        for (gear in 1..6) {
            val (prefs, store) = makePreferencesWithDataStore()
            store.edit { it[floatPreferencesKey("gear_ratio_$gear")] = 9.5f }

            val expected = AppPreferences.DEFAULT_GEAR_RATIOS.toMutableList().also { it[gear - 1] = 9.5f }
            assertEquals("gear $gear", expected, prefs.gearRatios.first())
        }
    }

    // --- Schreiben unter dem festen Namen ---

    @Test
    fun `the setters write under the fixed names and under no other`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()

        prefs.setWheelCircumferenceMm(2060f)
        prefs.setPrimaryRatio(1.91f)
        prefs.setSecondaryRatio(2.62f)
        for (index in 0..5) prefs.setGearRatio(index, 3f + index)

        assertEquals(2060f, store.raw("wheel_circumference_mm")!!, 0f)
        assertEquals(1.91f, store.raw("primary_ratio")!!, 0f)
        assertEquals(2.62f, store.raw("secondary_ratio")!!, 0f)
        for (gear in 1..6) assertEquals("gear $gear", 3f + (gear - 1), store.raw("gear_ratio_$gear")!!, 0f)
        assertEquals("nothing else is written", 9, store.data.first().asMap().size)
    }

    @Test
    fun `a gear setter changes only its own key`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()

        prefs.setGearRatio(2, 1.5f)

        assertEquals(1.5f, store.raw("gear_ratio_3")!!, 0f)
        assertNull(store.raw("gear_ratio_2"))
        assertNull(store.raw("gear_ratio_4"))
        assertNull(store.raw("wheel_circumference_mm"))
    }

    // --- Standardwerte und Grenzen ---

    @Test
    fun `nothing stored reads as the documented defaults`() = runTest {
        val (prefs, _) = makePreferencesWithDataStore()

        assertEquals(2096f, prefs.wheelCircumferenceMm.first(), 0f)
        assertEquals(1.9143f, prefs.primaryRatio.first(), 0f)
        assertEquals(2.625f, prefs.secondaryRatio.first(), 0f)
        assertEquals(listOf(2.9167f, 2.1333f, 1.6667f, 1.35f, 1.125f, 1.04f), prefs.gearRatios.first())
    }

    @Test
    fun `the gear setter refuses an index outside 0 to 5 and writes nothing`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()

        for (bad in listOf(-1, 6, 100)) {
            val failure = runCatching { prefs.setGearRatio(bad, 1f) }.exceptionOrNull()
            assertTrue("$bad muss abgelehnt werden, war $failure", failure is IllegalArgumentException)
        }

        assertTrue(store.data.first().asMap().isEmpty())
    }
}
