package de.adv990de.obd2.data

/** A fault code the vehicle reported at some point — what the history screen lists. */
data class DtcHistoryEntry(
    val code: String,
    val description: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val lastWasPending: Boolean,
)
