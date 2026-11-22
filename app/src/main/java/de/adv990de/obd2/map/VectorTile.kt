package de.adv990de.obd2.map

/*
 * Ein Dekoder fuer Mapbox Vector Tiles 2.1 (Spezifikation: github.com/mapbox/vector-tile-spec, 2.1, gelesen am 2026-11-22): die Teilmenge, die eine Karte zum Zeichnen braucht — Schichten,
 * Merkmale mit Art, Tags und Geometrie. Er liest aus einem entpackten Byte-Feld (die Packung der Kachel loest [PmTilesArchive] auf) und **wirft nie**: ein Formfehler oder eine Grenzverletzung
 * macht die ganze Kachel zu `null` („unbrauchbar“); ein einzelnes Merkmal mit unsinniger Geometrie oder unsinnigen Tags wird ausgelassen.
 */

enum class TileGeometryType { UNKNOWN, POINT, LINESTRING, POLYGON }

/**
 * Ein Merkmal. [parts] sind bei Linien die Linienzuege, bei Flaechen die Ringe (aeusserer und innere, ohne wiederholten ersten Punkt: ein Ring ist geschlossen), bei Punkten je ein Punkt;
 * jedes Teil ist ein `IntArray` mit abwechselnd x und y in Kachelkoordinaten (0 bis [TileLayer.extent], y waechst nach unten, Werte ausserhalb sind der Rand der Nachbarkachel).
 * [tags] hat Text, `Long`, `Double` oder `Boolean` als Werte.
 */
class TileFeature(val type: TileGeometryType, val tags: Map<String, Any>, val parts: List<IntArray>) {
    fun string(key: String): String? = tags[key] as? String
}

class TileLayer(val name: String, val extent: Int, val features: List<TileFeature>)

class VectorTile(val layers: List<TileLayer>) {
    fun layer(name: String): TileLayer? = layers.firstOrNull { it.name == name }
}

// ── Grenzen (gegen kaputte oder boesartige Kacheln) ────────────────────────────────────────────────────────────────

internal const val MAX_LAYERS = 64
internal const val MAX_FEATURES_PER_LAYER = 100_000
internal const val MAX_KEYS_PER_LAYER = 100_000
internal const val MAX_VALUES_PER_LAYER = 100_000
internal const val MAX_POINTS_PER_TILE = 2_000_000

/** So gross darf ein Koordinatenwert sein (Kacheln haben Erweiterungen bis einige zehntausend plus Rand); das verhindert Ueberlaeufe beim Aufsummieren der Schritte. */
internal const val MAX_COORDINATE = 1 shl 24
private const val DEFAULT_EXTENT = 4096
private const val MAX_EXTENT = 1 shl 20

private const val TILE_LAYERS = 3

private const val LAYER_NAME = 1
private const val LAYER_FEATURES = 2
private const val LAYER_KEYS = 3
private const val LAYER_VALUES = 4
private const val LAYER_EXTENT = 5
private const val LAYER_VERSION = 15

private const val FEATURE_TAGS = 2
private const val FEATURE_TYPE = 3
private const val FEATURE_GEOMETRY = 4

private const val VALUE_STRING = 1
private const val VALUE_FLOAT = 2
private const val VALUE_DOUBLE = 3
private const val VALUE_INT = 4
private const val VALUE_UINT = 5
private const val VALUE_SINT = 6
private const val VALUE_BOOL = 7

private const val CMD_MOVE_TO = 1
private const val CMD_LINE_TO = 2
private const val CMD_CLOSE_PATH = 7

/** Dekodiert eine Kachel; `null`, wenn sie kein gueltiges Protobuf ist oder eine Grenze reisst. */
fun decodeVectorTile(bytes: ByteArray): VectorTile? = decodeVectorTile(bytes, MAX_POINTS_PER_TILE, MAX_FEATURES_PER_LAYER)

/** Wie [decodeVectorTile] mit eigenen Grenzen (die Tests pruefen sie mit kleinen Werten, ohne Millionen Punkte zu bauen). */
internal fun decodeVectorTile(bytes: ByteArray, maxPoints: Int, maxFeaturesPerLayer: Int): VectorTile? = try {
    val budget = PointBudget(maxPoints)
    val reader = ProtoReader(bytes)
    val layers = ArrayList<TileLayer>()
    while (true) {
        val (field, wire) = reader.nextTag() ?: break
        if (field == TILE_LAYERS && wire == WIRE_LENGTH) {
            if (layers.size >= MAX_LAYERS) throw MalformedException("zu viele Schichten")
            decodeLayer(reader.lengthDelimited(), budget, maxFeaturesPerLayer)?.let(layers::add)
        } else {
            reader.skip(wire)
        }
    }
    VectorTile(layers)
} catch (e: MalformedException) {
    null
}

private class PointBudget(var remaining: Int) {
    fun take(n: Int) {
        remaining -= n
        if (remaining < 0) throw MalformedException("zu viele Punkte")
    }
}

/** Ein Merkmal, wie es im Datenstrom steht: die Tags noch als Indizes, bis Schluessel und Werte der Schicht bekannt sind (sie koennen hinter den Merkmalen stehen). */
private class RawFeature(val type: TileGeometryType, val tags: LongArray, val parts: List<IntArray>)

private fun decodeLayer(r: ProtoReader, budget: PointBudget, maxFeatures: Int): TileLayer? {
    var name: String? = null
    var extent = DEFAULT_EXTENT
    var version = 1L
    val keys = ArrayList<String>()
    val values = ArrayList<Any?>()
    val raws = ArrayList<RawFeature>()
    while (true) {
        val (field, wire) = r.nextTag() ?: break
        when {
            field == LAYER_NAME && wire == WIRE_LENGTH -> name = r.string()
            field == LAYER_VERSION && wire == WIRE_VARINT -> version = r.varint()
            field == LAYER_EXTENT && wire == WIRE_VARINT -> {
                val e = r.varint()
                if (e <= 0 || e > MAX_EXTENT) throw MalformedException("Erweiterung $e")
                extent = e.toInt()
            }
            field == LAYER_KEYS && wire == WIRE_LENGTH -> {
                if (keys.size >= MAX_KEYS_PER_LAYER) throw MalformedException("zu viele Schluessel")
                keys.add(r.string())
            }
            field == LAYER_VALUES && wire == WIRE_LENGTH -> {
                if (values.size >= MAX_VALUES_PER_LAYER) throw MalformedException("zu viele Werte")
                values.add(decodeValue(r.lengthDelimited()))
            }
            field == LAYER_FEATURES && wire == WIRE_LENGTH -> {
                if (raws.size >= maxFeatures) throw MalformedException("zu viele Merkmale")
                decodeFeature(r.lengthDelimited(), budget)?.let(raws::add)
            }
            else -> r.skip(wire)
        }
    }
    // Eine unbekannte Hauptfassung duerfen wir ueberspringen (Spezifikation); der Rest der Kachel bleibt brauchbar.
    if (version != 1L && version != 2L) return null
    val layerName = name ?: throw MalformedException("Schicht ohne Namen")
    val features = ArrayList<TileFeature>(raws.size)
    for (raw in raws) {
        val tags = resolveTags(raw.tags, keys, values) ?: continue // unsinnige Tags: das Merkmal faellt weg
        features.add(TileFeature(raw.type, tags, raw.parts))
    }
    return TileLayer(layerName, extent, features)
}

/** Die Tags eines Merkmals: Paare (Schluessel-Index, Wert-Index). `null` bei ungerader Zahl oder einem Index ausserhalb. */
private fun resolveTags(tags: LongArray, keys: List<String>, values: List<Any?>): Map<String, Any>? {
    if (tags.size % 2 != 0) return null
    if (tags.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, Any>(tags.size)
    var i = 0
    while (i < tags.size) {
        val k = tags[i]
        val v = tags[i + 1]
        if (k < 0 || k >= keys.size || v < 0 || v >= values.size) return null
        val value = values[v.toInt()] ?: return null
        out[keys[k.toInt()]] = value
        i += 2
    }
    return out
}

/** Ein Wert (genau ein Feld ist gesetzt); `null` fuer einen leeren oder unbekannten Wert. */
private fun decodeValue(r: ProtoReader): Any? {
    var value: Any? = null
    while (true) {
        val (field, wire) = r.nextTag() ?: break
        when {
            field == VALUE_STRING && wire == WIRE_LENGTH -> value = r.string()
            field == VALUE_FLOAT && wire == WIRE_FIXED32 -> value = java.lang.Float.intBitsToFloat(r.fixed32()).toDouble()
            field == VALUE_DOUBLE && wire == WIRE_FIXED64 -> value = java.lang.Double.longBitsToDouble(r.fixed64())
            field == VALUE_INT && wire == WIRE_VARINT -> value = r.varint()
            field == VALUE_UINT && wire == WIRE_VARINT -> value = r.varint()
            field == VALUE_SINT && wire == WIRE_VARINT -> value = zigZag64(r.varint())
            field == VALUE_BOOL && wire == WIRE_VARINT -> value = r.varint() != 0L
            else -> r.skip(wire)
        }
    }
    return value
}

private fun zigZag64(n: Long): Long = (n ushr 1) xor -(n and 1)

private fun decodeFeature(r: ProtoReader, budget: PointBudget): RawFeature? {
    var type = TileGeometryType.UNKNOWN
    var tags = LongArray(0)
    var geometry = LongArray(0)
    while (true) {
        val (field, wire) = r.nextTag() ?: break
        when {
            field == FEATURE_TAGS && wire == WIRE_LENGTH -> tags = packedVarints(r.lengthDelimited())
            field == FEATURE_TAGS && wire == WIRE_VARINT -> tags += r.varint()
            field == FEATURE_TYPE && wire == WIRE_VARINT -> type = when (r.varint()) {
                1L -> TileGeometryType.POINT
                2L -> TileGeometryType.LINESTRING
                3L -> TileGeometryType.POLYGON
                else -> TileGeometryType.UNKNOWN
            }
            field == FEATURE_GEOMETRY && wire == WIRE_LENGTH -> geometry = packedVarints(r.lengthDelimited())
            field == FEATURE_GEOMETRY && wire == WIRE_VARINT -> geometry += r.varint()
            else -> r.skip(wire)
        }
    }
    if (type == TileGeometryType.UNKNOWN) return null
    val parts = decodeGeometry(type, geometry, budget) ?: return null
    return RawFeature(type, tags, parts)
}

private fun packedVarints(r: ProtoReader): LongArray {
    val out = ArrayList<Long>()
    while (r.hasMore()) {
        out.add(r.varint())
        if (out.size > MAX_POINTS_PER_TILE * 2 + 8) throw MalformedException("zu viele Werte")
    }
    return out.toLongArray()
}

/**
 * Die Geometrie eines Merkmals aus den Befehlen (Abschnitt 4.3 der Spezifikation). `null`, wenn sie unsinnig ist (das Merkmal faellt dann weg); zu kleine Teile (eine Linie mit einem Punkt,
 * ein Ring mit zwei) werden ausgelassen, die uebrigen Teile bleiben.
 */
private fun decodeGeometry(type: TileGeometryType, g: LongArray, budget: PointBudget): List<IntArray>? {
    val parts = ArrayList<IntArray>()
    var current: ArrayList<Int>? = null
    var cx = 0L
    var cy = 0L

    fun finish() {
        val c = current ?: return
        val points = c.size / 2
        val minPoints = when (type) {
            TileGeometryType.POINT -> 1
            TileGeometryType.LINESTRING -> 2
            else -> 3
        }
        if (points >= minPoints) {
            budget.take(points)
            parts.add(c.toIntArray())
        }
        current = null
    }

    var i = 0
    while (i < g.size) {
        val command = g[i++]
        val id = (command and 7).toInt()
        val count = command ushr 3
        when (id) {
            CMD_MOVE_TO, CMD_LINE_TO -> {
                if (count <= 0 || count > (g.size - i) / 2) return null
                if (id == CMD_MOVE_TO && type != TileGeometryType.POINT && count != 1L) return null
                for (n in 0 until count.toInt()) {
                    cx += zigZag32(g[i++])
                    cy += zigZag32(g[i++])
                    if (cx !in -MAX_COORDINATE..MAX_COORDINATE || cy !in -MAX_COORDINATE..MAX_COORDINATE) return null
                    if (id == CMD_MOVE_TO) {
                        finish()
                        current = ArrayList<Int>().also { it.add(cx.toInt()); it.add(cy.toInt()) }
                        if (type == TileGeometryType.POINT) finish()
                    } else {
                        (current ?: return null).apply { add(cx.toInt()); add(cy.toInt()) }
                    }
                }
            }
            CMD_CLOSE_PATH -> {
                if (count != 1L || type != TileGeometryType.POLYGON || current == null) return null
                finish()
            }
            else -> return null
        }
    }
    // Ein Linienzug und ein Punkt enden mit dem Ende der Befehle; ein Ring muss mit ClosePath enden (sonst ist die Geometrie unvollstaendig).
    if (type == TileGeometryType.POLYGON && current != null) return null
    finish()
    return parts
}

private fun zigZag32(n: Long): Long {
    if (n < 0 || n > 0xFFFF_FFFFL) return Long.MAX_VALUE // ausserhalb: loest die Grenze der Koordinate aus
    return (n ushr 1) xor -(n and 1)
}
