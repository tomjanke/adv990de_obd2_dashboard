package de.adv990de.obd2.transfer

import de.adv990de.obd2.alarm.AlarmSettings
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.logbook.MAINTENANCE_TYPES
import de.adv990de.obd2.obd.DerivedPids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Der Einstellungs-Katalog: welche 36 Einstellungen ein Paket tragen und welche Werte als gueltig gelten (nie raten, nie runden). */
class SettingsCatalogTest {

    private fun spec(key: String) = SettingsCatalog.spec(key) ?: error("not in the catalog: $key")

    private fun check(key: String, raw: Any?) = SettingsCatalog.validate(spec(key), raw)

    private fun assertValid(expected: Any, key: String, raw: Any?) = assertEquals(SettingCheck.Valid(expected), check(key, raw))

    private fun assertInvalid(reason: SettingReject, key: String, raw: Any?) = assertEquals(SettingCheck.Invalid(reason), check(key, raw))

    private val maintenanceKeys = MAINTENANCE_TYPES.map { "maintenance_interval_${it.name.lowercase()}_km" }

    @Test
    fun `a whole number far beyond the range is out of range, not a wrong type`() {
        assertInvalid(SettingReject.OUT_OF_RANGE, "rpm_blink_threshold", 1e300)
        assertInvalid(SettingReject.OUT_OF_RANGE, "rpm_blink_threshold", -1e16)
        assertInvalid(SettingReject.OUT_OF_RANGE, "fuel_range_full_km", 1e16)
        assertInvalid(SettingReject.NOT_AN_OPTION, "obd_watchdog_timeout_seconds", 1e300)
        assertInvalid(SettingReject.WRONG_TYPE, "rpm_blink_threshold", 12.5)
        assertInvalid(SettingReject.WRONG_TYPE, "rpm_blink_threshold", Double.NaN)
        assertInvalid(SettingReject.WRONG_TYPE, "rpm_blink_threshold", Double.POSITIVE_INFINITY)
    }

    @Test
    fun `the catalog holds the 36 settings in the order of the file`() {
        val expected = listOf(
            "theme", "use_imperial", "hide_nav_on_drive", "use_gps_speed", "rpm_dynamic_threshold", "rpm_blink_threshold",
            "slot_pids",
            "show_all_bt_devices", "log_only_dtc_sessions", "obd_watchdog_timeout_seconds", "reconnect_base_delay_seconds",
            "alarm_voltage_enabled", "alarm_coolant_enabled", "alarm_faults_enabled",
            "alarm_voltage_notice_dv", "alarm_voltage_warning_dv", "alarm_coolant_notice_c", "alarm_coolant_warning_c",
            "fuel_range_full_km", "fuel_reserve_km", "fuel_tank_capacity_dl",
        ) + maintenanceKeys + listOf(
            "wheel_circumference_mm", "primary_ratio", "secondary_ratio",
            "gear_ratio_1", "gear_ratio_2", "gear_ratio_3", "gear_ratio_4", "gear_ratio_5", "gear_ratio_6",
        )
        assertEquals(36, expected.size)
        assertEquals(expected, SettingsCatalog.exported.map { it.key })
        assertEquals(6, maintenanceKeys.size)
        assertEquals(36, SettingsCatalog.exported.map { it.key }.toSet().size)
    }

    @Test
    fun `the groups cover the catalog`() {
        val byGroup = SettingsCatalog.exported.groupBy { it.group }
        assertEquals(SettingsGroup.entries.toSet(), byGroup.keys)
        assertEquals(6, byGroup.getValue(SettingsGroup.MAINTENANCE).size)
        assertEquals(9, byGroup.getValue(SettingsGroup.GEAR).size)
        assertEquals(7, byGroup.getValue(SettingsGroup.ALARMS).size)
    }

    @Test
    fun `the counters and the exclusions are named, disjoint and explained`() {
        assertEquals(listOf("fuel_km_since_fill", "logbook_gps_anchor_km", "logbook_gps_km"), SettingsCatalog.counterKeys)
        assertEquals(
            setOf(
                "device_address", "device_name", "device_type", "crash_reports_enabled",
                "weather_location_prompted", "fuel_range_learned_km",
            ),
            SettingsCatalog.excluded.keys,
        )
        assertTrue(SettingsCatalog.excluded.values.all { it.isNotBlank() })
        val exported = SettingsCatalog.exported.map { it.key }.toSet()
        assertTrue(exported.intersect(SettingsCatalog.excluded.keys).isEmpty())
        assertTrue(exported.intersect(SettingsCatalog.counterKeys.toSet()).isEmpty())
        assertTrue(SettingsCatalog.counterKeys.toSet().intersect(SettingsCatalog.excluded.keys).isEmpty())
        assertNull(SettingsCatalog.spec("device_address"))
        assertNull(SettingsCatalog.spec("nonsense"))
    }

    @Test
    fun `every default of the app is valid under its kind`() {
        val a = AlarmSettings()
        val defaults = mapOf<String, Any>(
            "theme" to AppPreferences.THEME_SYSTEM,
            "use_imperial" to false, "hide_nav_on_drive" to false, "use_gps_speed" to false, "rpm_dynamic_threshold" to false,
            "rpm_blink_threshold" to 0,
            "slot_pids" to AppPreferences.DEFAULT_SLOT_PIDS.joinToString(","),
            "show_all_bt_devices" to false, "log_only_dtc_sessions" to false,
            "obd_watchdog_timeout_seconds" to AppPreferences.DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS,
            "reconnect_base_delay_seconds" to AppPreferences.DEFAULT_RECONNECT_BASE_DELAY_SECONDS,
            "alarm_voltage_enabled" to a.voltageEnabled, "alarm_coolant_enabled" to a.coolantEnabled, "alarm_faults_enabled" to a.faultsEnabled,
            "alarm_voltage_notice_dv" to a.voltageNoticeDeciV, "alarm_voltage_warning_dv" to a.voltageWarningDeciV,
            "alarm_coolant_notice_c" to a.coolantNoticeC, "alarm_coolant_warning_c" to a.coolantWarningC,
            "fuel_range_full_km" to 0, "fuel_reserve_km" to 0, "fuel_tank_capacity_dl" to 0,
            "wheel_circumference_mm" to AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM,
            "primary_ratio" to AppPreferences.DEFAULT_PRIMARY_RATIO,
            "secondary_ratio" to AppPreferences.DEFAULT_SECONDARY_RATIO,
        ) + maintenanceKeys.associateWith { 0 } +
            AppPreferences.DEFAULT_GEAR_RATIOS.mapIndexed { i, v -> "gear_ratio_${i + 1}" to v }
        assertEquals(SettingsCatalog.exported.map { it.key }.toSet(), defaults.keys)
        for ((key, value) in defaults) assertValid(value, key, value)
    }

    @Test
    fun `the options of the app are the options of the catalog`() {
        assertEquals(SettingKind.IntOf(AppPreferences.OBD_WATCHDOG_TIMEOUT_OPTIONS_SECONDS), spec("obd_watchdog_timeout_seconds").kind)
        assertEquals(SettingKind.IntOf(AppPreferences.RECONNECT_BASE_DELAY_OPTIONS_SECONDS), spec("reconnect_base_delay_seconds").kind)
        assertEquals(SettingKind.IntOf(AlarmSettings.VOLTAGE_NOTICE_OPTIONS_DV), spec("alarm_voltage_notice_dv").kind)
        assertEquals(SettingKind.IntOf(AlarmSettings.VOLTAGE_WARNING_OPTIONS_DV), spec("alarm_voltage_warning_dv").kind)
        assertEquals(SettingKind.IntOf(AlarmSettings.COOLANT_NOTICE_OPTIONS_C), spec("alarm_coolant_notice_c").kind)
        assertEquals(SettingKind.IntOf(AlarmSettings.COOLANT_WARNING_OPTIONS_C), spec("alarm_coolant_warning_c").kind)
    }

    // ── Flag ──────────────────────────────────────────────────────────────

    @Test
    fun `a flag is only a boolean`() {
        assertValid(true, "use_imperial", true)
        assertValid(false, "use_imperial", false)
        assertInvalid(SettingReject.WRONG_TYPE, "use_imperial", 1)
        assertInvalid(SettingReject.WRONG_TYPE, "use_imperial", "true")
        assertInvalid(SettingReject.WRONG_TYPE, "use_imperial", null)
    }

    // ── Choice ────────────────────────────────────────────────────────────

    @Test
    fun `the theme is one of the six names`() {
        for (name in listOf("system", "light", "dark", "blue", "amber", "green")) assertValid(name, "theme", name)
        assertInvalid(SettingReject.UNKNOWN_VALUE, "theme", "purple")
        assertInvalid(SettingReject.UNKNOWN_VALUE, "theme", "")
        assertInvalid(SettingReject.UNKNOWN_VALUE, "theme", "Dark")
        assertInvalid(SettingReject.WRONG_TYPE, "theme", 3)
        assertInvalid(SettingReject.WRONG_TYPE, "theme", null)
    }

    // ── IntOf ─────────────────────────────────────────────────────────────

    @Test
    fun `a value from a list of options is valid, anything between is not`() {
        for (v in listOf(10, 20, 30, 60, 120)) assertValid(v, "obd_watchdog_timeout_seconds", v)
        for (v in listOf(9, 11, 25, 45, 119, 121, 0, -20)) assertInvalid(SettingReject.NOT_AN_OPTION, "obd_watchdog_timeout_seconds", v)
        for (v in listOf(123, 125, 128)) assertValid(v, "alarm_voltage_notice_dv", v)
        assertInvalid(SettingReject.NOT_AN_OPTION, "alarm_voltage_notice_dv", 124)
        for (v in listOf(118, 120, 122)) assertValid(v, "alarm_voltage_warning_dv", v)
        assertInvalid(SettingReject.NOT_AN_OPTION, "alarm_voltage_warning_dv", 123)
        for (v in listOf(92, 95, 98)) assertValid(v, "alarm_coolant_notice_c", v)
        assertInvalid(SettingReject.NOT_AN_OPTION, "alarm_coolant_notice_c", 99)
        for (v in listOf(100, 105, 110)) assertValid(v, "alarm_coolant_warning_c", v)
        assertInvalid(SettingReject.NOT_AN_OPTION, "alarm_coolant_warning_c", 111)
        for (v in listOf(3, 10, 30)) assertValid(v, "reconnect_base_delay_seconds", v)
        assertInvalid(SettingReject.NOT_AN_OPTION, "reconnect_base_delay_seconds", 4)
    }

    // ── IntIn ─────────────────────────────────────────────────────────────

    @Test
    fun `the blink threshold runs from 0 to 10000`() {
        assertValid(0, "rpm_blink_threshold", 0)
        assertValid(10_000, "rpm_blink_threshold", 10_000)
        assertValid(7_000, "rpm_blink_threshold", 7_000)
        assertInvalid(SettingReject.OUT_OF_RANGE, "rpm_blink_threshold", -1)
        assertInvalid(SettingReject.OUT_OF_RANGE, "rpm_blink_threshold", 10_001)
    }

    @Test
    fun `the fuel values accept zero for not set and their range at both ends`() {
        val limits = listOf(
            Triple("fuel_range_full_km", 50, 1000),
            Triple("fuel_reserve_km", 5, 500),
            Triple("fuel_tank_capacity_dl", 30, 600),
        )
        for ((key, min, max) in limits) {
            assertValid(0, key, 0)
            assertValid(min, key, min)
            assertValid(max, key, max)
            assertValid((min + max) / 2, key, (min + max) / 2)
            assertInvalid(SettingReject.OUT_OF_RANGE, key, min - 1)
            assertInvalid(SettingReject.OUT_OF_RANGE, key, max + 1)
            assertInvalid(SettingReject.OUT_OF_RANGE, key, -1)
        }
    }

    @Test
    fun `a maintenance interval accepts zero for not set and 50 to 100000`() {
        for (key in maintenanceKeys) {
            assertValid(0, key, 0)
            assertValid(50, key, 50)
            assertValid(100_000, key, 100_000)
            assertInvalid(SettingReject.OUT_OF_RANGE, key, 49)
            assertInvalid(SettingReject.OUT_OF_RANGE, key, 100_001)
            assertInvalid(SettingReject.OUT_OF_RANGE, key, -1)
        }
    }

    @Test
    fun `whole numbers arrive as int, long or an integral double, nothing is rounded`() {
        assertValid(10_000, "rpm_blink_threshold", 10_000.0)
        assertValid(300, "fuel_range_full_km", 300L)
        assertValid(300, "fuel_range_full_km", 300.0)
        assertValid(300, "fuel_range_full_km", java.math.BigDecimal("300.0"))
        assertInvalid(SettingReject.WRONG_TYPE, "fuel_range_full_km", 300.5)
        assertInvalid(SettingReject.WRONG_TYPE, "fuel_range_full_km", Double.NaN)
        assertInvalid(SettingReject.WRONG_TYPE, "fuel_range_full_km", Double.POSITIVE_INFINITY)
        assertInvalid(SettingReject.WRONG_TYPE, "obd_watchdog_timeout_seconds", 20.5)
        assertValid(20, "obd_watchdog_timeout_seconds", 20.0)
        assertInvalid(SettingReject.WRONG_TYPE, "fuel_range_full_km", "300")
        assertInvalid(SettingReject.WRONG_TYPE, "fuel_range_full_km", true)
        assertInvalid(SettingReject.WRONG_TYPE, "fuel_range_full_km", null)
        assertInvalid(SettingReject.OUT_OF_RANGE, "fuel_range_full_km", Long.MAX_VALUE)
        assertInvalid(SettingReject.NOT_AN_OPTION, "obd_watchdog_timeout_seconds", Long.MIN_VALUE)
    }

    @Test
    fun `zero is only valid for an integer range that says so`() {
        // im Katalog hat nur der Blinkgrenzwert eine Spanne mit 0 darin; die Regel selbst pruefen eigene Arten
        val strict = SettingSpec("x", SettingsGroup.DISPLAY, SettingKind.IntIn(5..10))
        val unset = SettingSpec("x", SettingsGroup.DISPLAY, SettingKind.IntIn(5..10, zeroMeansUnset = true))
        assertEquals(SettingCheck.Invalid(SettingReject.OUT_OF_RANGE), SettingsCatalog.validate(strict, 0))
        assertEquals(SettingCheck.Valid(0), SettingsCatalog.validate(unset, 0))
        assertEquals(SettingCheck.Invalid(SettingReject.OUT_OF_RANGE), SettingsCatalog.validate(unset, 4))
        assertEquals(SettingCheck.Valid(5), SettingsCatalog.validate(strict, 5))
    }

    // ── FloatIn ───────────────────────────────────────────────────────────

    @Test
    fun `the gear values keep their range at both ends and as a float`() {
        assertValid(1000f, "wheel_circumference_mm", 1000)
        assertValid(4000f, "wheel_circumference_mm", 4000.0)
        assertValid(2096f, "wheel_circumference_mm", 2096)
        assertInvalid(SettingReject.OUT_OF_RANGE, "wheel_circumference_mm", 999.9)
        assertInvalid(SettingReject.OUT_OF_RANGE, "wheel_circumference_mm", 4000.1)
        assertInvalid(SettingReject.OUT_OF_RANGE, "wheel_circumference_mm", 0)
        assertValid(0.5f, "primary_ratio", 0.5)
        assertValid(10f, "primary_ratio", 10)
        assertInvalid(SettingReject.OUT_OF_RANGE, "primary_ratio", 0.49)
        assertInvalid(SettingReject.OUT_OF_RANGE, "primary_ratio", 10.01)
        assertValid(0.5f, "secondary_ratio", 0.5)
        assertValid(15f, "secondary_ratio", 15)
        assertInvalid(SettingReject.OUT_OF_RANGE, "secondary_ratio", 15.01)
        assertInvalid(SettingReject.OUT_OF_RANGE, "secondary_ratio", 0)
        assertValid(1.9143f, "primary_ratio", 1.9143)
    }

    @Test
    fun `a gear ratio may be zero for gear off, otherwise 0_2 to 8`() {
        for (i in 1..6) {
            val key = "gear_ratio_$i"
            assertValid(0f, key, 0)
            assertValid(0f, key, 0.0)
            assertValid(0.2f, key, 0.2)
            assertValid(8f, key, 8)
            assertValid(2.9167f, key, 2.9167)
            assertInvalid(SettingReject.OUT_OF_RANGE, key, 0.19)
            assertInvalid(SettingReject.OUT_OF_RANGE, key, 8.01)
            assertInvalid(SettingReject.OUT_OF_RANGE, key, -1)
        }
    }

    @Test
    fun `a float needs a finite number`() {
        assertInvalid(SettingReject.OUT_OF_RANGE, "gear_ratio_1", Double.NaN)
        assertInvalid(SettingReject.OUT_OF_RANGE, "gear_ratio_1", Double.POSITIVE_INFINITY)
        assertInvalid(SettingReject.OUT_OF_RANGE, "gear_ratio_1", 1e300)
        assertInvalid(SettingReject.WRONG_TYPE, "gear_ratio_1", "2.0")
        assertInvalid(SettingReject.WRONG_TYPE, "gear_ratio_1", false)
        assertInvalid(SettingReject.WRONG_TYPE, "gear_ratio_1", null)
    }

    // ── SlotPids ──────────────────────────────────────────────────────────

    @Test
    fun `the slots are exactly four entries, each empty or a known id`() {
        assertValid("010C,010D,0105,", "slot_pids", "010C,010D,0105,")
        assertValid("010C,010D,0105,0142", "slot_pids", "010C,010D,0105,0142")
        assertValid(",,,", "slot_pids", ",,,")
        assertValid("TERRAIN,010D,0105,010C", "slot_pids", "TERRAIN,010D,0105,010C")
        assertValid(DerivedPids.TERRAIN_ID + ",010D,0105,010C", "slot_pids", "TERRAIN,010D,0105,010C")
        assertValid("010C,010D,0105,0142", "slot_pids", " 010C , 010D ,0105, 0142 ")
        assertInvalid(SettingReject.OUT_OF_RANGE, "slot_pids", "010C,010D,0105")
        assertInvalid(SettingReject.OUT_OF_RANGE, "slot_pids", "010C,010D,0105,0142,010B")
        assertInvalid(SettingReject.OUT_OF_RANGE, "slot_pids", "")
        assertInvalid(SettingReject.UNKNOWN_VALUE, "slot_pids", "010C,010D,0105,9999")
        assertInvalid(SettingReject.UNKNOWN_VALUE, "slot_pids", "terrain,010D,0105,010C")
        assertInvalid(SettingReject.WRONG_TYPE, "slot_pids", 4)
        assertInvalid(SettingReject.WRONG_TYPE, "slot_pids", listOf("010C"))
        assertInvalid(SettingReject.WRONG_TYPE, "slot_pids", null)
        assertNotNull(SettingsCatalog.spec("slot_pids"))
    }
}
