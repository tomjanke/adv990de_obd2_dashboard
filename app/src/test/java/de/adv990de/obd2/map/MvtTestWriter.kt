package de.adv990de.obd2.map

import java.io.ByteArrayOutputStream

/**
 * Baut Vektorkacheln (Mapbox Vector Tile 2.1) fuer Tests, nach der Spezifikation und **ohne** Code des Dekoders: eigener Protobuf-Schreiber, eigene Geometrie-Befehle.
 */
internal object MvtTestWriter {

    class UInt64(val value: Long)
    class SInt64(val value: Long)

    class Proto {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (x < 0 || x >= 0x80) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun varintField(field: Int, v: Long) { tag(field, 0); varint(v) }
        fun bytesField(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun stringField(field: Int, s: String) = bytesField(field, s.toByteArray(Charsets.UTF_8))
        fun bytes(): ByteArray = out.toByteArray()
    }

    fun zigzag(n: Int): Long = ((n shl 1) xor (n shr 31)).toLong() and 0xFFFF_FFFFL
    fun command(id: Int, count: Int): Long = ((count shl 3) or id).toLong()

    /** Geometrie-Befehle mit **absoluten** Koordinaten als Eingabe; die Schritte rechnet der Schreiber aus. */
    class Geom {
        val values = ArrayList<Long>()
        private var cx = 0
        private var cy = 0
        private fun step(x: Int, y: Int) { values += zigzag(x - cx); values += zigzag(y - cy); cx = x; cy = y }
        fun moveTo(vararg pts: Pair<Int, Int>): Geom { values += command(1, pts.size); pts.forEach { step(it.first, it.second) }; return this }
        fun lineTo(vararg pts: Pair<Int, Int>): Geom { values += command(2, pts.size); pts.forEach { step(it.first, it.second) }; return this }
        fun close(count: Int = 1): Geom { values += command(7, count); return this }
        fun raw(vararg v: Long): Geom { v.forEach { values += it }; return this }
    }

    fun geom(block: Geom.() -> Unit): Geom = Geom().apply(block)

    class Feature(val type: Int, val geometry: Geom, val tags: Map<String, Any> = emptyMap(), val id: Long? = null, val packed: Boolean = true, val rawTags: List<Long>? = null)

    class Layer(
        val name: String?,
        val features: List<Feature>,
        val extent: Int? = 4096,
        val version: Int? = 2,
        val keysBeforeFeatures: Boolean = false,
        val unknownFields: Boolean = false,
    )

    private fun value(v: Any): ByteArray {
        val p = Proto()
        when (v) {
            is String -> p.stringField(1, v)
            is Float -> { p.tag(2, 5); val b = java.lang.Float.floatToIntBits(v); for (i in 0 until 4) p.out.write((b ushr (8 * i)) and 0xFF) }
            is Double -> { p.tag(3, 1); val b = java.lang.Double.doubleToLongBits(v); for (i in 0 until 8) p.out.write(((b ushr (8 * i)) and 0xFF).toInt()) }
            is Long -> p.varintField(4, v)
            is UInt64 -> p.varintField(5, v.value)
            is SInt64 -> p.varintField(6, (v.value shl 1) xor (v.value shr 63))
            is Boolean -> p.varintField(7, if (v) 1 else 0)
            else -> error("Wert $v")
        }
        return p.bytes()
    }

    fun layer(l: Layer): ByteArray {
        val keys = LinkedHashMap<String, Int>()
        val values = ArrayList<Any>()
        val features = l.features.map { f ->
            val tags = ArrayList<Long>()
            if (f.rawTags != null) tags += f.rawTags else f.tags.forEach { (k, v) ->
                tags += keys.getOrPut(k) { keys.size }.toLong()
                val idx = values.indexOfFirst { it == v && it::class == v::class }.let { if (it >= 0) it else { values += v; values.size - 1 } }
                tags += idx.toLong()
            }
            val p = Proto()
            f.id?.let { p.varintField(1, it) }
            if (f.packed) {
                if (tags.isNotEmpty()) { val t = Proto(); tags.forEach(t::varint); p.bytesField(2, t.bytes()) }
            } else tags.forEach { p.varintField(2, it) }
            p.varintField(3, f.type.toLong())
            if (f.packed) { val g = Proto(); f.geometry.values.forEach(g::varint); p.bytesField(4, g.bytes()) }
            else f.geometry.values.forEach { p.varintField(4, it) }
            p.bytes()
        }
        val p = Proto()
        l.version?.let { p.varintField(15, it.toLong()) }
        l.name?.let { p.stringField(1, it) }
        if (l.unknownFields) { p.varintField(99, 7); p.stringField(100, "x"); p.tag(101, 1); repeat(8) { p.out.write(1) }; p.tag(102, 5); repeat(4) { p.out.write(2) } }
        fun keysAndValues() {
            keys.keys.forEach { p.stringField(3, it) }
            values.forEach { p.bytesField(4, value(it)) }
        }
        if (l.keysBeforeFeatures) keysAndValues()
        features.forEach { p.bytesField(2, it) }
        if (!l.keysBeforeFeatures) keysAndValues()
        l.extent?.let { p.varintField(5, it.toLong()) }
        return p.bytes()
    }

    fun tile(vararg layers: Layer, unknownTileFields: Boolean = false): ByteArray {
        val p = Proto()
        layers.forEach { p.bytesField(3, layer(it)) }
        if (unknownTileFields) { p.varintField(16, 1); p.stringField(17, "ext") }
        return p.bytes()
    }

    fun point(x: Int, y: Int, tags: Map<String, Any> = emptyMap()) = Feature(1, geom { moveTo(x to y) }, tags)
    fun line(vararg pts: Pair<Int, Int>, tags: Map<String, Any> = emptyMap()) = Feature(2, geom { moveTo(pts.first()); lineTo(*pts.drop(1).toTypedArray()) }, tags)
    fun ring(g: Geom, vararg pts: Pair<Int, Int>) { g.moveTo(pts.first()); g.lineTo(*pts.drop(1).toTypedArray()); g.close() }
    fun polygon(vararg rings: List<Pair<Int, Int>>, tags: Map<String, Any> = emptyMap()) =
        Feature(3, geom { rings.forEach { r -> ring(this, *r.toTypedArray()) } }, tags)
}
