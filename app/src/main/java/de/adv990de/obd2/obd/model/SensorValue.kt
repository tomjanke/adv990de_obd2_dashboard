package de.adv990de.obd2.obd.model

data class SensorValue(
    val pid: PidDefinition,
    val value: Float,
    val timestamp: Long = System.currentTimeMillis()
)
