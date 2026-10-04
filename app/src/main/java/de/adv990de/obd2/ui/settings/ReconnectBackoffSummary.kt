package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.ui.dashboard.reconnectDelays
import java.util.Locale

/**
 * What the reconnect loop will do for a given base delay: the wait before each attempt, in whole
 * seconds, and their sum. Shown under the base-delay choice in Settings so the user sees what a
 * choice really means — a base of 30 s adds up to 15:30 min of waiting.
 */
internal data class ReconnectBackoffSummary(val delaysSeconds: List<Int>, val totalSeconds: Int)

/**
 * Derived from the production [reconnectDelays] rather than re-computed, so the display cannot
 * drift from what `ConnectionCoordinator` really waits. Every option is a whole number of seconds,
 * so the millisecond values divide exactly.
 */
internal fun reconnectBackoffSummary(baseDelaySeconds: Int): ReconnectBackoffSummary {
    val delays = reconnectDelays(baseDelaySeconds).map { (it / 1_000L).toInt() }
    return ReconnectBackoffSummary(delaysSeconds = delays, totalSeconds = delays.sum())
}

/** `m:ss` — 93 → "1:33", 930 → "15:30". The unit ("min") is part of the string resource, so the
 *  formatter itself stays locale-neutral ([Locale.ROOT] keeps the digits ASCII on every device). */
internal fun formatMinutesSeconds(totalSeconds: Int): String {
    require(totalSeconds >= 0) { "totalSeconds must not be negative" }
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
