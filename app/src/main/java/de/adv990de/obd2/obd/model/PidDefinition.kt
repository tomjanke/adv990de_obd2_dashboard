package de.adv990de.obd2.obd.model

data class PidDefinition(
    val id: String,           // e.g. "010C"
    val name: String,         // e.g. "RPM"
    val unit: String,         // e.g. "rpm"
    val minValue: Float,
    val maxValue: Float,
    val formula: (ByteArray) -> Float   // raw bytes → engineering value
)
