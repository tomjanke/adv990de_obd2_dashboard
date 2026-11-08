package de.adv990de.obd2.data

import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.MAINTENANCE_TYPES
import de.adv990de.obd2.transfer.SettingsCatalog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Liest die Einstellungen eines Pakets aus [AppPreferences] und schreibt sie zurueck — **nur ueber die oeffentlichen Setter** (nie an den Repositories vorbei). Das Format und die
 * Pruefung stehen rein in `transfer/SettingsCatalog` und `SettingsFile`; hier steht die Bindung Schluessel → Lesen → Schreiben (jede Zeile ein Eintrag von [bindings], ein Test
 * haelt die Schluessel gleich denen des Katalogs).
 */
class SettingsTransfer @Inject constructor(private val prefs: AppPreferences) {

    /** Die **wirksamen** Werte (P9) in Katalogreihenfolge: Standardwerte eingeschlossen, ein nicht festgelegtes Wartungsintervall als 0. */
    suspend fun snapshot(): Map<String, Any> {
        val result = LinkedHashMap<String, Any>()
        for (binding in bindings) result[binding.key] = binding.read(prefs)
        return result
    }

    /**
     * Schreibt **geprueft** Werte (aus `SettingsCatalog.validate`) in Katalogreihenfolge. DataStore hat keine Transaktion ueber mehrere Setter (P8): vorher wird der Stand
     * gesichert, bei **jedem** Fehler — auch einem Setter, der einen Wert ablehnt, einem falschen Typ und einem Abbruch — wird er zurueckgeschrieben (best effort, jeder
     * Schluessel fuer sich, ausser Reichweite eines Abbruchs) und der Fehler weitergereicht. Ein Schluessel, der nicht in [values] steht, bleibt unberuehrt.
     */
    suspend fun apply(values: Map<String, Any>) {
        val before = snapshot()
        try {
            for (binding in bindings) {
                val value = values[binding.key] ?: continue
                binding.write(prefs, value)
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                for (binding in bindings) {
                    // nur, was der Import angefasst hat: ein anderer Schluessel behaelt seinen Standard (statt zu einem expliziten Wert zu werden) und eine gleichzeitige Aenderung des Nutzers bleibt
                    if (binding.key !in values) continue
                    try {
                        binding.write(prefs, before.getValue(binding.key))
                    } catch (second: Exception) {
                        e.addSuppressed(second)
                    }
                }
            }
            throw e
        }
    }

    private class Binding(val key: String, val read: suspend (AppPreferences) -> Any, val write: suspend (AppPreferences, Any) -> Unit)

    companion object {
        private fun maintenance(type: LogType) = Binding(
            "maintenance_interval_${type.name.lowercase()}_km",
            { it.maintenanceIntervals.first()[type] ?: 0 },
            { p, v -> p.setMaintenanceInterval(type, v as Int) },
        )

        private val bindings: List<Binding> = listOf(
            Binding("theme", { it.theme.first() }, { p, v -> p.setTheme(v as String) }),
            Binding("use_imperial", { it.useImperial.first() }, { p, v -> p.setUseImperial(v as Boolean) }),
            Binding("hide_nav_on_drive", { it.hideNavOnDrive.first() }, { p, v -> p.setHideNavOnDrive(v as Boolean) }),
            Binding("use_gps_speed", { it.useGpsSpeed.first() }, { p, v -> p.setUseGpsSpeed(v as Boolean) }),
            Binding("rpm_dynamic_threshold", { it.rpmDynamicThreshold.first() }, { p, v -> p.setRpmDynamicThreshold(v as Boolean) }),
            Binding("rpm_blink_threshold", { it.rpmBlinkThreshold.first() }, { p, v -> p.setRpmBlinkThreshold(v as Int) }),
            Binding(
                "slot_pids",
                { it.slotPids.first().joinToString(",") },
                { p, v ->
                    val ids = (v as String).split(",", limit = Int.MAX_VALUE)
                    require(ids.size == 4) { "slot_pids needs four entries" }
                    ids.forEachIndexed { i, id -> p.setSlotPid(i, id.trim()) }
                },
            ),
            Binding("show_all_bt_devices", { it.showAllBtDevices.first() }, { p, v -> p.setShowAllBtDevices(v as Boolean) }),
            Binding("log_only_dtc_sessions", { it.logOnlyDtcSessions.first() }, { p, v -> p.setLogOnlyDtcSessions(v as Boolean) }),
            Binding("obd_watchdog_timeout_seconds", { it.obdWatchdogTimeoutSeconds.first() }, { p, v -> p.setObdWatchdogTimeoutSeconds(v as Int) }),
            Binding("reconnect_base_delay_seconds", { it.reconnectBaseDelaySeconds.first() }, { p, v -> p.setReconnectBaseDelaySeconds(v as Int) }),
            Binding("alarm_voltage_enabled", { it.alarmSettings.first().voltageEnabled }, { p, v -> p.setAlarmVoltageEnabled(v as Boolean) }),
            Binding("alarm_coolant_enabled", { it.alarmSettings.first().coolantEnabled }, { p, v -> p.setAlarmCoolantEnabled(v as Boolean) }),
            Binding("alarm_faults_enabled", { it.alarmSettings.first().faultsEnabled }, { p, v -> p.setAlarmFaultsEnabled(v as Boolean) }),
            Binding("alarm_voltage_notice_dv", { it.alarmSettings.first().voltageNoticeDeciV }, { p, v -> p.setAlarmVoltageNoticeDeciV(v as Int) }),
            Binding("alarm_voltage_warning_dv", { it.alarmSettings.first().voltageWarningDeciV }, { p, v -> p.setAlarmVoltageWarningDeciV(v as Int) }),
            Binding("alarm_coolant_notice_c", { it.alarmSettings.first().coolantNoticeC }, { p, v -> p.setAlarmCoolantNoticeC(v as Int) }),
            Binding("alarm_coolant_warning_c", { it.alarmSettings.first().coolantWarningC }, { p, v -> p.setAlarmCoolantWarningC(v as Int) }),
            Binding("fuel_range_full_km", { it.fuelRangeFullKm.first() }, { p, v -> p.setFuelRangeFullKm(v as Int) }),
            Binding("fuel_reserve_km", { it.fuelReserveKm.first() }, { p, v -> p.setFuelReserveKm(v as Int) }),
            Binding("fuel_tank_capacity_dl", { it.fuelTankCapacityDl.first() }, { p, v -> p.setFuelTankCapacityDl(v as Int) }),
        ) + MAINTENANCE_TYPES.map(::maintenance) + listOf(
            Binding("wheel_circumference_mm", { it.wheelCircumferenceMm.first() }, { p, v -> p.setWheelCircumferenceMm(v as Float) }),
            Binding("primary_ratio", { it.primaryRatio.first() }, { p, v -> p.setPrimaryRatio(v as Float) }),
            Binding("secondary_ratio", { it.secondaryRatio.first() }, { p, v -> p.setSecondaryRatio(v as Float) }),
        ) + (1..6).map { n ->
            Binding("gear_ratio_$n", { it.gearRatios.first()[n - 1] }, { p, v -> p.setGearRatio(n - 1, v as Float) })
        }

        /** Die Schluessel der Bindungen, in Reihenfolge — ein Test haelt sie gleich denen von [SettingsCatalog.exported]. */
        val bindingKeys: List<String> get() = bindings.map { it.key }
    }

    private val bindings get() = Companion.bindings
}
