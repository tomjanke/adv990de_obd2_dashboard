package de.adv990de.obd2.data

import android.bluetooth.BluetoothAdapter
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import de.adv990de.obd2.logbook.MAINTENANCE_TYPES
import de.adv990de.obd2.transfer.SettingsCatalog
import de.adv990de.obd2.transfer.parseSettingsJson
import de.adv990de.obd2.transfer.sampleSettings
import de.adv990de.obd2.transfer.settingsToJson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Einstellungen lesen und schreiben ueber die oeffentlichen Setter: Runde hin und zurueck, Zurueckrollen bei einem Fehler. Mit **echtem** `AppPreferences`. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTransferTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private var files = 0

    private fun freshPrefs(): AppPreferences {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher()),
            produceFile = { tempFolder.newFile("t${files++}.preferences_pb") },
        )
        return AppPreferences(dataStore, mockk<BluetoothAdapter>(relaxed = true))
    }

    private suspend inline fun <reified T : Throwable> thrown(block: () -> Unit): T = try {
        block()
        throw AssertionError("expected ${T::class.simpleName}")
    } catch (e: Throwable) {
        if (e is T) e else throw e
    }

    private suspend fun roundTrip(from: AppPreferences, to: AppPreferences) {
        val parsed = parseSettingsJson(settingsToJson(SettingsTransfer(from).snapshot()))
        assertEquals(emptyList<Any>(), parsed.rejected.map { it.key })
        assertEquals(emptyList<String>(), parsed.unknownKeys)
        SettingsTransfer(to).apply(parsed.valid)
    }

    @Test
    fun `the bindings and the catalog have exactly the same keys in the same order`() {
        assertEquals(SettingsCatalog.exported.map { it.key }, SettingsTransfer.bindingKeys)
    }

    @Test
    fun `a fresh install reads as the defaults, every key present`() = runTest {
        val snap = SettingsTransfer(freshPrefs()).snapshot()
        assertEquals(SettingsCatalog.exported.map { it.key }, snap.keys.toList())
        assertEquals("system", snap["theme"])
        assertEquals(false, snap["use_imperial"])
        assertEquals(0, snap["rpm_blink_threshold"])
        assertEquals("010C,010D,0105,", snap["slot_pids"])
        assertEquals(20, snap["obd_watchdog_timeout_seconds"])
        assertEquals(3, snap["reconnect_base_delay_seconds"])
        assertEquals(true, snap["alarm_voltage_enabled"])
        assertEquals(125, snap["alarm_voltage_notice_dv"])
        assertEquals(0, snap["fuel_range_full_km"])
        for (t in MAINTENANCE_TYPES) assertEquals(0, snap["maintenance_interval_${t.name.lowercase()}_km"])
        assertEquals(2096f, snap["wheel_circumference_mm"])
        assertEquals(2.9167f, snap["gear_ratio_1"])
        assertEquals(1.04f, snap["gear_ratio_6"])
        // jeder Wert ist unter seiner Art gueltig
        for (spec in SettingsCatalog.exported) assertTrue(spec.key, SettingsCatalog.validate(spec, snap.getValue(spec.key)) is de.adv990de.obd2.transfer.SettingCheck.Valid)
    }

    @Test
    fun `all 36 settings survive the trip from one install to another`() = runTest {
        val source = freshPrefs()
        SettingsTransfer(source).apply(sampleSettings())
        assertEquals(sampleSettings(), SettingsTransfer(source).snapshot())
        val target = freshPrefs()
        roundTrip(source, target)
        assertEquals(sampleSettings(), SettingsTransfer(target).snapshot())
        // und die rohen Flows sagen dasselbe wie der Schnappschuss
        assertEquals("amber", target.theme.first())
        assertEquals(listOf("010C", "0142", "TERRAIN", ""), target.slotPids.first())
        assertEquals(listOf(2.9168f, 2.2f, 1.7f, 1.4f, 0f, 1.1f), target.gearRatios.first())
        assertEquals(MAINTENANCE_TYPES.size, target.maintenanceIntervals.first().size)
        assertEquals(205, target.fuelTankCapacityDl.first())
    }

    @Test
    fun `the defaults survive the trip too`() = runTest {
        val source = freshPrefs()
        val target = freshPrefs()
        roundTrip(source, target)
        assertEquals(SettingsTransfer(source).snapshot(), SettingsTransfer(target).snapshot())
    }

    @Test
    fun `an import resets a value back to the default when the source is at the default`() = runTest {
        val source = freshPrefs()
        val target = freshPrefs()
        SettingsTransfer(target).apply(sampleSettings())
        roundTrip(source, target)
        assertEquals(SettingsTransfer(source).snapshot(), SettingsTransfer(target).snapshot())
        assertEquals(0, target.maintenanceIntervals.first().size)   // 0 loescht das Intervall
        assertEquals(0, target.fuelRangeFullKm.first())
        assertEquals(listOf("010C", "010D", "0105", ""), target.slotPids.first())
    }

    @Test
    fun `a key that is not in the values stays as it is`() = runTest {
        val prefs = freshPrefs()
        SettingsTransfer(prefs).apply(sampleSettings())
        SettingsTransfer(prefs).apply(mapOf("theme" to "dark"))
        val snap = SettingsTransfer(prefs).snapshot()
        assertEquals("dark", snap["theme"])
        assertEquals(sampleSettings() + ("theme" to "dark"), snap)
    }

    @Test
    fun `all four slots are written`() = runTest {
        val prefs = freshPrefs()
        SettingsTransfer(prefs).apply(mapOf("slot_pids" to "0142,010B,TERRAIN,010C"))
        assertEquals(listOf("0142", "010B", "TERRAIN", "010C"), prefs.slotPids.first())
    }

    @Test
    fun `an error while writing puts every key back and reaches the caller`() = runTest {
        val prefs = freshPrefs()
        val before = SettingsTransfer(prefs).snapshot()
        val spy = spyk(prefs)
        coEvery { spy.setGearRatio(2, any()) } throws IOException("disk full")
        val failure = thrown<IOException> { SettingsTransfer(spy).apply(sampleSettings()) }
        assertEquals("disk full", failure.message)
        assertEquals(before, SettingsTransfer(prefs).snapshot())
        assertEquals(listOf("010C", "010D", "0105", ""), prefs.slotPids.first())
        assertEquals(0, prefs.maintenanceIntervals.first().size)
    }

    @Test
    fun `an error also restores a state that was not the default`() = runTest {
        val prefs = freshPrefs()
        val old = sampleSettings() + mapOf("theme" to "blue", "fuel_range_full_km" to 500, "gear_ratio_5" to 1.2f)
        SettingsTransfer(prefs).apply(old)
        val spy = spyk(prefs)
        coEvery { spy.setPrimaryRatio(any()) } throws IOException("boom")
        thrown<IOException> { SettingsTransfer(spy).apply(mapOf("theme" to "green", "fuel_range_full_km" to 0, "use_imperial" to false, "primary_ratio" to 2.0f)) }
        assertEquals(old, SettingsTransfer(prefs).snapshot())
    }

    @Test
    fun `the rollback writes only the keys the import touched, not the whole snapshot`() = runTest {
        val prefs = freshPrefs()
        val spy = spyk(prefs)
        coEvery { spy.setGearRatio(2, any()) } throws IOException("boom")
        thrown<IOException> { SettingsTransfer(spy).apply(mapOf("theme" to "dark", "gear_ratio_3" to 1.5f)) }
        // ein Schluessel, der nicht in der Datei stand, wird weder geschrieben noch beim Zurueckrollen angefasst (sonst wuerde sein Standard zu einem expliziten Wert)
        coVerify(exactly = 0) { spy.setUseImperial(any()) }
        coVerify(exactly = 0) { spy.setFuelRangeFullKm(any()) }
        assertEquals("system", SettingsTransfer(prefs).snapshot()["theme"])
    }

    @Test
    fun `a key that was only half written is put back too`() = runTest {
        val prefs = freshPrefs()
        SettingsTransfer(prefs).apply(mapOf("slot_pids" to "0142,010B,TERRAIN,010C"))
        val spy = spyk(prefs)
        var calls = 0
        // der dritte Platz scheitert: die Plaetze 0 und 1 sind dann schon geschrieben, das Zurueckschreiben ruft denselben Setter wieder
        coEvery { spy.setSlotPid(2, any()) } coAnswers { if (calls++ == 0) throw IOException("half") else prefs.setSlotPid(firstArg(), secondArg()) }
        thrown<IOException> { SettingsTransfer(spy).apply(mapOf("slot_pids" to "010C,010D,0105,0142")) }
        assertEquals(listOf("0142", "010B", "TERRAIN", "010C"), prefs.slotPids.first())
    }

    @Test
    fun `a value a setter refuses rolls everything back`() = runTest {
        val prefs = freshPrefs()
        val before = SettingsTransfer(prefs).snapshot()
        // nicht ueber den Katalog geprueft, nur im Test eingeschleust: setFuelRangeFullKm(20) verlangt 0 oder 50..1000
        thrown<IllegalArgumentException> { SettingsTransfer(prefs).apply(mapOf("theme" to "dark", "use_imperial" to true, "fuel_range_full_km" to 20)) }
        assertEquals(before, SettingsTransfer(prefs).snapshot())
    }

    @Test
    fun `a value of the wrong type rolls everything back`() = runTest {
        val prefs = freshPrefs()
        val before = SettingsTransfer(prefs).snapshot()
        thrown<ClassCastException> { SettingsTransfer(prefs).apply(mapOf("theme" to "dark", "rpm_blink_threshold" to "9000")) }
        assertEquals(before, SettingsTransfer(prefs).snapshot())
    }

    @Test
    fun `a cancellation in the middle rolls back and goes through`() = kotlinx.coroutines.runBlocking {
        val prefs = freshPrefs()
        val before = SettingsTransfer(prefs).snapshot()
        val spy = spyk(prefs)
        lateinit var job: Job
        var calls = 0
        // nur der erste Aufruf bricht ab; das Zurueckschreiben ruft denselben Setter noch einmal und muss durchlaufen
        coEvery { spy.setGearRatio(2, any()) } coAnswers { if (calls++ == 0) { job.cancel(); awaitCancellation() } else prefs.setGearRatio(firstArg(), secondArg()) }
        var cancelled = false
        job = launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                SettingsTransfer(spy).apply(sampleSettings())
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        job.join()
        assertTrue(cancelled)
        assertEquals(before, SettingsTransfer(prefs).snapshot())
    }

    @Test
    fun `a CancellationException from a setter is rethrown after the rollback`() = runTest {
        val prefs = freshPrefs()
        val before = SettingsTransfer(prefs).snapshot()
        val spy = spyk(prefs)
        coEvery { spy.setUseImperial(any()) } throws CancellationException("stop")
        thrown<CancellationException> { SettingsTransfer(spy).apply(sampleSettings()) }
        assertEquals(before, SettingsTransfer(prefs).snapshot())
        assertTrue(currentCoroutineContext()[Job]!!.isActive)
    }
}
