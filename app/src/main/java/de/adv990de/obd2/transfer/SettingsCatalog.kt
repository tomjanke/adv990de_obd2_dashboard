package de.adv990de.obd2.transfer

import de.adv990de.obd2.alarm.AlarmSettings
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.fuel.FuelCapacityLimits
import de.adv990de.obd2.fuel.FuelRangeLimits
import de.adv990de.obd2.fuel.FuelReserveLimits
import de.adv990de.obd2.logbook.MAINTENANCE_TYPES
import de.adv990de.obd2.logbook.MaintenanceIntervalLimits
import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.ui.settings.GearLimits

/** Die Gruppen der Einstellungen, wie die Vorschau sie zaehlt. */
enum class SettingsGroup { DISPLAY, DASHBOARD, CONNECTION, ALARMS, VEHICLE_FUEL, MAINTENANCE, GEAR }

/** Welche Werte ein Schluessel annehmen darf. */
sealed interface SettingKind {
    data object Flag : SettingKind
    data class IntOf(val options: List<Int>) : SettingKind
    data class IntIn(val range: IntRange, val zeroMeansUnset: Boolean = false) : SettingKind
    data class FloatIn(val range: ClosedFloatingPointRange<Float>, val zeroAllowed: Boolean = false) : SettingKind
    data class Choice(val values: List<String>) : SettingKind
    data object SlotPids : SettingKind
}

class SettingSpec(val key: String, val group: SettingsGroup, val kind: SettingKind)

enum class SettingReject { WRONG_TYPE, OUT_OF_RANGE, NOT_AN_OPTION, UNKNOWN_VALUE }

sealed interface SettingCheck {
    data class Valid(val value: Any) : SettingCheck
    data class Invalid(val reason: SettingReject) : SettingCheck
}

/**
 * Welche Einstellungen ein Paket traegt (`settings.json`), welche Schluessel Zaehler sind (`counters.json`) und welche bewusst nicht mitgehen (D8). **Die einzige
 * Stelle**; `PreferenceKeysExportGuardTest` haelt sie gleich den Schluesseln im Quelltext. Die Schluessel sind persistierter Zustand — nie umbenennen. Die Grenzen
 * kommen aus den Konstanten der App, nicht abgeschrieben.
 */
object SettingsCatalog {

    private fun flag(key: String, group: SettingsGroup) = SettingSpec(key, group, SettingKind.Flag)

    private fun maintenanceKey(typeName: String) = "maintenance_interval_${typeName.lowercase()}_km"

    /** Die Einstellungen in der Reihenfolge der Datei. */
    val exported: List<SettingSpec> = listOf(
        SettingSpec(
            "theme", SettingsGroup.DISPLAY,
            SettingKind.Choice(
                listOf(
                    AppPreferences.THEME_SYSTEM, AppPreferences.THEME_LIGHT, AppPreferences.THEME_DARK,
                    AppPreferences.THEME_BLUE, AppPreferences.THEME_AMBER, AppPreferences.THEME_GREEN,
                ),
            ),
        ),
        flag("use_imperial", SettingsGroup.DISPLAY),
        flag("hide_nav_on_drive", SettingsGroup.DISPLAY),
        flag("use_gps_speed", SettingsGroup.DISPLAY),
        flag("rpm_dynamic_threshold", SettingsGroup.DISPLAY),
        // Die Spanne des Schiebereglers in den Einstellungen (RpmThresholdSection: 0f..10000f).
        SettingSpec("rpm_blink_threshold", SettingsGroup.DISPLAY, SettingKind.IntIn(0..10_000)),
        SettingSpec("slot_pids", SettingsGroup.DASHBOARD, SettingKind.SlotPids),
        flag("show_all_bt_devices", SettingsGroup.CONNECTION),
        flag("log_only_dtc_sessions", SettingsGroup.CONNECTION),
        SettingSpec("obd_watchdog_timeout_seconds", SettingsGroup.CONNECTION, SettingKind.IntOf(AppPreferences.OBD_WATCHDOG_TIMEOUT_OPTIONS_SECONDS)),
        SettingSpec("reconnect_base_delay_seconds", SettingsGroup.CONNECTION, SettingKind.IntOf(AppPreferences.RECONNECT_BASE_DELAY_OPTIONS_SECONDS)),
        flag("alarm_voltage_enabled", SettingsGroup.ALARMS),
        flag("alarm_coolant_enabled", SettingsGroup.ALARMS),
        flag("alarm_faults_enabled", SettingsGroup.ALARMS),
        SettingSpec("alarm_voltage_notice_dv", SettingsGroup.ALARMS, SettingKind.IntOf(AlarmSettings.VOLTAGE_NOTICE_OPTIONS_DV)),
        SettingSpec("alarm_voltage_warning_dv", SettingsGroup.ALARMS, SettingKind.IntOf(AlarmSettings.VOLTAGE_WARNING_OPTIONS_DV)),
        SettingSpec("alarm_coolant_notice_c", SettingsGroup.ALARMS, SettingKind.IntOf(AlarmSettings.COOLANT_NOTICE_OPTIONS_C)),
        SettingSpec("alarm_coolant_warning_c", SettingsGroup.ALARMS, SettingKind.IntOf(AlarmSettings.COOLANT_WARNING_OPTIONS_C)),
        SettingSpec("fuel_range_full_km", SettingsGroup.VEHICLE_FUEL, SettingKind.IntIn(FuelRangeLimits.MIN_KM..FuelRangeLimits.MAX_KM, zeroMeansUnset = true)),
        SettingSpec("fuel_reserve_km", SettingsGroup.VEHICLE_FUEL, SettingKind.IntIn(FuelReserveLimits.MIN_KM..FuelReserveLimits.MAX_KM, zeroMeansUnset = true)),
        SettingSpec("fuel_tank_capacity_dl", SettingsGroup.VEHICLE_FUEL, SettingKind.IntIn(FuelCapacityLimits.MIN_DL..FuelCapacityLimits.MAX_DL, zeroMeansUnset = true)),
    ) + MAINTENANCE_TYPES.map {
        SettingSpec(
            maintenanceKey(it.name), SettingsGroup.MAINTENANCE,
            SettingKind.IntIn(MaintenanceIntervalLimits.MIN_KM..MaintenanceIntervalLimits.MAX_KM, zeroMeansUnset = true),
        )
    } + listOf(
        SettingSpec("wheel_circumference_mm", SettingsGroup.GEAR, SettingKind.FloatIn(GearLimits.WHEEL_CIRCUMFERENCE_MM)),
        SettingSpec("primary_ratio", SettingsGroup.GEAR, SettingKind.FloatIn(GearLimits.PRIMARY_RATIO)),
        SettingSpec("secondary_ratio", SettingsGroup.GEAR, SettingKind.FloatIn(GearLimits.SECONDARY_RATIO)),
    ) + (1..6).map { SettingSpec("gear_ratio_$it", SettingsGroup.GEAR, SettingKind.FloatIn(GearLimits.GEAR_RATIO, zeroAllowed = true)) }

    /** Die Zaehler: gehoeren in `counters.json`, nicht in die Einstellungen. */
    val counterKeys: List<String> = listOf("fuel_km_since_fill", "logbook_gps_anchor_km", "logbook_gps_km")

    /** Bewusst nicht im Paket (D8), je mit dem Grund in einem Satz. */
    val excluded: Map<String, String> = mapOf(
        "device_address" to "Die Kopplung mit dem Adapter ist personenbezogen und gilt nur fuer dieses Telefon.",
        "device_name" to "Der Name des Adapters gehoert zur Kopplung und geht nie mit.",
        "device_type" to "Die Art des Adapters gehoert zur Kopplung und geht nie mit.",
        "crash_reports_enabled" to "Ob Absturzberichte gesendet werden, entscheidet jede Installation selbst.",
        "weather_location_prompted" to "Das Merkzeichen \"schon gefragt\": die Berechtigung muss am neuen Geraet neu erteilt werden.",
        "fuel_range_learned_km" to "Nur ein Zwischenspeicher fuer das erste Bild; er entsteht aus dem Logbuch neu.",
    )

    private val byKey: Map<String, SettingSpec> = exported.associateBy { it.key }

    fun spec(key: String): SettingSpec? = byKey[key]

    private val knownPids: Set<String> = PidRegistry.all.keys + DerivedPids.TERRAIN_ID

    /**
     * Prueft einen **rohen** Wert aus dem JSON (`Boolean`, `Int`, `Long`, `Double` oder `String`, wie `org.json` sie liefert). Gueltig = der Wert im Zieltyp
     * (`Boolean`, `Int`, `Float`, `String`). **Nie raten:** ein ungueltiger Wert wird nicht gerundet, nicht auf den Standard gesetzt und nicht abgeschnitten.
     */
    fun validate(spec: SettingSpec, raw: Any?): SettingCheck = when (val kind = spec.kind) {
        SettingKind.Flag -> if (raw is Boolean) valid(raw) else invalid(SettingReject.WRONG_TYPE)
        is SettingKind.IntOf -> whole(raw)?.let { n ->
            if (kind.options.any { it.toLong() == n }) valid(n.toInt()) else invalid(SettingReject.NOT_AN_OPTION)
        } ?: invalid(SettingReject.WRONG_TYPE)
        is SettingKind.IntIn -> whole(raw)?.let { n ->
            if ((kind.zeroMeansUnset && n == 0L) || n in kind.range.first.toLong()..kind.range.last.toLong()) valid(n.toInt())
            else invalid(SettingReject.OUT_OF_RANGE)
        } ?: invalid(SettingReject.WRONG_TYPE)
        is SettingKind.FloatIn -> if (raw !is Number) invalid(SettingReject.WRONG_TYPE) else {
            val f = raw.toDouble().toFloat()
            if (f.isFinite() && ((kind.zeroAllowed && f == 0f) || f in kind.range)) valid(f) else invalid(SettingReject.OUT_OF_RANGE)
        }
        is SettingKind.Choice -> when {
            raw !is String -> invalid(SettingReject.WRONG_TYPE)
            raw in kind.values -> valid(raw)
            else -> invalid(SettingReject.UNKNOWN_VALUE)
        }
        SettingKind.SlotPids -> if (raw !is String) invalid(SettingReject.WRONG_TYPE) else {
            val parts = raw.split(",", limit = Int.MAX_VALUE).map { it.trim() }
            when {
                parts.size != 4 -> invalid(SettingReject.OUT_OF_RANGE)
                parts.any { it.isNotEmpty() && it !in knownPids } -> invalid(SettingReject.UNKNOWN_VALUE)
                else -> valid(parts.joinToString(","))
            }
        }
    }

    private fun valid(value: Any): SettingCheck = SettingCheck.Valid(value)

    private fun invalid(reason: SettingReject): SettingCheck = SettingCheck.Invalid(reason)

    /** Eine ganze Zahl als `Long`: `Int`, `Long`, oder ein endliches `Double` ohne Nachkommastelle (`12.0` gilt, `12.5` nicht); sonst `null` (falscher Typ). */
    private fun whole(raw: Any?): Long? = when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        is Short -> raw.toLong()
        is Byte -> raw.toLong()
        // ein endliches, ganzzahliges Double jenseits von +-9e15 wird auf diese Grenze gesetzt: es liegt in jeder Spanne und bei jeder Auswahl ausserhalb (OUT_OF_RANGE/NOT_AN_OPTION), nicht "falscher Typ"
        is Number -> raw.toDouble().takeIf { it.isFinite() && it == Math.rint(it) }?.coerceIn(-9.0e15, 9.0e15)?.toLong()
        else -> null
    }
}
