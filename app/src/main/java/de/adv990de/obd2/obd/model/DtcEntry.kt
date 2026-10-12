package de.adv990de.obd2.obd.model

/** A fault code read from the vehicle. The description is derived from the code for display: [de.adv990de.obd2.obd.dtcDescriptionRes]. */
data class DtcEntry(
    val code: String,           // e.g. "P0301"
    val isPending: Boolean
)
