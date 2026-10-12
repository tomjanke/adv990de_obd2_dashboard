package de.adv990de.obd2.data

/** A fault code the vehicle reported at some point — what the history screen lists. The description is derived from [code] for display. */
data class DtcHistoryEntry(
    val code: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val lastWasPending: Boolean,
)
