package de.adv990de.obd2.transfer

import org.json.JSONObject

/** Der Kopf eines Pakets: Format, Herkunft und die Zahl der Einträge je Teil. */
data class PackageManifest(
    val format: Int,
    val appId: String,
    val appVersion: String,
    val createdMs: Long,
    val parts: Map<DataPart, Int>,
)

fun PackageManifest.toJson(): String {
    val partsJson = JSONObject()
    for ((part, count) in parts) partsJson.put(part.name, count)
    return JSONObject()
        .put("format", format)
        .put("app_id", appId)
        .put("app_version", appVersion)
        .put("created_ms", createdMs)
        .put("parts", partsJson)
        .toString()
}

/**
 * `null` = keine Sicherung dieser App: kein JSON-Objekt, `format` keine ganze Zahl ab 1, `app_id` fehlt oder ist eine andere. Ein neueres Format wird gelesen
 * (das Warnen ist Sache des Importers); unbekannte Namen in `parts` werden übergangen.
 */
fun parseManifest(text: String): PackageManifest? {
    val json = try {
        JSONObject(text)
    } catch (_: Exception) {
        return null
    }
    val format = json.opt("format").let { if (it is Int) it else if (it is Long) it.toInt().takeIf { v -> v.toLong() == it } else null } ?: return null
    if (format < 1) return null
    if (json.opt("app_id") != PackageFormat.APP_ID) return null
    val parts = mutableMapOf<DataPart, Int>()
    json.optJSONObject("parts")?.let { obj ->
        for (key in obj.keys()) {
            val part = DataPart.entries.firstOrNull { it.name == key } ?: continue
            val count = obj.opt(key) as? Int ?: continue
            parts[part] = count
        }
    }
    return PackageManifest(
        format = format,
        appId = PackageFormat.APP_ID,
        appVersion = json.optString("app_version", ""),
        createdMs = json.optLong("created_ms", 0L),
        parts = parts,
    )
}
