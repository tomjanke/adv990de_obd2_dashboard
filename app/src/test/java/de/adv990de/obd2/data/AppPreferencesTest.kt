// app/src/test/java/de/adv990de/obd2/data/AppPreferencesTest.kt
package de.adv990de.obd2.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.obd.PidRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun makePreferences(): AppPreferences = makePreferencesWithDataStore().first

    // Same setup as makePreferences(), but also returns the underlying DataStore so tests can
    // write raw/corrupt values directly (bypassing persistDevice's BtType.name encoding) and an
    // overridable bluetoothAdapter so tests can force getRemoteDevice(...) to throw.
    private fun makePreferencesWithDataStore(
        btAdapter: BluetoothAdapter = mockk<BluetoothAdapter> {
            every { getRemoteDevice("AA:BB:CC:DD:EE:FF") } returns mockk<BluetoothDevice> {
                every { address } returns "AA:BB:CC:DD:EE:FF"
            }
        }
    ): Pair<AppPreferences, DataStore<Preferences>> {
        // UnconfinedTestDispatcher is required: DataStore launches internal coroutines tied to its
        // own TestScope. Using StandardTestDispatcher would cause a scheduler deadlock because
        // DataStore's scope and runTest's scheduler are separate — DataStore's work never runs.
        // A fresh dispatcher per call avoids accidental state sharing between tests.
        val testDispatcher = UnconfinedTestDispatcher()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(testDispatcher),
            produceFile = { tempFolder.newFile("test.preferences_pb") }
        )
        return AppPreferences(dataStore, btAdapter) to dataStore
    }

    // ── slotPids / setSlotPid ─────────────────────────────────────────────────

    @Test
    fun `slotPids returns DEFAULT_SLOT_PIDS when nothing is stored`() = runTest {
        val prefs = makePreferences()
        val slots = prefs.slotPids.first()
        // slotPids always pads to exactly 4 entries; DEFAULT_SLOT_PIDS may be shorter.
        // Verify each default entry appears in the matching slot.
        AppPreferences.DEFAULT_SLOT_PIDS.forEachIndexed { i, id ->
            assertEquals("slot $i should match DEFAULT_SLOT_PIDS[$i]", id, slots[i])
        }
    }

    @Test
    fun `setSlotPid updates the correct slot and leaves others unchanged`() = runTest {
        val prefs = makePreferences()
        // Use the PID at index 1 of defaults so it is a known-valid, distinct value.
        val newPid = PidRegistry.defaults[1].id
        prefs.setSlotPid(2, newPid)

        val slots = prefs.slotPids.first()
        assertEquals(AppPreferences.DEFAULT_SLOT_PIDS[0], slots[0])
        assertEquals(AppPreferences.DEFAULT_SLOT_PIDS[1], slots[1])
        assertEquals(newPid,                               slots[2])
        // Slot 3 was never written; it falls back to DEFAULT_SLOT_PIDS[3] if it exists,
        // otherwise to empty string (DEFAULT_SLOT_PIDS has fewer than 4 entries currently).
        val expectedSlot3 = AppPreferences.DEFAULT_SLOT_PIDS.getOrElse(3) { "" }
        assertEquals(expectedSlot3, slots[3])
    }

    @Test
    fun `slotPids always returns exactly 4 entries`() = runTest {
        val prefs = makePreferences()
        // Verify size on a fresh store.
        assertEquals(4, prefs.slotPids.first().size)

        // And after an update.
        prefs.setSlotPid(0, PidRegistry.defaults[0].id)
        assertEquals(4, prefs.slotPids.first().size)
    }

    @Test
    fun `setSlotPid rejects an out-of-range slot`() = runTest {
        val prefs = makePreferences()
        try {
            prefs.setSlotPid(4, PidRegistry.defaults[0].id)
            org.junit.Assert.fail("Expected IllegalArgumentException for slot 4")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ── lastDevice ────────────────────────────────────────────────────────────

    @Test
    fun `lastDevice is null when nothing is persisted`() = runTest {
        val prefs = makePreferences()
        assertNull(prefs.lastDevice.first())
    }

    @Test
    fun `persistDevice then lastDevice round-trips name and type`() = runTest {
        val btDevice = mockk<BluetoothDevice> { every { address } returns "AA:BB:CC:DD:EE:FF" }
        val scanned = ScannedDevice(btDevice, BtType.CLASSIC, "ELM327-Classic")
        val prefs = makePreferences()

        prefs.persistDevice(scanned)
        val last = prefs.lastDevice.first()

        assertNotNull(last)
        assertEquals("ELM327-Classic", last!!.name)
        assertEquals(BtType.CLASSIC, last.type)
        assertEquals("AA:BB:CC:DD:EE:FF", last.device!!.address)
    }

    @Test
    fun `lastDevice is null when stored device type is unrecognized`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()
        // Write directly to the DataStore (not via persistDevice) to simulate a corrupt/stale
        // device_type string that no longer matches any BtType enum constant, e.g. after an
        // app downgrade or a renamed enum value.
        dataStore.edit { p ->
            p[stringPreferencesKey("device_address")] = "AA:BB:CC:DD:EE:FF"
            p[stringPreferencesKey("device_name")]    = "Weird Device"
            p[stringPreferencesKey("device_type")]    = "NOT_A_REAL_BT_TYPE"
        }

        assertNull(prefs.lastDevice.first())
    }

    @Test
    fun `a device saved with the former BLE type is read as unknown - no auto-connect, the user picks a device again`() = runTest {
        val (prefs, dataStore) = makePreferencesWithDataStore()
        // BLE gibt es seit 1.24.9 nicht mehr (die Geraeteliste hat nie BLE-Adapter angeboten). Ein aus aelterer Zeit
        // gespeichertes Geraet dieses Typs darf weder abstuerzen noch eine Verbindung versuchen, fuer die es keinen Code gibt.
        dataStore.edit { p ->
            p[stringPreferencesKey("device_address")] = "AA:BB:CC:DD:EE:FF"
            p[stringPreferencesKey("device_name")]    = "OBDLink CX"
            p[stringPreferencesKey("device_type")]    = "BLE"
        }

        assertNull(prefs.lastDevice.first())
    }

    @Test
    fun `lastDevice is null when stored MAC address is invalid`() = runTest {
        val btAdapter = mockk<BluetoothAdapter> {
            every { getRemoteDevice("INVALID_MAC") } throws
                IllegalArgumentException("Invalid Bluetooth address")
        }
        val (prefs, dataStore) = makePreferencesWithDataStore(btAdapter)
        dataStore.edit { p ->
            p[stringPreferencesKey("device_address")] = "INVALID_MAC"
            p[stringPreferencesKey("device_name")]    = "Some Device"
            p[stringPreferencesKey("device_type")]    = BtType.CLASSIC.name
        }

        assertNull(prefs.lastDevice.first())
    }

    // ── persistDevice SIMULATOR guard ────────────────────────────────────────

    @Test
    fun `persistDevice with SIMULATOR device does not persist anything`() = runTest {
        val prefs = makePreferences()
        val simDevice = ScannedDevice(device = null, type = BtType.SIMULATOR, name = "OBD Sim")
        prefs.persistDevice(simDevice)
        assertNull(prefs.lastDevice.first())
    }

    // ── logOnlyDtcSessions ────────────────────────────────────────────────────

    @Test
    fun `logOnlyDtcSessions defaults to false`() = runTest {
        assertEquals(false, makePreferences().logOnlyDtcSessions.first())
    }

    @Test
    fun `setLogOnlyDtcSessions persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setLogOnlyDtcSessions(true)
        assertEquals(true, prefs.logOnlyDtcSessions.first())
    }

    // Die frueheren Schluessel `log_only_discovered_pids`, `unlogged_pid_ids` und `logged_pid_ids` werden nicht mehr gelesen
    // (seit 1.28.0 schreibt die Aufzeichnung, was abgefragt wird, ohne weiteren Filter); ein alter Wert im DataStore bleibt wirkungslos.

    // ── showAllBtDevices ──────────────────────────────────────────────────────

    @Test
    fun `showAllBtDevices defaults to false`() = runTest {
        assertEquals(false, makePreferences().showAllBtDevices.first())
    }

    @Test
    fun `setShowAllBtDevices persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setShowAllBtDevices(true)
        assertEquals(true, prefs.showAllBtDevices.first())
    }

    // ── hideNavOnDrive ────────────────────────────────────────────────────────

    @Test
    fun `hideNavOnDrive defaults to false`() = runTest {
        assertEquals(false, makePreferences().hideNavOnDrive.first())
    }

    @Test
    fun `setHideNavOnDrive persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setHideNavOnDrive(true)
        assertEquals(true, prefs.hideNavOnDrive.first())
    }

    // ── crashReportsEnabled (QS B33) ──────────────────────────────────────────

    @Test
    fun `crashReportsEnabled defaults to true - a build with a DSN reports, as before the switch existed`() = runTest {
        assertEquals(true, makePreferences().crashReportsEnabled.first())
    }

    @Test
    fun `setCrashReportsEnabled persists both values`() = runTest {
        val prefs = makePreferences()
        prefs.setCrashReportsEnabled(false)
        assertEquals(false, prefs.crashReportsEnabled.first())
        prefs.setCrashReportsEnabled(true)
        assertEquals(true, prefs.crashReportsEnabled.first())
    }

    // ── useGpsSpeed ───────────────────────────────────────────────────────────

    @Test
    fun `useGpsSpeed defaults to false`() = runTest {
        assertEquals(false, makePreferences().useGpsSpeed.first())
    }

    @Test
    fun `useGpsSpeed does not emit again when an unrelated setting is written (DAT-13)`() = runTest {
        // ReadingsPipeline startet den Standort-Listener per flatMapLatest auf diesem Flow neu — jeder DataStore-Schreibvorgang
        // (u. a. persistDevice bei jedem manuellen Verbinden) liess ihn vorher mit demselben Wert erneut anlaufen.
        val prefs = makePreferences()
        val emissions = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { prefs.useGpsSpeed.toList(emissions) }

        prefs.setUseImperial(true)
        prefs.setTheme(AppPreferences.THEME_BLUE)
        assertEquals("fremde Schluessel loesen nichts aus", listOf(false), emissions)

        prefs.setUseGpsSpeed(true)
        assertEquals("eine echte Aenderung kommt an", listOf(false, true), emissions)

        prefs.setUseGpsSpeed(true)
        assertEquals("dasselbe noch einmal schreiben ist keine Aenderung", listOf(false, true), emissions)
        job.cancel()
    }

    @Test
    fun `setUseGpsSpeed persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setUseGpsSpeed(true)
        assertEquals(true, prefs.useGpsSpeed.first())
    }

    // ── backgroundLocationPrompted ───────────────────────────────────────────

    @Test
    fun `backgroundLocationPrompted defaults to false`() = runTest {
        assertEquals(false, makePreferences().backgroundLocationPrompted.first())
    }

    @Test
    fun `setBackgroundLocationPrompted persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setBackgroundLocationPrompted(true)
        assertEquals(true, prefs.backgroundLocationPrompted.first())
    }

    // ── weatherLocationPrompted ───────────────────────────────────────────────

    @Test
    fun `weatherLocationPrompted defaults to false`() = runTest {
        assertEquals(false, makePreferences().weatherLocationPrompted.first())
    }

    @Test
    fun `setWeatherLocationPrompted persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setWeatherLocationPrompted(true)
        assertEquals(true, prefs.weatherLocationPrompted.first())
    }

    // ── rpmBlinkThreshold ─────────────────────────────────────────────────────

    @Test
    fun `rpmBlinkThreshold defaults to 0`() = runTest {
        assertEquals(0, makePreferences().rpmBlinkThreshold.first())
    }

    @Test
    fun `setRpmBlinkThreshold persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setRpmBlinkThreshold(4500)
        assertEquals(4500, prefs.rpmBlinkThreshold.first())
    }

    // ── rpmDynamicThreshold ───────────────────────────────────────────────────

    @Test
    fun `rpmDynamicThreshold defaults to false`() = runTest {
        assertEquals(false, makePreferences().rpmDynamicThreshold.first())
    }

    @Test
    fun `setRpmDynamicThreshold persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setRpmDynamicThreshold(true)
        assertEquals(true, prefs.rpmDynamicThreshold.first())
    }

    // ── Display preferences ───────────────────────────────────────────────────

    @Test
    fun `useImperial defaults to false`() = runTest {
        assertEquals(false, makePreferences().useImperial.first())
    }

    @Test
    fun `setUseImperial persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setUseImperial(true)
        assertEquals(true, prefs.useImperial.first())
    }

    @Test
    fun `theme defaults to system`() = runTest {
        assertEquals("system", makePreferences().theme.first())
    }

    @Test
    fun `setTheme persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setTheme("dark")
        assertEquals("dark", prefs.theme.first())
    }

    // ── Getriebe-Einstellungen (virtuelle Ganganzeige) ────────────────────────

    @Test
    fun `wheelCircumferenceMm returns the default when nothing is stored`() = runTest {
        val prefs = makePreferences()
        assertEquals(
            AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM,
            prefs.wheelCircumferenceMm.first(),
            0.001f
        )
    }

    @Test
    fun `setWheelCircumferenceMm persists the value`() = runTest {
        val prefs = makePreferences()
        prefs.setWheelCircumferenceMm(2100f)
        assertEquals(2100f, prefs.wheelCircumferenceMm.first(), 0.001f)
    }

    @Test
    fun `primaryRatio and secondaryRatio return their defaults when nothing is stored`() = runTest {
        val prefs = makePreferences()
        assertEquals(AppPreferences.DEFAULT_PRIMARY_RATIO, prefs.primaryRatio.first(), 0.0001f)
        assertEquals(AppPreferences.DEFAULT_SECONDARY_RATIO, prefs.secondaryRatio.first(), 0.0001f)
    }

    @Test
    fun `setPrimaryRatio and setSecondaryRatio persist independently`() = runTest {
        val prefs = makePreferences()
        prefs.setPrimaryRatio(2.0f)
        assertEquals(2.0f, prefs.primaryRatio.first(), 0.0001f)
        // secondaryRatio must be untouched by the primaryRatio write
        assertEquals(AppPreferences.DEFAULT_SECONDARY_RATIO, prefs.secondaryRatio.first(), 0.0001f)
    }

    @Test
    fun `gearRatios returns the six defaults when nothing is stored`() = runTest {
        val prefs = makePreferences()
        assertEquals(AppPreferences.DEFAULT_GEAR_RATIOS, prefs.gearRatios.first())
    }

    @Test
    fun `setGearRatio updates only the targeted gear`() = runTest {
        val prefs = makePreferences()
        prefs.setGearRatio(2, 8.0f)

        val ratios = prefs.gearRatios.first()
        assertEquals(AppPreferences.DEFAULT_GEAR_RATIOS[0], ratios[0], 0.0001f)
        assertEquals(AppPreferences.DEFAULT_GEAR_RATIOS[1], ratios[1], 0.0001f)
        assertEquals(8.0f, ratios[2], 0.0001f)
        assertEquals(AppPreferences.DEFAULT_GEAR_RATIOS[3], ratios[3], 0.0001f)
        assertEquals(AppPreferences.DEFAULT_GEAR_RATIOS[4], ratios[4], 0.0001f)
        assertEquals(AppPreferences.DEFAULT_GEAR_RATIOS[5], ratios[5], 0.0001f)
    }

    @Test
    fun `setGearRatio rejects an out-of-range index`() = runTest {
        val prefs = makePreferences()
        try {
            prefs.setGearRatio(6, 5f)
            org.junit.Assert.fail("Expected IllegalArgumentException for index 6")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ── OBD-Watchdog (erzwungener Reconnect bei eingefrorenen Sensorwerten) ──

    @Test
    fun `obdWatchdogTimeoutSeconds returns the default when nothing is stored`() = runTest {
        val prefs = makePreferences()
        assertEquals(
            AppPreferences.DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS,
            prefs.obdWatchdogTimeoutSeconds.first()
        )
    }

    @Test
    fun `setObdWatchdogTimeoutSeconds persists an option value`() = runTest {
        val prefs = makePreferences()
        for (option in AppPreferences.OBD_WATCHDOG_TIMEOUT_OPTIONS_SECONDS) {
            prefs.setObdWatchdogTimeoutSeconds(option)
            assertEquals(option, prefs.obdWatchdogTimeoutSeconds.first())
        }
    }

    @Test
    fun `obdWatchdogTimeoutSeconds snaps a stored off-list value to the nearest option`() = runTest {
        // Earlier versions took a free number; the UI now offers only the fixed options, so the
        // old value is reinterpreted on read (no migration, nothing written back).
        val prefs = makePreferences()
        prefs.setObdWatchdogTimeoutSeconds(45)   // tie between 30 and 60 -> the larger one
        assertEquals(60, prefs.obdWatchdogTimeoutSeconds.first())
        prefs.setObdWatchdogTimeoutSeconds(14)
        assertEquals(10, prefs.obdWatchdogTimeoutSeconds.first())
        prefs.setObdWatchdogTimeoutSeconds(95)
        assertEquals(120, prefs.obdWatchdogTimeoutSeconds.first())
    }

    @Test
    fun `obdWatchdogTimeoutSeconds keeps absurd stored values inside the option range`() = runTest {
        val prefs = makePreferences()
        prefs.setObdWatchdogTimeoutSeconds(0)
        assertEquals(10, prefs.obdWatchdogTimeoutSeconds.first())
        prefs.setObdWatchdogTimeoutSeconds(-1)
        assertEquals(10, prefs.obdWatchdogTimeoutSeconds.first())
        prefs.setObdWatchdogTimeoutSeconds(86_400)
        assertEquals(120, prefs.obdWatchdogTimeoutSeconds.first())
    }

    // ── Reconnect-Basis-Verzoegerung (Backlog-Cleanup, Sweep 2026-09-28/29) ───

    @Test
    fun `reconnectBaseDelaySeconds returns the default when nothing is stored`() = runTest {
        val prefs = makePreferences()
        assertEquals(
            AppPreferences.DEFAULT_RECONNECT_BASE_DELAY_SECONDS,
            prefs.reconnectBaseDelaySeconds.first()
        )
    }

    @Test
    fun `setReconnectBaseDelaySeconds persists an option value`() = runTest {
        val prefs = makePreferences()
        for (option in AppPreferences.RECONNECT_BASE_DELAY_OPTIONS_SECONDS) {
            prefs.setReconnectBaseDelaySeconds(option)
            assertEquals(option, prefs.reconnectBaseDelaySeconds.first())
        }
    }

    @Test
    fun `reconnectBaseDelaySeconds snaps a stored off-list value to the nearest option`() = runTest {
        val prefs = makePreferences()
        prefs.setReconnectBaseDelaySeconds(5)    // nearer to 3 than to 10
        assertEquals(3, prefs.reconnectBaseDelaySeconds.first())
        prefs.setReconnectBaseDelaySeconds(7)    // nearer to 10 than to 3
        assertEquals(10, prefs.reconnectBaseDelaySeconds.first())
        prefs.setReconnectBaseDelaySeconds(20)   // tie between 10 and 30 -> the larger one
        assertEquals(30, prefs.reconnectBaseDelaySeconds.first())
    }

    @Test
    fun `reconnectBaseDelaySeconds keeps absurd stored values inside the option range`() = runTest {
        val prefs = makePreferences()
        prefs.setReconnectBaseDelaySeconds(0)
        assertEquals(3, prefs.reconnectBaseDelaySeconds.first())
        prefs.setReconnectBaseDelaySeconds(999)
        assertEquals(30, prefs.reconnectBaseDelaySeconds.first())
    }

    // ── B15: a damaged DataStore must not become a crash loop ────────────────

    /**
     * A truncated protobuf message: tag 0x0A (field 1, length-delimited) announces 127 bytes, one follows.
     * (Plain text is NOT enough — its first byte reads as an "end group" tag, which the parser accepts as
     * an empty message; the negative control below proves this file really fails without the handler.)
     */
    private val corruptBytes = byteArrayOf(0x0A, 0x7F, 0x01)

    private fun corruptFile() = tempFolder.newFile("corrupt.preferences_pb").also { it.writeBytes(corruptBytes) }

    private fun dataStoreOn(file: java.io.File, withHandler: Boolean): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = if (withHandler) appPreferencesCorruptionHandler() else null,
            scope = TestScope(UnconfinedTestDispatcher()),
            produceFile = { file },
        )

    @Test
    fun `the test file is really corrupt - without the handler reading it throws`() = runTest {
        val dataStore = dataStoreOn(corruptFile(), withHandler = false)

        val failure = runCatching { dataStore.data.first() }.exceptionOrNull()

        assertNotNull("Der Negativ-Gegenbeweis: ohne Handler wirft das Lesen", failure)
    }

    @Test
    fun `a corrupt preferences file yields the defaults instead of throwing`() = runTest {
        val prefs = AppPreferences(dataStoreOn(corruptFile(), withHandler = true), mockk())

        assertEquals(AppPreferences.THEME_SYSTEM, prefs.theme.first())
        assertEquals(AppPreferences.DEFAULT_SLOT_PIDS, prefs.slotPids.first())
        assertNull(prefs.lastDevice.first())
    }

    @Test
    fun `after a corrupt file was replaced new settings are stored normally`() = runTest {
        val prefs = AppPreferences(dataStoreOn(corruptFile(), withHandler = true), mockk())

        prefs.setTheme(AppPreferences.THEME_DARK)

        assertEquals(AppPreferences.THEME_DARK, prefs.theme.first())
    }

    @Test
    fun `an IOException while reading yields the defaults instead of throwing`() = runTest {
        val failingStore = object : DataStore<Preferences> {
            override val data: kotlinx.coroutines.flow.Flow<Preferences> =
                kotlinx.coroutines.flow.flow { throw java.io.IOException("disk read error") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw java.io.IOException("disk write error")
        }
        val prefs = AppPreferences(failingStore, mockk())

        assertEquals(AppPreferences.THEME_SYSTEM, prefs.theme.first())
        assertEquals(AppPreferences.DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS, prefs.obdWatchdogTimeoutSeconds.first())
    }

    @Test
    fun `a non-IO failure while reading still propagates`() = runTest {
        val failingStore = object : DataStore<Preferences> {
            override val data: kotlinx.coroutines.flow.Flow<Preferences> =
                kotlinx.coroutines.flow.flow { throw IllegalStateException("programming error") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IllegalStateException("programming error")
        }
        val prefs = AppPreferences(failingStore, mockk())

        val failure = runCatching { prefs.theme.first() }.exceptionOrNull()

        assertTrue("Programmierfehler duerfen nicht still verschluckt werden", failure is IllegalStateException)
    }

    // ── Tankanzeige (Reichweite bei vollem Tank, gefahrene Kilometer) ──────────

    @Test
    fun `fuelRangeFullKm is 0 (not set) when nothing is stored`() = runTest {
        assertEquals(0, makePreferences().fuelRangeFullKm.first())
    }

    @Test
    fun `setFuelRangeFullKm stores a valid range and reads it back`() = runTest {
        val prefs = makePreferences()

        prefs.setFuelRangeFullKm(320)

        assertEquals(320, prefs.fuelRangeFullKm.first())
    }

    @Test
    fun `setFuelRangeFullKm stores the limits 50 and 1000`() = runTest {
        val prefs = makePreferences()

        prefs.setFuelRangeFullKm(50)
        assertEquals(50, prefs.fuelRangeFullKm.first())
        prefs.setFuelRangeFullKm(1000)
        assertEquals(1000, prefs.fuelRangeFullKm.first())
    }

    @Test
    fun `setFuelRangeFullKm with 0 clears the range`() = runTest {
        val prefs = makePreferences()
        prefs.setFuelRangeFullKm(320)

        prefs.setFuelRangeFullKm(0)

        assertEquals(0, prefs.fuelRangeFullKm.first())
    }

    @Test
    fun `setFuelRangeFullKm refuses a value outside 50 to 1000 and keeps the old one`() = runTest {
        val prefs = makePreferences()
        prefs.setFuelRangeFullKm(320)

        for (bad in listOf(-1, 1, 49, 1001, 100_000)) {
            val failure = runCatching { prefs.setFuelRangeFullKm(bad) }.exceptionOrNull()
            assertTrue("$bad muss abgelehnt werden, war $failure", failure is IllegalArgumentException)
        }
        assertEquals(320, prefs.fuelRangeFullKm.first())
    }

    @Test
    fun `an out-of-range value in the store reads as not set`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()
        store.edit { it[androidx.datastore.preferences.core.intPreferencesKey("fuel_range_full_km")] = 5 }

        assertEquals(0, prefs.fuelRangeFullKm.first())
    }

    @Test
    fun `fuelKmSinceFill is 0 when nothing is stored`() = runTest {
        assertEquals(0f, makePreferences().fuelKmSinceFill.first(), 0f)
    }

    @Test
    fun `setFuelKmSinceFill stores the counter and reads it back`() = runTest {
        val prefs = makePreferences()

        prefs.setFuelKmSinceFill(123.4f)

        assertEquals(123.4f, prefs.fuelKmSinceFill.first(), 0.0001f)
    }

    @Test
    fun `a negative or non-finite counter in the store reads as 0`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()
        val key = androidx.datastore.preferences.core.floatPreferencesKey("fuel_km_since_fill")

        store.edit { it[key] = -3f }
        assertEquals(0f, prefs.fuelKmSinceFill.first(), 0f)
        store.edit { it[key] = Float.NaN }
        assertEquals(0f, prefs.fuelKmSinceFill.first(), 0f)
    }

    @Test
    fun `setFuelKmSinceFill refuses a negative or non-finite counter`() = runTest {
        val prefs = makePreferences()

        for (bad in listOf(-0.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val failure = runCatching { prefs.setFuelKmSinceFill(bad) }.exceptionOrNull()
            assertTrue("$bad muss abgelehnt werden, war $failure", failure is IllegalArgumentException)
        }
        assertEquals(0f, prefs.fuelKmSinceFill.first(), 0f)
    }

    // ── Reserve der Tankanzeige (km Restreichweite, 0 = aus) ──────────────────

    @Test
    fun `fuelReserveKm is 0 (off) when nothing is stored`() = runTest {
        assertEquals(0, makePreferences().fuelReserveKm.first())
    }

    @Test
    fun `setFuelReserveKm stores a valid reserve and reads it back`() = runTest {
        val prefs = makePreferences()

        prefs.setFuelReserveKm(40)

        assertEquals(40, prefs.fuelReserveKm.first())
    }

    @Test
    fun `setFuelReserveKm stores the limits 5 and 500`() = runTest {
        val prefs = makePreferences()

        prefs.setFuelReserveKm(5)
        assertEquals(5, prefs.fuelReserveKm.first())
        prefs.setFuelReserveKm(500)
        assertEquals(500, prefs.fuelReserveKm.first())
    }

    @Test
    fun `setFuelReserveKm with 0 switches the reserve off`() = runTest {
        val prefs = makePreferences()
        prefs.setFuelReserveKm(40)

        prefs.setFuelReserveKm(0)

        assertEquals(0, prefs.fuelReserveKm.first())
    }

    @Test
    fun `setFuelReserveKm refuses a value outside 5 to 500 and keeps the old one`() = runTest {
        val prefs = makePreferences()
        prefs.setFuelReserveKm(40)

        for (bad in listOf(-1, 1, 4, 501, 100_000)) {
            val failure = runCatching { prefs.setFuelReserveKm(bad) }.exceptionOrNull()
            assertTrue("$bad muss abgelehnt werden, war $failure", failure is IllegalArgumentException)
        }
        assertEquals(40, prefs.fuelReserveKm.first())
    }

    @Test
    fun `an out-of-range reserve in the store reads as off`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()
        val key = androidx.datastore.preferences.core.intPreferencesKey("fuel_reserve_km")

        store.edit { it[key] = 3 }
        assertEquals(0, prefs.fuelReserveKm.first())
        store.edit { it[key] = 9999 }
        assertEquals(0, prefs.fuelReserveKm.first())
    }

    @Test
    fun `the reserve is stored under the persisted key fuel_reserve_km`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()

        prefs.setFuelReserveKm(40)

        val key = androidx.datastore.preferences.core.intPreferencesKey("fuel_reserve_km")
        assertEquals(40, store.data.first()[key])
    }

    // ── Tankinhalt der Tankanzeige (Zehntelliter, 0 = nicht festgelegt) ───────

    @Test
    fun `fuelTankCapacityDl is 0 (not set) when nothing is stored`() = runTest {
        assertEquals(0, makePreferences().fuelTankCapacityDl.first())
    }

    @Test
    fun `setFuelTankCapacityDl stores a valid content and reads it back`() = runTest {
        val prefs = makePreferences()

        prefs.setFuelTankCapacityDl(200)

        assertEquals(200, prefs.fuelTankCapacityDl.first())
    }

    @Test
    fun `setFuelTankCapacityDl stores the limits 30 and 600`() = runTest {
        val prefs = makePreferences()

        prefs.setFuelTankCapacityDl(30)
        assertEquals(30, prefs.fuelTankCapacityDl.first())
        prefs.setFuelTankCapacityDl(600)
        assertEquals(600, prefs.fuelTankCapacityDl.first())
    }

    @Test
    fun `setFuelTankCapacityDl with 0 clears the content`() = runTest {
        val prefs = makePreferences()
        prefs.setFuelTankCapacityDl(200)

        prefs.setFuelTankCapacityDl(0)

        assertEquals(0, prefs.fuelTankCapacityDl.first())
    }

    @Test
    fun `setFuelTankCapacityDl refuses a value outside 30 to 600 and keeps the old one`() = runTest {
        val prefs = makePreferences()
        prefs.setFuelTankCapacityDl(200)

        for (bad in listOf(-1, 1, 29, 601, 100_000)) {
            val failure = runCatching { prefs.setFuelTankCapacityDl(bad) }.exceptionOrNull()
            assertTrue("$bad muss abgelehnt werden, war $failure", failure is IllegalArgumentException)
        }
        assertEquals(200, prefs.fuelTankCapacityDl.first())
    }

    @Test
    fun `an out-of-range tank content in the store reads as not set`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()
        val key = androidx.datastore.preferences.core.intPreferencesKey("fuel_tank_capacity_dl")

        store.edit { it[key] = 5 }
        assertEquals(0, prefs.fuelTankCapacityDl.first())
        store.edit { it[key] = 9999 }
        assertEquals(0, prefs.fuelTankCapacityDl.first())
    }

    @Test
    fun `the tank content is stored under the persisted key fuel_tank_capacity_dl`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()

        prefs.setFuelTankCapacityDl(200)

        val key = androidx.datastore.preferences.core.intPreferencesKey("fuel_tank_capacity_dl")
        assertEquals(200, store.data.first()[key])
    }

    // ── Gelernte Reichweite (Cache fuer das erste Bild, 0 = keiner) ───────────

    @Test
    fun `fuelRangeLearnedKm is 0 (none) when nothing is stored`() = runTest {
        assertEquals(0, makePreferences().fuelRangeLearnedKm.first())
    }

    @Test
    fun `setFuelRangeLearnedKm stores the cache and reads it back`() = runTest {
        val prefs = makePreferences()

        prefs.setFuelRangeLearnedKm(260)
        assertEquals(260, prefs.fuelRangeLearnedKm.first())
        prefs.setFuelRangeLearnedKm(0)
        assertEquals(0, prefs.fuelRangeLearnedKm.first())
    }

    @Test
    fun `setFuelRangeLearnedKm refuses a value outside 50 to 1000`() = runTest {
        val prefs = makePreferences()
        prefs.setFuelRangeLearnedKm(260)

        for (bad in listOf(-1, 1, 49, 1001)) {
            val failure = runCatching { prefs.setFuelRangeLearnedKm(bad) }.exceptionOrNull()
            assertTrue("$bad muss abgelehnt werden, war $failure", failure is IllegalArgumentException)
        }
        assertEquals(260, prefs.fuelRangeLearnedKm.first())
    }

    @Test
    fun `an out-of-range learned range in the store reads as none`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()
        val key = androidx.datastore.preferences.core.intPreferencesKey("fuel_range_learned_km")

        store.edit { it[key] = 7 }
        assertEquals(0, prefs.fuelRangeLearnedKm.first())
    }

    @Test
    fun `the learned range is stored under the persisted key fuel_range_learned_km`() = runTest {
        val (prefs, store) = makePreferencesWithDataStore()

        prefs.setFuelRangeLearnedKm(260)

        val key = androidx.datastore.preferences.core.intPreferencesKey("fuel_range_learned_km")
        assertEquals(260, store.data.first()[key])
    }
}
