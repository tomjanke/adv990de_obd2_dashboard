// app/src/main/java/de/adv990de/obd2/data/AppPreferences.kt
package de.adv990de.obd2.data

import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.obd.PidRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Eine beschaedigte Einstellungsdatei wird durch leere Einstellungen ersetzt (= alle Standardwerte).
 * Ohne diesen Handler wirft der erste Lesezugriff — schon beim Theme in `MainActivity` — und die App
 * stuerzte bei jedem Start erneut ab, bis der Nutzer die App-Daten loescht. Verlust der Einstellungen
 * ist der kleinere Schaden. Als Funktion herausgezogen, damit der Test denselben Handler benutzt.
 */
internal fun appPreferencesCorruptionHandler(): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { emptyPreferences() }

// Top-level extension — single DataStore instance per Application context
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_prefs",
    corruptionHandler = appPreferencesCorruptionHandler(),
)

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
        /** Die Abwahl (seit 1.25.2). Die Namen beider Schluessel sind gespeicherter Zustand — nie umbenennen. */
        private val KEY_UNLOGGED_PIDS  = stringSetPreferencesKey("unlogged_pid_ids")
        /** Nur noch zum Lesen: die Positivliste bis 1.25.1, s. [loggedPidIds]. */
        private val KEY_LOGGED_PIDS    = stringSetPreferencesKey("logged_pid_ids")
        private val KEY_HIDE_NAV_ON_DRIVE = booleanPreferencesKey("hide_nav_on_drive")
        private val KEY_CRASH_REPORTS_ENABLED = booleanPreferencesKey("crash_reports_enabled")
        private val KEY_USE_GPS_SPEED = booleanPreferencesKey("use_gps_speed")
        private val KEY_BACKGROUND_LOCATION_PROMPTED = booleanPreferencesKey("background_location_prompted")
        private val KEY_WEATHER_LOCATION_PROMPTED = booleanPreferencesKey("weather_location_prompted")
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
        private val KEY_OBD_WATCHDOG_TIMEOUT_SECONDS = intPreferencesKey("obd_watchdog_timeout_seconds")
        private val KEY_RECONNECT_BASE_DELAY_SECONDS = intPreferencesKey("reconnect_base_delay_seconds")
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
        // Deutlich ueber normalem Timeout-Rauschen (ein einzelner PID-Timeout ist bereits 5s),
        // aber kurz genug, dass der Fahrer nicht lange auf eingefrorene Werte schaut.
        const val DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS = 20
        // Feste Auswahl in den Einstellungen (ChoiceSetting) statt freier Zahleneingabe. Die
        // kleinste Option liegt deutlich ueber normalem Timeout-Rauschen: bei wenigen Sekunden
        // wuerde der Watchdog schon dabei staendig einen Reconnect erzwingen. Der Default muss
        // in der Liste stehen — sonst wuerde eine unberuehrte Installation ihren eigenen Default
        // auf einen anderen Wert umdeuten (ChoiceOptionsTest). Aufsteigend sortiert: die Segmente
        // werden in Listenreihenfolge angezeigt.
        val OBD_WATCHDOG_TIMEOUT_OPTIONS_SECONDS: List<Int> = listOf(10, 20, 30, 60, 120)
        // Backlog-Cleanup (Sweep 2026-09-28/29): vorher hartcodiert in ConnectionCoordinator.kt
        // als RECONNECT_DELAYS = listOf(3_000L, 6_000L, ...). 3s als Basis reproduziert exakt
        // diese bisherigen Werte (3, 6, 12, 24, 48s, s. ConnectionCoordinator.reconnectDelays()).
        const val DEFAULT_RECONNECT_BASE_DELAY_SECONDS = 3
        // Feste Auswahl wie bei den Watchdog-Optionen oben. Die kleinste Option ist > 0: bei 0s
        // wuerde der erste Versuch ohne jede Wartezeit sofort feuern, noch bevor der Adapter
        // eine reale Chance hatte, sich vom vorherigen Abbruch zu erholen.
        val RECONNECT_BASE_DELAY_OPTIONS_SECONDS: List<Int> = listOf(3, 10, 30)
    }

    /**
     * Alle Lesezugriffe laufen hierueber: Ein E/A-Fehler beim Lesen (voller oder defekter Speicher)
     * liefert leere Einstellungen = Standardwerte statt einer Ausnahme im Flow, die bis in die UI
     * durchschluege. Alles andere (Programmierfehler) wird weitergereicht. Vor den Flows deklariert,
     * weil Kotlin Property-Initialisierer in Reihenfolge ausfuehrt.
     */
    private val data: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    suspend fun persistDevice(device: ScannedDevice) {
        if (device.device == null) return   // simulator – nothing to persist
        dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ADDRESS] = device.address
            prefs[KEY_DEVICE_NAME]    = device.name
            prefs[KEY_DEVICE_TYPE]    = device.type.name
        }
    }

    val lastDevice: Flow<ScannedDevice?> = data.map { prefs ->
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

    val showAllBtDevices: Flow<Boolean> = data.map { prefs ->
        prefs[KEY_SHOW_ALL_BT_DEVICES] ?: false
    }

    suspend fun setShowAllBtDevices(enabled: Boolean) {
        dataStore.edit { it[KEY_SHOW_ALL_BT_DEVICES] = enabled }
    }

    // ── Recording preferences ─────────────────────────────────────────────────

    val logOnlyDtcSessions: Flow<Boolean> = data.map { prefs ->
        prefs[KEY_LOG_ONLY_DTC] ?: false
    }

    val logOnlyDiscoveredPids: Flow<Boolean> = data.map { prefs ->
        prefs[KEY_LOG_ONLY_DISC] ?: false
    }

    /**
     * Die aufzuzeichnenden PIDs: alle bekannten ausser den vom Nutzer **abgewaehlten**. Gespeichert wird die Abwahl
     * ([KEY_UNLOGGED_PIDS]), nicht die Auswahl: Eine Positivliste (bis 1.25.1) laesst jeden PID aus, den es beim Speichern noch nicht gab —
     * wer einmal einen Haken entfernt hatte, zeichnete spaeter ergaenzte PIDs nie auf, ohne es zu merken. Unbekannte IDs in der
     * Abwahl wirken nicht (sie liegen ausserhalb von [ALL_PID_IDS]).
     */
    val loggedPidIds: Flow<Set<String>> = data.map { prefs -> ALL_PID_IDS - unloggedPidIds(prefs) }

    /**
     * Die Abwahl. Fehlt der neue Schluessel, gilt die Positivliste einer frueheren Version ([KEY_LOGGED_PIDS]): was darin fehlt, ist
     * abgewaehlt — der Nutzer sieht seine Auswahl unveraendert, und PIDs, die schon damals fehlten, bleiben weg, wie sie es
     * bisher taten. Der alte Schluessel wird beim ersten Schreiben abgeloest ([writeUnloggedPids]).
     */
    private fun unloggedPidIds(prefs: Preferences): Set<String> =
        prefs[KEY_UNLOGGED_PIDS]
            ?: prefs[KEY_LOGGED_PIDS]?.let { legacyLogged -> ALL_PID_IDS - legacyLogged }
            ?: emptySet()

    private fun MutablePreferences.writeUnloggedPids(unlogged: Set<String>) {
        this[KEY_UNLOGGED_PIDS] = unlogged
        remove(KEY_LOGGED_PIDS)
    }

    suspend fun setLogOnlyDtcSessions(enabled: Boolean) {
        dataStore.edit { it[KEY_LOG_ONLY_DTC] = enabled }
    }

    suspend fun setLogOnlyDiscoveredPids(enabled: Boolean) {
        dataStore.edit { it[KEY_LOG_ONLY_DISC] = enabled }
    }

    suspend fun toggleLoggedPid(id: String) {
        dataStore.edit { prefs ->
            val unlogged = unloggedPidIds(prefs)
            prefs.writeUnloggedPids(if (id in unlogged) unlogged - id else unlogged + id)
        }
    }

    suspend fun setLoggedPids(ids: Set<String>) {
        dataStore.edit { prefs -> prefs.writeUnloggedPids(ALL_PID_IDS - ids) }
    }

    // ── Drive state preferences ───────────────────────────────────────────────

    val hideNavOnDrive: Flow<Boolean> = data.map { prefs ->
        prefs[KEY_HIDE_NAV_ON_DRIVE] ?: false
    }

    suspend fun setHideNavOnDrive(enabled: Boolean) {
        dataStore.edit { it[KEY_HIDE_NAV_ON_DRIVE] = enabled }
    }

    /**
     * Ob Absturzberichte an Sentry gehen duerfen (nur wirksam, wenn die Version mit DSN gebaut ist, s.
     * `CrashReportingController`). Standard an: Eine Version mit DSN meldete schon vor diesem Schalter — er aendert
     * das Verhalten nur fuer den, der ihn ausschaltet.
     */
    val crashReportsEnabled: Flow<Boolean> = data.map { prefs ->
        prefs[KEY_CRASH_REPORTS_ENABLED] ?: true
    }

    suspend fun setCrashReportsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_CRASH_REPORTS_ENABLED] = enabled }
    }

    /** Geschwindigkeit aus dem GPS, wenn das Fahrzeug PID 010D nicht liefert. Standard aus:
     *  die Standortberechtigung wird erst angefragt, wenn der Nutzer das Feature einschaltet. */
    val useGpsSpeed: Flow<Boolean> = data.map { prefs ->
        prefs[KEY_USE_GPS_SPEED] ?: false
    }.distinctUntilChanged() // DataStore emits on every write; ReadingsPipeline restarts the GPS listener on each emission (DAT-13)

    suspend fun setUseGpsSpeed(enabled: Boolean) {
        dataStore.edit { it[KEY_USE_GPS_SPEED] = enabled }
    }

    /** Ob die Hintergrund-Standort-Eskalation dem Nutzer schon einmal angeboten wurde. Verhindert,
     *  dass der System-Dialog bei jedem Settings-Besuch erneut aufpoppt, solange die Berechtigung
     *  nicht erteilt ist. */
    val backgroundLocationPrompted: Flow<Boolean> = data.map { prefs ->
        prefs[KEY_BACKGROUND_LOCATION_PROMPTED] ?: false
    }

    suspend fun setBackgroundLocationPrompted(value: Boolean) {
        dataStore.edit { it[KEY_BACKGROUND_LOCATION_PROMPTED] = value }
    }

    /** Ob die Standortanfrage fuer die Aussentemperatur (hideNavOnDrive-Schalter) schon einmal
     *  gezeigt wurde. Verhindert, wie [backgroundLocationPrompted], dass der System-Dialog bei
     *  jedem Settings-Besuch/jeder Rotation erneut aufpoppt, solange weder FINE noch COARSE
     *  erteilt ist. */
    val weatherLocationPrompted: Flow<Boolean> = data.map { prefs ->
        prefs[KEY_WEATHER_LOCATION_PROMPTED] ?: false
    }

    suspend fun setWeatherLocationPrompted(value: Boolean) {
        dataStore.edit { it[KEY_WEATHER_LOCATION_PROMPTED] = value }
    }

    // ── Display preferences ───────────────────────────────────────────────────

    val useImperial: Flow<Boolean> = data.map { prefs ->
        prefs[KEY_USE_IMPERIAL] ?: false
    }

    suspend fun setUseImperial(enabled: Boolean) {
        dataStore.edit { it[KEY_USE_IMPERIAL] = enabled }
    }

    val theme: Flow<String> = data.map { prefs ->
        prefs[KEY_THEME] ?: THEME_SYSTEM
    }

    suspend fun setTheme(value: String) {
        dataStore.edit { it[KEY_THEME] = value }
    }

    // ── Dashboard preferences ─────────────────────────────────────────────────

    val rpmBlinkThreshold: Flow<Int> = data.map { prefs ->
        prefs[KEY_RPM_BLINK_THRESHOLD] ?: 0
    }

    suspend fun setRpmBlinkThreshold(value: Int) {
        dataStore.edit { it[KEY_RPM_BLINK_THRESHOLD] = value }
    }

    val rpmDynamicThreshold: Flow<Boolean> = data.map { prefs ->
        prefs[KEY_RPM_DYNAMIC_THRESHOLD] ?: false
    }

    suspend fun setRpmDynamicThreshold(enabled: Boolean) {
        dataStore.edit { it[KEY_RPM_DYNAMIC_THRESHOLD] = enabled }
    }

    val slotPids: Flow<List<String>> = data.map { prefs ->
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

    val wheelCircumferenceMm: Flow<Float> = data.map { prefs ->
        prefs[KEY_WHEEL_CIRCUMFERENCE_MM] ?: DEFAULT_WHEEL_CIRCUMFERENCE_MM
    }.distinctUntilChanged() // DataStore emits on every write; suppress value-equal emissions

    suspend fun setWheelCircumferenceMm(value: Float) {
        dataStore.edit { it[KEY_WHEEL_CIRCUMFERENCE_MM] = value }
    }

    val primaryRatio: Flow<Float> = data.map { prefs ->
        prefs[KEY_PRIMARY_RATIO] ?: DEFAULT_PRIMARY_RATIO
    }.distinctUntilChanged()

    suspend fun setPrimaryRatio(value: Float) {
        dataStore.edit { it[KEY_PRIMARY_RATIO] = value }
    }

    val secondaryRatio: Flow<Float> = data.map { prefs ->
        prefs[KEY_SECONDARY_RATIO] ?: DEFAULT_SECONDARY_RATIO
    }.distinctUntilChanged()

    suspend fun setSecondaryRatio(value: Float) {
        dataStore.edit { it[KEY_SECONDARY_RATIO] = value }
    }

    /** Gesamt-Getriebeuebersetzung je Gang (Index 0 = 1. Gang ... Index 5 = 6. Gang). Sechs
     *  einzelne Keys statt einer CSV-kodierten Liste, damit jedes Settings-Eingabefeld
     *  unabhaengig von den anderen fuenf geschrieben werden kann. */
    val gearRatios: Flow<List<Float>> = data.map { prefs ->
        GEAR_RATIO_KEYS.mapIndexed { i, key -> prefs[key] ?: DEFAULT_GEAR_RATIOS[i] }
    }.distinctUntilChanged()

    suspend fun setGearRatio(index: Int, value: Float) {
        require(index in 0..5) { "index must be 0..5" }
        dataStore.edit { it[GEAR_RATIO_KEYS[index]] = value }
    }

    // ── OBD-Watchdog (erzwungener Reconnect bei eingefrorenen Sensorwerten) ───

    /** Immer ein Wert aus [OBD_WATCHDOG_TIMEOUT_OPTIONS_SECONDS]: Aeltere Versionen nahmen eine
     *  freie Zahl, die Einstellungen bieten jetzt nur noch die feste Auswahl. Ein gespeicherter
     *  Wert ausserhalb der Liste wird beim LESEN auf die naechste Option gesetzt ([snapToOption]) —
     *  ohne Migration und ohne Zurueckschreiben. Dadurch sehen Watchdog, Pipeline und
     *  Einstellungs-UI immer dieselbe Zahl. */
    val obdWatchdogTimeoutSeconds: Flow<Int> = data.map { prefs ->
        snapToOption(
            prefs[KEY_OBD_WATCHDOG_TIMEOUT_SECONDS] ?: DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS,
            OBD_WATCHDOG_TIMEOUT_OPTIONS_SECONDS
        )
    }.distinctUntilChanged()

    /** Speichert [value] unveraendert — das Einstellungs-UI bietet nur Listenwerte an; ein anderer
     *  Wert wuerde ohnehin beim Lesen auf die naechste Option gesetzt. */
    suspend fun setObdWatchdogTimeoutSeconds(value: Int) {
        dataStore.edit { it[KEY_OBD_WATCHDOG_TIMEOUT_SECONDS] = value }
    }

    // ── Reconnect-Basis-Verzoegerung (Backlog-Cleanup, Sweep 2026-09-28/29) ───

    /** Immer ein Wert aus [RECONNECT_BASE_DELAY_OPTIONS_SECONDS], gleiche Begruendung wie bei
     *  [obdWatchdogTimeoutSeconds]. */
    val reconnectBaseDelaySeconds: Flow<Int> = data.map { prefs ->
        snapToOption(
            prefs[KEY_RECONNECT_BASE_DELAY_SECONDS] ?: DEFAULT_RECONNECT_BASE_DELAY_SECONDS,
            RECONNECT_BASE_DELAY_OPTIONS_SECONDS
        )
    }.distinctUntilChanged()

    suspend fun setReconnectBaseDelaySeconds(value: Int) {
        dataStore.edit { it[KEY_RECONNECT_BASE_DELAY_SECONDS] = value }
    }
}
