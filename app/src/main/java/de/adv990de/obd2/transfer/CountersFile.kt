package de.adv990de.obd2.transfer

import de.adv990de.obd2.logbook.MAX_ODOMETER_KM
import org.json.JSONException
import org.json.JSONObject

private const val KEY_FUEL = "fuel_km_since_fill"
private const val KEY_ANCHOR = "logbook_gps_anchor_km"
private const val KEY_GPS_KM = "logbook_gps_km"

/** Obergrenze der beiden Kilometerzaehler (km); mehr gibt es an einem Fahrzeug nicht. */
private const val MAX_COUNTER_KM = 1_000_000f

/** Die Zaehler der `counters.json`; `null` = nicht in der Datei oder ungueltig. */
class CounterValues(val fuelKmSinceFill: Float?, val gpsAnchorKm: Int?, val gpsKm: Float?)

/** Die gelesene `counters.json`: [values] sind geprueft, [rejectedKeys] die vorhandenen Schluessel, die nicht uebernommen werden (Katalogreihenfolge). */
class ParsedCounters(val values: CounterValues, val rejectedKeys: List<String>)

/**
 * `{"format":1,"fuel_km_since_fill":12.3,"logbook_gps_anchor_km":12345,"logbook_gps_km":4.2}`; nicht gesetzte (oder nicht endliche) Schluessel fehlen. Ein `Float` steht als kuerzeste
 * Dezimalzahl, damit er beim Lesen wieder derselbe `Float` wird. Die Schluessel sind ein Vertrag des Formats 1.
 */
fun countersToJson(values: CounterValues): String {
    val parts = mutableListOf("\"format\":1")
    values.fuelKmSinceFill?.takeIf { it.isFinite() }?.let { parts += "\"$KEY_FUEL\":${it.toString().toDouble()}" }
    values.gpsAnchorKm?.let { parts += "\"$KEY_ANCHOR\":$it" }
    values.gpsKm?.takeIf { it.isFinite() }?.let { parts += "\"$KEY_GPS_KM\":${it.toString().toDouble()}" }
    return "{${parts.joinToString(",")}}"
}

/**
 * `fuel_km_since_fill`: endlich, 0..1 000 000. `logbook_gps_anchor_km`: ganze Zahl 0..[MAX_ODOMETER_KM]. `logbook_gps_km`: endlich, 0..1 000 000. **Anker und Kilometer gelten nur zusammen:**
 * fehlt oder scheitert einer, werden beide verworfen (die vorhandenen Schluessel stehen in `rejectedKeys`). `IllegalArgumentException`: kein JSON-Objekt.
 */
fun parseCountersJson(text: String): ParsedCounters {
    require(jsonDepthWithin(text)) { "not a counters file: nested too deeply" }
    val root = try {
        JSONObject(text)
    } catch (e: JSONException) {
        throw IllegalArgumentException("not a counters file: not a JSON object", e)
    }
    val rejected = mutableListOf<String>()
    fun raw(key: String): Any? = if (root.has(key)) root.get(key) else null

    val fuelRaw = raw(KEY_FUEL)
    val fuel = fuelRaw?.let { number(it)?.takeIf { v -> v in 0.0..MAX_COUNTER_KM.toDouble() }?.toFloat() }
    if (fuelRaw != null && fuel == null) rejected += KEY_FUEL

    val anchorRaw = raw(KEY_ANCHOR)
    val kmRaw = raw(KEY_GPS_KM)
    val anchor = anchorRaw?.let { number(it)?.takeIf { v -> v == Math.rint(v) && v >= 0.0 && v <= MAX_ODOMETER_KM }?.toInt() }
    val km = kmRaw?.let { number(it)?.takeIf { v -> v in 0.0..MAX_COUNTER_KM.toDouble() }?.toFloat() }
    val pairOk = anchor != null && km != null
    if (!pairOk) {
        if (anchorRaw != null) rejected += KEY_ANCHOR
        if (kmRaw != null) rejected += KEY_GPS_KM
    }
    return ParsedCounters(
        CounterValues(fuel, anchor.takeIf { pairOk }, km.takeIf { pairOk }),
        rejected,
    )
}

/** Eine endliche JSON-Zahl (ein Text wie `"NaN"` oder `"12"` ist keine). */
private fun number(raw: Any): Double? = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() }

enum class OdometerDecision { APPLY, SKIP_ANCHOR_MISMATCH, SKIP_NO_LOGBOOK, ABSENT }

/** Die Regel des Entwurfs: die Schaetzung gilt **nur, wenn der Anker der Datei gleich dem hoechsten Kilometerstand des Logbuchs nach dem Import ist**. */
fun decideOdometer(values: CounterValues, highestLoggedKm: Int?): OdometerDecision = when {
    values.gpsAnchorKm == null || values.gpsKm == null -> OdometerDecision.ABSENT
    highestLoggedKm == null -> OdometerDecision.SKIP_NO_LOGBOOK
    values.gpsAnchorKm == highestLoggedKm -> OdometerDecision.APPLY
    else -> OdometerDecision.SKIP_ANCHOR_MISMATCH
}
