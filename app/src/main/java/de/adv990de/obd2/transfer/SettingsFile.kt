package de.adv990de.obd2.transfer

import org.json.JSONException
import org.json.JSONObject

/** Ein Wert der Datei, den der Katalog ablehnt, mit dem Grund. */
class RejectedSetting(val key: String, val reason: SettingReject)

/**
 * Die gelesene `settings.json`: [valid] sind die geprueften Werte im Zieltyp (in Katalogreihenfolge), [rejected] die abgelehnten, [unknownKeys] die Schluessel, die der
 * Katalog nicht fuehrt — auch die ausgeschlossenen (ein von Hand eingefuegtes `device_address` wird nie angewandt) — sortiert.
 */
class ParsedSettings(val valid: Map<String, Any>, val rejected: List<RejectedSetting>, val unknownKeys: List<String>)

/**
 * `{"format":1,"values":{…}}`, die Schluessel in Katalogreihenfolge (von Hand geschrieben: das `JSONObject` der JVM-Referenzimplementierung ordnet nicht). Nur Katalogschluessel;
 * ein `Float` steht als kuerzeste Dezimalzahl (`value.toString().toDouble()`), damit er beim Lesen wieder derselbe `Float` wird.
 */
fun settingsToJson(values: Map<String, Any>): String {
    val body = SettingsCatalog.exported.mapNotNull { spec ->
        val value = values[spec.key] ?: return@mapNotNull null
        val text = when (value) {
            is Boolean, is Int, is Long -> value.toString()
            is Float -> value.toString().toDouble().toString()
            is Double -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
        "${JSONObject.quote(spec.key)}:$text"
    }
    return "{\"format\":1,\"values\":{${body.joinToString(",")}}}"
}

/** `IllegalArgumentException`: kein JSON-Objekt oder kein Objekt `values`. Ein neueres `format` wird gelesen (das Warnen ist Sache des Importers). */
fun parseSettingsJson(text: String): ParsedSettings {
    val root = try {
        JSONObject(text)
    } catch (e: JSONException) {
        throw IllegalArgumentException("not a settings file: not a JSON object", e)
    }
    val values = root.optJSONObject("values") ?: throw IllegalArgumentException("not a settings file: no \"values\" object")
    val valid = LinkedHashMap<String, Any>()
    val rejected = mutableListOf<RejectedSetting>()
    for (spec in SettingsCatalog.exported) {
        if (!values.has(spec.key)) continue
        val raw = values.get(spec.key).takeIf { it != JSONObject.NULL }
        when (val check = SettingsCatalog.validate(spec, raw)) {
            is SettingCheck.Valid -> valid[spec.key] = check.value
            is SettingCheck.Invalid -> rejected += RejectedSetting(spec.key, check.reason)
        }
    }
    val unknown = values.keys().asSequence().filter { SettingsCatalog.spec(it) == null }.sorted().toList()
    return ParsedSettings(valid, rejected, unknown)
}

class SettingChange(val spec: SettingSpec, val from: Any?, val to: Any)

/**
 * Was ein Import ändern würde: [changes] (gültige Werte, die vom jetzigen Stand abweichen), [unchanged] (gültig und schon so), dazu die abgelehnten und unbekannten Schlüssel.
 * [total] zählt die Einstellungen der Datei, die der Katalog kennt (geändert, unverändert und abgelehnt).
 */
class SettingsPlan(val changes: List<SettingChange>, val unchanged: Int, val rejected: List<RejectedSetting>, val unknownKeys: List<String>) {
    val total: Int get() = changes.size + unchanged + rejected.size

    fun changesBy(group: SettingsGroup): Int = changes.count { it.spec.group == group }
}

/** Ein Schlüssel, der nicht in der Datei steht, bleibt unberührt (keine Änderung, keine Ablehnung). */
fun planSettings(parsed: ParsedSettings, current: Map<String, Any>): SettingsPlan {
    val changes = mutableListOf<SettingChange>()
    var unchanged = 0
    for (spec in SettingsCatalog.exported) {
        val to = parsed.valid[spec.key] ?: continue
        val from = current[spec.key]
        if (from == to) unchanged++ else changes += SettingChange(spec, from, to)
    }
    return SettingsPlan(changes, unchanged, parsed.rejected, parsed.unknownKeys)
}
