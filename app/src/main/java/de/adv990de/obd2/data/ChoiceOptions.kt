package de.adv990de.obd2.data

import kotlin.math.abs

/**
 * Maps [value] to the entry of [options] that is nearest to it; on an exact tie the LARGER option
 * wins. Pure and Android-free, so it is directly JVM-testable (same convention as `determineGear`).
 *
 * Used at the READ boundary of settings that are chosen from a fixed list in the UI but were typed
 * as a free number by earlier versions (see `AppPreferences.obdWatchdogTimeoutSeconds`): a stored
 * value that is not on the list is reinterpreted as its nearest option, so every consumer — the
 * watchdog, the reconnect loop and the Settings UI — sees the same on-list number, without a
 * DataStore migration.
 *
 * The tie-break is arbitrary but fixed: the larger option is the more patient one for both
 * connection settings (a longer freeze tolerance, a longer reconnect pause).
 *
 * The distance is computed in [Long] so `Int.MIN_VALUE`/`Int.MAX_VALUE` cannot overflow.
 *
 * @throws IllegalArgumentException if [options] is empty.
 */
internal fun snapToOption(value: Int, options: List<Int>): Int {
    require(options.isNotEmpty()) { "options must not be empty" }
    return options.minWith(
        compareBy<Int> { abs(value.toLong() - it.toLong()) }.thenByDescending { it }
    )
}
