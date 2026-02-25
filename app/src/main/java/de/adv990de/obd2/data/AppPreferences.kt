// app/src/main/java/de/adv990de/obd2/data/AppPreferences.kt
package de.adv990de.obd2.data

import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.obd.PidRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Top-level extension — single DataStore instance per Application context
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

@Singleton
class AppPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val bluetoothAdapter: BluetoothAdapter
) {
    companion object {
        private val KEY_DEVICE_ADDRESS = stringPreferencesKey("device_address")
        private val KEY_DEVICE_NAME    = stringPreferencesKey("device_name")
        private val KEY_DEVICE_TYPE    = stringPreferencesKey("device_type")
        private val KEY_SHOW_ALL_BT_DEVICES = booleanPreferencesKey("show_all_bt_devices")
        private val KEY_SLOT_PIDS      = stringPreferencesKey("slot_pids")
        private val KEY_LOG_ONLY_DTC   = booleanPreferencesKey("log_only_dtc_sessions")
        private val KEY_LOG_ONLY_DISC  = booleanPreferencesKey("log_only_discovered_pids")
        private val KEY_LOGGED_PIDS    = stringSetPreferencesKey("logged_pid_ids")
        private val KEY_HIDE_NAV_ON_DRIVE = booleanPreferencesKey("hide_nav_on_drive")
        private val KEY_USE_IMPERIAL      = booleanPreferencesKey("use_imperial")
        private val KEY_THEME             = stringPreferencesKey("theme")
        private val KEY_RPM_BLINK_THRESHOLD = intPreferencesKey("rpm_blink_threshold")
        private val KEY_RPM_DYNAMIC_THRESHOLD = booleanPreferencesKey("rpm_dynamic_threshold")
        val DEFAULT_SLOT_PIDS: List<String> = List(4) { i -> PidRegistry.defaults.getOrNull(i)?.id ?: "" }
        val ALL_PID_IDS: Set<String>   = PidRegistry.all.keys.toSet()
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT  = "light"
        const val THEME_DARK   = "dark"
        const val THEME_BLUE  = "blue"
        const val THEME_AMBER = "amber"
        const val THEME_GREEN = "green"
    }

    suspend fun persistDevice(device: ScannedDevice) {
        dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ADDRESS] = device.device.address
            prefs[KEY_DEVICE_NAME]    = device.name
            prefs[KEY_DEVICE_TYPE]    = device.type.name
        }
    }

    val lastDevice: Flow<ScannedDevice?> = dataStore.data.map { prefs ->
        val address = prefs[KEY_DEVICE_ADDRESS] ?: return@map null
        val name    = prefs[KEY_DEVICE_NAME]    ?: return@map null
        val type    = prefs[KEY_DEVICE_TYPE]
            ?.let { try { BtType.valueOf(it) } catch (_: IllegalArgumentException) { null } }
            ?: return@map null
        val btDevice = try {
            bluetoothAdapter.getRemoteDevice(address)
        } catch (_: Exception) { return@map null }
        ScannedDevice(btDevice, type, name)
    }

    // ── Device / BT preferences ───────────────────────────────────────────────

    val showAllBtDevices: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_ALL_BT_DEVICES] ?: false
    }

    suspend fun setShowAllBtDevices(enabled: Boolean) {
        dataStore.edit { it[KEY_SHOW_ALL_BT_DEVICES] = enabled }
    }

    // ── Recording preferences ─────────────────────────────────────────────────

    val logOnlyDtcSessions: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_LOG_ONLY_DTC] ?: false
    }

    val logOnlyDiscoveredPids: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_LOG_ONLY_DISC] ?: false
    }

    val loggedPidIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_LOGGED_PIDS] ?: ALL_PID_IDS
    }

    suspend fun setLogOnlyDtcSessions(enabled: Boolean) {
        dataStore.edit { it[KEY_LOG_ONLY_DTC] = enabled }
    }

    suspend fun setLogOnlyDiscoveredPids(enabled: Boolean) {
        dataStore.edit { it[KEY_LOG_ONLY_DISC] = enabled }
    }

    suspend fun toggleLoggedPid(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_LOGGED_PIDS] ?: ALL_PID_IDS
            prefs[KEY_LOGGED_PIDS] = if (id in current) current - id else current + id
        }
    }

    suspend fun setLoggedPids(ids: Set<String>) {
        dataStore.edit { it[KEY_LOGGED_PIDS] = ids }
    }

    // ── Drive state preferences ───────────────────────────────────────────────

    val hideNavOnDrive: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_HIDE_NAV_ON_DRIVE] ?: false
    }

    suspend fun setHideNavOnDrive(enabled: Boolean) {
        dataStore.edit { it[KEY_HIDE_NAV_ON_DRIVE] = enabled }
    }

    // ── Display preferences ───────────────────────────────────────────────────

    val useImperial: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_USE_IMPERIAL] ?: false
    }

    suspend fun setUseImperial(enabled: Boolean) {
        dataStore.edit { it[KEY_USE_IMPERIAL] = enabled }
    }

    val theme: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_THEME] ?: THEME_SYSTEM
    }

    suspend fun setTheme(value: String) {
        dataStore.edit { it[KEY_THEME] = value }
    }

    // ── Dashboard preferences ─────────────────────────────────────────────────

    val rpmBlinkThreshold: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_RPM_BLINK_THRESHOLD] ?: 0
    }

    suspend fun setRpmBlinkThreshold(value: Int) {
        dataStore.edit { it[KEY_RPM_BLINK_THRESHOLD] = value }
    }

    val rpmDynamicThreshold: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_RPM_DYNAMIC_THRESHOLD] ?: false
    }

    suspend fun setRpmDynamicThreshold(enabled: Boolean) {
        dataStore.edit { it[KEY_RPM_DYNAMIC_THRESHOLD] = enabled }
    }

    val slotPids: Flow<List<String>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_SLOT_PIDS]
        val parts = if (raw.isNullOrBlank()) DEFAULT_SLOT_PIDS
                    else raw.split(",").map { it.trim() }
        // Always return exactly 4 entries, padding with empty string if parts is shorter.
        List(4) { i -> parts.getOrElse(i) { DEFAULT_SLOT_PIDS.getOrElse(i) { "" } } }
    }.distinctUntilChanged() // DataStore emits on every write; suppress list-equal emissions

    suspend fun setSlotPid(slot: Int, pidId: String) {
        require(slot in 0..3) { "slot must be 0..3" }
        dataStore.edit { prefs ->
            val current = prefs[KEY_SLOT_PIDS]?.split(",")?.map { it.trim() }
                ?: DEFAULT_SLOT_PIDS
            val updated = List(4) { i ->
                if (i == slot) pidId
                else current.getOrElse(i) { DEFAULT_SLOT_PIDS.getOrElse(i) { "" } }
            }
            prefs[KEY_SLOT_PIDS] = updated.joinToString(",")
        }
    }
}
