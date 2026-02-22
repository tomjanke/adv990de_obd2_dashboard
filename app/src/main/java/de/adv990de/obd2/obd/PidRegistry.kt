package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.PidDefinition

object PidRegistry {

    val RPM = PidDefinition(
        id = "010C",
        name = "RPM",
        unit = "rpm",
        minValue = 0f,
        maxValue = 10000f,
        formula = { bytes -> ((bytes[0].toInt() and 0xFF) * 256f + (bytes[1].toInt() and 0xFF)) / 4f }
    )

    val SPEED = PidDefinition(
        id = "010D",
        name = "Speed",
        unit = "km/h",
        minValue = 0f,
        maxValue = 300f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF).toFloat() }
    )

    val COOLANT_TEMP = PidDefinition(
        id = "0105",
        name = "Coolant Temp",
        unit = "°C",
        minValue = -40f,
        maxValue = 215f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF) - 40f }
    )

    val ENGINE_LOAD = PidDefinition(
        id = "0104",
        name = "Engine Load",
        unit = "%",
        minValue = 0f,
        maxValue = 100f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF) * 100f / 255f }
    )

    val THROTTLE = PidDefinition(
        id = "0111",
        name = "Throttle",
        unit = "%",
        minValue = 0f,
        maxValue = 100f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF) * 100f / 255f }
    )

    val INTAKE_AIR_TEMP = PidDefinition(
        id = "010F",
        name = "Intake Air Temp",
        unit = "°C",
        minValue = -40f,
        maxValue = 215f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF) - 40f }
    )

    val MAF = PidDefinition(
        id = "0110",
        name = "MAF",
        unit = "g/s",
        minValue = 0f,
        maxValue = 655f,
        formula = { bytes -> ((bytes[0].toInt() and 0xFF) * 256f + (bytes[1].toInt() and 0xFF)) / 100f }
    )

    val SHORT_TERM_FUEL_TRIM = PidDefinition(
        id = "0106", name = "Short Term Fuel Trim", unit = "%",
        minValue = -100f, maxValue = 99.2f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF) * 100f / 128f - 100f }
    )

    val LONG_TERM_FUEL_TRIM = PidDefinition(
        id = "0107", name = "Long Term Fuel Trim", unit = "%",
        minValue = -100f, maxValue = 99.2f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF) * 100f / 128f - 100f }
    )

    val INTAKE_MANIFOLD_PRESSURE = PidDefinition(
        id = "010B", name = "Intake Manifold Pressure", unit = "kPa",
        minValue = 0f, maxValue = 255f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF).toFloat() }
    )

    val TIMING_ADVANCE = PidDefinition(
        id = "010E", name = "Timing Advance", unit = "°",
        minValue = -64f, maxValue = 63.5f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF) / 2f - 64f }
    )

    val FUEL_TANK_LEVEL = PidDefinition(
        id = "012F", name = "Fuel Tank Level", unit = "%",
        minValue = 0f, maxValue = 100f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF) * 100f / 255f }
    )

    val BAROMETRIC_PRESSURE = PidDefinition(
        id = "0133", name = "Barometric Pressure", unit = "kPa",
        minValue = 0f, maxValue = 255f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF).toFloat() }
    )

    val CATALYST_TEMP_B1S1 = PidDefinition(
        id = "013C", name = "Catalyst Temp B1S1", unit = "°C",
        minValue = -40f, maxValue = 6513f,
        // (A×256 + B) / 10 − 40
        formula = { bytes ->
            ((bytes[0].toInt() and 0xFF) * 256f + (bytes[1].toInt() and 0xFF)) / 10f - 40f
        }
    )

    val CONTROL_MODULE_VOLTAGE = PidDefinition(
        id = "0142", name = "Control Module Voltage", unit = "V",
        minValue = 0f, maxValue = 65.5f,
        // (A×256 + B) / 1000
        formula = { bytes ->
            ((bytes[0].toInt() and 0xFF) * 256f + (bytes[1].toInt() and 0xFF)) / 1000f
        }
    )

    val AMBIENT_AIR_TEMP = PidDefinition(
        id = "0146", name = "Ambient Air Temp", unit = "°C",
        minValue = -40f, maxValue = 215f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF) - 40f }
    )

    val ACCELERATOR_PEDAL_POS = PidDefinition(
        id = "0149", name = "Accelerator Pedal Pos", unit = "%",
        minValue = 0f, maxValue = 100f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF) * 100f / 255f }
    )

    val ENGINE_OIL_TEMP = PidDefinition(
        id = "015C", name = "Engine Oil Temp", unit = "°C",
        minValue = -40f, maxValue = 215f,
        formula = { bytes -> (bytes[0].toInt() and 0xFF) - 40f }
    )

    val ENGINE_FUEL_RATE = PidDefinition(
        id = "015E", name = "Engine Fuel Rate", unit = "L/h",
        minValue = 0f, maxValue = 3276f,
        // (A×256 + B) / 20
        formula = { bytes ->
            ((bytes[0].toInt() and 0xFF) * 256f + (bytes[1].toInt() and 0xFF)) / 20f
        }
    )

    /** All PIDs indexed by command string (e.g. "010C") */
    val all: Map<String, PidDefinition> = mapOf(
        RPM.id to RPM,
        SPEED.id to SPEED,
        COOLANT_TEMP.id to COOLANT_TEMP,
        ENGINE_LOAD.id to ENGINE_LOAD,
        THROTTLE.id to THROTTLE,
        INTAKE_AIR_TEMP.id to INTAKE_AIR_TEMP,
        MAF.id to MAF,
        SHORT_TERM_FUEL_TRIM.id to SHORT_TERM_FUEL_TRIM,
        LONG_TERM_FUEL_TRIM.id to LONG_TERM_FUEL_TRIM,
        INTAKE_MANIFOLD_PRESSURE.id to INTAKE_MANIFOLD_PRESSURE,
        TIMING_ADVANCE.id to TIMING_ADVANCE,
        FUEL_TANK_LEVEL.id to FUEL_TANK_LEVEL,
        BAROMETRIC_PRESSURE.id to BAROMETRIC_PRESSURE,
        CATALYST_TEMP_B1S1.id to CATALYST_TEMP_B1S1,
        CONTROL_MODULE_VOLTAGE.id to CONTROL_MODULE_VOLTAGE,
        AMBIENT_AIR_TEMP.id to AMBIENT_AIR_TEMP,
        ACCELERATOR_PEDAL_POS.id to ACCELERATOR_PEDAL_POS,
        ENGINE_OIL_TEMP.id to ENGINE_OIL_TEMP,
        ENGINE_FUEL_RATE.id to ENGINE_FUEL_RATE,
    )

    /** Default PIDs shown on first launch */
    val defaults: List<PidDefinition> = listOf(RPM, SPEED, COOLANT_TEMP)
}
