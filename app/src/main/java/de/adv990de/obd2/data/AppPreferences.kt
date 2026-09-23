// app/src/main/java/de/adv990de/obd2/data/AppPreferences.kt
package de.adv990de.obd2.data

import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
        private val KEY_USE_GPS_SPEED = booleanPreferencesKey("use_gps_speed")
        private val KEY_BACKGROUND_LOCATION_PROMPTED = booleanPreferencesKey("background_location_prompted")
        private val KEY_USE_IMPERIAL      = booleanPreferencesKey("use_imperial")
        private val KEY_THEME             = stringPreferencesKey("theme")
        private val KEY_RPM_BLINK_THRESHOLD = intPreferencesKey("rpm_blink_threshold")
        private val KEY_RPM_DYNAMIC_THRESHOLD = booleanPreferencesKey("rpm_dynamic_threshold")
        private val KEY_WHEEL_CIRCUMFERENCE_MM = floatPreferencesKey("wheel_circumference_mm")
        private val KEY_PRIMARY_RATIO   = floatPreferencesKey("primary_ratio")
        private val KEY_SECONDARY_RATIO = floatPreferencesKey("secondary_ratio")
        private val KEY_GEAR_RATIO_1 = floatPreferencesKey("gear_ratio_1")
        private val KEY_GEAR_RATIO_2 = floatPreferencesKey("gear_ratio_2")
        private val KEY_GEAR_RATIO_3 = floatPreferencesKey("gear_ratio_3")
        private val KEY_GEAR_RATIO_4 = floatPreferencesKey("gear_ratio_4")
        private val KEY_GEAR_RATIO_5 = floatPreferencesKey("gear_ratio_5")
        private val KEY_GEAR_RATIO_6 = floatPreferencesKey("gear_ratio_6")
        private val GEAR_RATIO_KEYS = listOf(
            KEY_GEAR_RATIO_1, KEY_GEAR_RATIO_2, KEY_GEAR_RATIO_3,
            KEY_GEAR_RATIO_4, KEY_GEAR_RATIO_5, KEY_GEAR_RATIO_6,
        )
        val DEFAULT_SLOT_PIDS: List<String> = List(4) { i -> PidRegistry.defaults.getOrNull(i)?.id ?: "" }
        val ALL_PID_IDS: Set<String>   = PidRegistry.all.keys.toSet()
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT  = "light"
        const val THEME_DARK   = "dark"
        const val THEME_BLUE  = "blue"
        const val THEME_AMBER = "amber"
        const val THEME_GREEN = "green"
        // Aus Reifengroesse 150/70-18 berechnet (siehe Design-Spec): (18*25.4 + 2*150*0.70) * PI
        const val DEFAULT_WHEEL_CIRCUMFERENCE_MM = 2096f
        const val DEFAULT_PRIMARY_RATIO = 1.9143f    // 67/35
        const val DEFAULT_SECONDARY_RATIO = 2.625f   // 42/16
        val DEFAULT_GEAR_RATIOS: List<Float> =
            listOf(2.9167f, 2.1333f, 1.6667f, 1.35f, 1.125f, 1.04f)
    }

    suspend fun persistDevice(device: ScannedDevice) {
        if (device.device == null) return   // simulator – nothing to persist
        dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ADDRESS] = device.address
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
        if (type == BtType.SIMULATOR) return@map null   // never persisted; guard against stale data
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

    /** Geschwindigkeit aus dem GPS, wenn das Fahrzeug PID 010D nicht liefert. Standard aus:
     *  die Standortberechtigung wird erst angefragt, wenn der Nutzer das Feature einschaltet. */
    val useGpsSpeed: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_USE_GPS_SPEED] ?: false
    }

    suspend fun setUseGpsSpeed(enabled: Boolean) {
        dataStore.edit { it[KEY_USE_GPS_SPEED] = enabled }
    }

    /** Ob die Hintergrund-Standort-Eskalation dem Nutzer schon einmal angeboten wurde. Verhindert,
     *  dass der System-Dialog bei jedem Settings-Besuch erneut aufpoppt, solange die Berechtigung
     *  nicht erteilt ist. */
    val backgroundLocationPrompted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_BACKGROUND_LOCATION_PROMPTED] ?: false
    }

    suspend fun setBackgroundLocationPrompted(value: Boolean) {
        dataStore.edit { it[KEY_BACKGROUND_LOCATION_PROMPTED] = value }
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

    // ── Getriebe-Einstellungen (virtuelle Ganganzeige) ────────────────────────

    val wheelCircumferenceMm: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_WHEEL_CIRCUMFERENCE_MM] ?: DEFAULT_WHEEL_CIRCUMFERENCE_MM
    }

    suspend fun setWheelCircumferenceMm(value: Float) {
        dataStore.edit { it[KEY_WHEEL_CIRCUMFERENCE_MM] = value }
    }

    val primaryRatio: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_PRIMARY_RATIO] ?: DEFAULT_PRIMARY_RATIO
    }

    suspend fun setPrimaryRatio(value: Float) {
        dataStore.edit { it[KEY_PRIMARY_RATIO] = value }
    }

    val secondaryRatio: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_SECONDARY_RATIO] ?: DEFAULT_SECONDARY_RATIO
    }

    suspend fun setSecondaryRatio(value: Float) {
        dataStore.edit { it[KEY_SECONDARY_RATIO] = value }
    }

    /** Gesamt-Getriebeuebersetzung je Gang (Index 0 = 1. Gang ... Index 5 = 6. Gang). Sechs
     *  einzelne Keys statt einer CSV-kodierten Liste, damit jedes Settings-Eingabefeld
     *  unabhaengig von den anderen fuenf geschrieben werden kann. */
    val gearRatios: Flow<List<Float>> = dataStore.data.map { prefs ->
        GEAR_RATIO_KEYS.mapIndexed { i, key -> prefs[key] ?: DEFAULT_GEAR_RATIOS[i] }
    }

    suspend fun setGearRatio(index: Int, value: Float) {
        require(index in 0..5) { "index must be 0..5" }
        dataStore.edit { it[GEAR_RATIO_KEYS[index]] = value }
    }
}
