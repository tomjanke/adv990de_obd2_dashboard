package de.adv990de.obd2.obd

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
