package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.PidDefinition

object PidRegistry {

    val RPM = PidDefinition(
        id = "010C",
        name = "RPM",
        unit = "rpm",
        minValue = 0f,
        maxValue = 8000f,
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

    /** All PIDs indexed by command string (e.g. "010C") */
    val all: Map<String, PidDefinition> = mapOf(
        RPM.id to RPM,
        SPEED.id to SPEED,
        COOLANT_TEMP.id to COOLANT_TEMP,
        ENGINE_LOAD.id to ENGINE_LOAD,
        THROTTLE.id to THROTTLE,
        INTAKE_AIR_TEMP.id to INTAKE_AIR_TEMP,
        MAF.id to MAF,
    )

    /** Default PIDs shown on first launch */
    val defaults: List<PidDefinition> = listOf(RPM, SPEED, COOLANT_TEMP)
}
