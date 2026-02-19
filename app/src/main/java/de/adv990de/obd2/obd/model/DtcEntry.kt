package de.adv990de.obd2.obd.model

data class DtcEntry(
    val code: String,           // e.g. "P0301"
    val description: String,
    val isPending: Boolean
)
