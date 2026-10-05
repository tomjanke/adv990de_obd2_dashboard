package de.adv990de.obd2.obd

import kotlin.math.abs

/**
 * Converts a sensor value and its unit to the display unit.
 * Returns the converted value and display unit string.
 * When [imperial] is false, value and unit are returned unchanged.
 */
fun convertUnit(value: Float, unit: String, imperial: Boolean): Pair<Float, String> =
    when {
        !imperial      -> value to unit
        unit == "km/h" -> value * 0.621371f to "mph"
        unit == "°C"   -> value * 9f / 5f + 32f to "°F"
        else           -> value to unit
    }

/**
 * The number of a sensor value as shown on screen: one decimal for volts, a whole number for everything else, in the
 * device's locale. [rawUnit] is the PID's own unit (before [convertUnit]) — it decides the precision.
 *
 * A negative value that rounds to zero is shown without the sign: `"%.0f".format(-0.4f)` is "-0" — a sign in front
 * of nothing (QA UID-14). The check is numeric (|value| below half a unit of the last shown digit), not textual, so it
 * holds for locales that write digits differently. Kachel, Slot-Auswahl and the logging list all format this way.
 */
fun formatSensorNumber(value: Float, rawUnit: String): String {
    val isVolt = rawUnit == "V"
    val text = if (isVolt) "%.1f".format(value) else "%.0f".format(value)
    val halfLastDigit = if (isVolt) 0.05f else 0.5f
    return if (text.startsWith("-") && abs(value) < halfLastDigit) text.substring(1) else text
}
