package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.ui.components.TileStatus
import org.junit.Test
import kotlin.test.assertEquals

class PidStatusMappingTest {

    // region — Coolant Temp thresholds (Warning > 110°C, Critical > 120°C)
    @Test fun coolant_nominal_isOk()         = check(PidRegistry.COOLANT_TEMP.id, 90f,   TileStatus.Ok)
    @Test fun coolant_atWarningEdge_isOk()   = check(PidRegistry.COOLANT_TEMP.id, 110f,  TileStatus.Ok)  // strictly greater than
    @Test fun coolant_aboveWarning_isWarn()  = check(PidRegistry.COOLANT_TEMP.id, 110.1f, TileStatus.Warning)
    @Test fun coolant_atCriticalEdge_isWarn() = check(PidRegistry.COOLANT_TEMP.id, 120f, TileStatus.Warning)
    @Test fun coolant_aboveCritical_isCrit() = check(PidRegistry.COOLANT_TEMP.id, 120.1f, TileStatus.Critical)
    // endregion

    // region — Oil Temp (Warning > 120°C, Critical > 135°C)
    @Test fun oilTemp_nominal_isOk()         = check(PidRegistry.ENGINE_OIL_TEMP.id, 95f,  TileStatus.Ok)
    @Test fun oilTemp_aboveWarn_isWarn()     = check(PidRegistry.ENGINE_OIL_TEMP.id, 122f, TileStatus.Warning)
    @Test fun oilTemp_aboveCrit_isCrit()     = check(PidRegistry.ENGINE_OIL_TEMP.id, 140f, TileStatus.Critical)
    @Test fun oilTemp_atWarningEdge_isOk()   = check(PidRegistry.ENGINE_OIL_TEMP.id, 120f,   TileStatus.Ok)
    @Test fun oilTemp_justAboveWarning_isWarn() = check(PidRegistry.ENGINE_OIL_TEMP.id, 120.1f, TileStatus.Warning)
    @Test fun oilTemp_atCriticalEdge_isWarn() = check(PidRegistry.ENGINE_OIL_TEMP.id, 135f,   TileStatus.Warning)
    @Test fun oilTemp_justAboveCritical_isCrit() = check(PidRegistry.ENGINE_OIL_TEMP.id, 135.1f, TileStatus.Critical)
    // endregion

    // region — Control Module Voltage (Crit <11.0, Warn <11.8 or >15.0, Ok between)
    @Test fun voltage_nominal_isOk()         = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, 13.8f, TileStatus.Ok)
    @Test fun voltage_low_isWarn()           = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, 11.5f, TileStatus.Warning)
    @Test fun voltage_veryLow_isCrit()       = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, 10.5f, TileStatus.Critical)
    @Test fun voltage_overcharging_isWarn()  = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, 15.5f, TileStatus.Warning)
    // Die Kanten genau: unter 11,0 kritisch, ab 11,0 bis unter 11,8 Warnung, 11,8 bis einschliesslich 15,0 in Ordnung, darueber Warnung.
    @Test fun voltage_justAboveZero_isCrit() = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, 0.1f,   TileStatus.Critical)
    @Test fun voltage_justBelow11_isCrit()   = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, 10.99f, TileStatus.Critical)
    @Test fun voltage_at11_isWarn()          = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, 11.0f,  TileStatus.Warning)
    @Test fun voltage_justBelow11_8_isWarn() = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, 11.79f, TileStatus.Warning)
    @Test fun voltage_at11_8_isOk()          = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, 11.8f,  TileStatus.Ok)
    @Test fun voltage_at15_isOk()            = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, 15.0f,  TileStatus.Ok)
    @Test fun voltage_justAbove15_isWarn()   = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, 15.01f, TileStatus.Warning)
    // 0 V ist keine leere Batterie, sondern eine fehlende Antwort (PID nicht unterstuetzt,
    // Platzhalter-Wert). Alarm waere hier eine Falschmeldung.
    @Test fun voltage_zero_isOk()            = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, 0f, TileStatus.Ok)
    @Test fun voltage_negative_isOk()        = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, -1f, TileStatus.Ok)
    // endregion

    // region — RPM
    @Test fun rpm_idle_isOk()                = check(PidRegistry.RPM.id,  800f, TileStatus.Ok)
    @Test fun rpm_highCruise_isOk()          = check(PidRegistry.RPM.id, 6500f, TileStatus.Ok)
    @Test fun rpm_aboveWarn_isWarn()         = check(PidRegistry.RPM.id, 6800f, TileStatus.Warning)
    @Test fun rpm_aboveCrit_isCrit()         = check(PidRegistry.RPM.id, 7800f, TileStatus.Critical)
    @Test fun rpm_justAboveWarning_isWarn()  = check(PidRegistry.RPM.id, 6500.1f, TileStatus.Warning)
    @Test fun rpm_atCriticalEdge_isWarn()    = check(PidRegistry.RPM.id, 7500f, TileStatus.Warning)
    @Test fun rpm_justAboveCritical_isCrit() = check(PidRegistry.RPM.id, 7500.1f, TileStatus.Critical)
    // endregion

    // region — Engine Load (Warning > 90%, no Critical)
    @Test fun load_normal_isOk()             = check(PidRegistry.ENGINE_LOAD.id, 45f, TileStatus.Ok)
    @Test fun load_high_isWarn()             = check(PidRegistry.ENGINE_LOAD.id, 95f, TileStatus.Warning)
    @Test fun load_atEdge_isOk()             = check(PidRegistry.ENGINE_LOAD.id, 90f, TileStatus.Ok)
    @Test fun load_justAboveEdge_isWarn()    = check(PidRegistry.ENGINE_LOAD.id, 90.1f, TileStatus.Warning)
    // endregion

    // region — Fuel Tank Level (low = bad)
    @Test fun fuel_full_isOk()               = check(PidRegistry.FUEL_TANK_LEVEL.id, 80f, TileStatus.Ok)
    @Test fun fuel_quarter_isOk()            = check(PidRegistry.FUEL_TANK_LEVEL.id, 25f, TileStatus.Ok)
    @Test fun fuel_low_isWarn()              = check(PidRegistry.FUEL_TANK_LEVEL.id, 15f, TileStatus.Warning)
    @Test fun fuel_veryLow_isCrit()          = check(PidRegistry.FUEL_TANK_LEVEL.id,  5f, TileStatus.Critical)
    @Test fun fuel_at20_isOk()               = check(PidRegistry.FUEL_TANK_LEVEL.id, 20f,   TileStatus.Ok)
    @Test fun fuel_justBelow20_isWarn()      = check(PidRegistry.FUEL_TANK_LEVEL.id, 19.9f, TileStatus.Warning)
    @Test fun fuel_at10_isWarn()             = check(PidRegistry.FUEL_TANK_LEVEL.id, 10f,   TileStatus.Warning)
    @Test fun fuel_justBelow10_isCrit()      = check(PidRegistry.FUEL_TANK_LEVEL.id, 9.9f,  TileStatus.Critical)
    // endregion

    // region — Catalyst Temp B1S1 (Warning > 750°C, Critical > 850°C)
    @Test fun catalyst_nominal_isOk()          = check(PidRegistry.CATALYST_TEMP_B1S1.id, 600f,   TileStatus.Ok)
    @Test fun catalyst_atWarningEdge_isOk()    = check(PidRegistry.CATALYST_TEMP_B1S1.id, 750f,   TileStatus.Ok)  // strictly greater than
    @Test fun catalyst_aboveWarning_isWarn()   = check(PidRegistry.CATALYST_TEMP_B1S1.id, 750.1f, TileStatus.Warning)
    @Test fun catalyst_atCriticalEdge_isWarn() = check(PidRegistry.CATALYST_TEMP_B1S1.id, 850f,   TileStatus.Warning)
    @Test fun catalyst_aboveCritical_isCrit()  = check(PidRegistry.CATALYST_TEMP_B1S1.id, 850.1f, TileStatus.Critical)
    // endregion

    // region — Edge / unknown PIDs
    @Test fun nan_isOk()                     = check(PidRegistry.COOLANT_TEMP.id, Float.NaN, TileStatus.Ok)
    @Test fun positiveInfinity_isOk()        = check(PidRegistry.RPM.id, Float.POSITIVE_INFINITY, TileStatus.Ok)
    @Test fun negativeInfinity_isOk()        = check(PidRegistry.CONTROL_MODULE_VOLTAGE.id, Float.NEGATIVE_INFINITY, TileStatus.Ok)
    @Test fun unknownPidId_defaultsToOk()    = check("01FF", 9000f, TileStatus.Ok)
    @Test fun speedPid_neverWarns()          = check(PidRegistry.SPEED.id, 250f, TileStatus.Ok)  // no rule
    // endregion

    private fun check(pidId: String, value: Float, expected: TileStatus) {
        assertEquals(expected, pidTileStatus(pidId, value),
            "pidTileStatus($pidId, $value)")
    }
}
