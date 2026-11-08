package de.adv990de.obd2.transfer

import de.adv990de.obd2.logbook.MAINTENANCE_TYPES

/** Ein Satz Werte, der **von den Standardwerten abweicht**, einer je Schluessel des Katalogs. */
internal fun sampleSettings(): Map<String, Any> = buildMap {
    put("theme", "amber")
    put("use_imperial", true)
    put("hide_nav_on_drive", true)
    put("use_gps_speed", true)
    put("rpm_dynamic_threshold", true)
    put("rpm_blink_threshold", 9_000)
    put("slot_pids", "010C,0142,TERRAIN,")
    put("show_all_bt_devices", true)
    put("log_only_dtc_sessions", true)
    put("obd_watchdog_timeout_seconds", 60)
    put("reconnect_base_delay_seconds", 30)
    put("alarm_voltage_enabled", false)
    put("alarm_coolant_enabled", false)
    put("alarm_faults_enabled", false)
    put("alarm_voltage_notice_dv", 128)
    put("alarm_voltage_warning_dv", 118)
    put("alarm_coolant_notice_c", 98)
    put("alarm_coolant_warning_c", 110)
    put("fuel_range_full_km", 280)
    put("fuel_reserve_km", 40)
    put("fuel_tank_capacity_dl", 205)
    MAINTENANCE_TYPES.forEachIndexed { i, t -> put("maintenance_interval_${t.name.lowercase()}_km", 500 * (i + 1)) }
    put("wheel_circumference_mm", 2060f)
    put("primary_ratio", 1.8571f)
    put("secondary_ratio", 2.8125f)
    put("gear_ratio_1", 2.9168f)
    put("gear_ratio_2", 2.2f)
    put("gear_ratio_3", 1.7f)
    put("gear_ratio_4", 1.4f)
    put("gear_ratio_5", 0f)
    put("gear_ratio_6", 1.1f)
}

