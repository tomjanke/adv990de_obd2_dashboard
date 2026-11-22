package de.adv990de.obd2.map

/** Eine Kachel im Web-Mercator-Raster: Stufe [z], Spalte [x] von Westen, Zeile [y] von Norden. */
data class TileKey(val z: Int, val x: Int, val y: Int) {
    /** Gueltig, wenn die Stufe bis [MAX_TILE_ZOOM] reicht und [x] und [y] in [0, 2^z) liegen. */
    val isValid: Boolean get() = z in 0..MAX_TILE_ZOOM && x >= 0 && y >= 0 && x < (1L shl z) && y < (1L shl z)
}

/** Die hoechste Stufe, die wir rechnen (PMTiles erlaubt mehr; 26 passt noch sicher in einen `Long` und ist weit ueber jeder Karte). */
const val MAX_TILE_ZOOM = 26

/**
 * Die PMTiles-Kennung einer Kachel: ihre Lage auf einer Reihe von Hilbert-Kurven, ab Stufe 0 fortlaufend gezaehlt (Stufe 0 = 0, Stufe 1 beginnt bei 1, Stufe 2 bei 5, Stufe z bei (4^z − 1) / 3).
 * `null` fuer eine ungueltige Kachel.
 *
 * Die Kurve ist die gewoehnliche Hilbert-Abbildung (x, y) → Abstand; ihre Lage gegen die Test-Werte der PMTiles-Spezifikation ist in `TileIdTest` festgehalten.
 */
fun tileId(key: TileKey): Long? {
    if (!key.isValid) return null
    val n = 1L shl key.z
    var x = key.x.toLong()
    var y = key.y.toLong()
    var d = 0L
    var s = n / 2
    while (s > 0) {
        val rx = if (x and s > 0) 1L else 0L
        val ry = if (y and s > 0) 1L else 0L
        d += s * s * ((3 * rx) xor ry)
        if (ry == 0L) {
            if (rx == 1L) {
                x = n - 1 - x
                y = n - 1 - y
            }
            val t = x
            x = y
            y = t
        }
        s /= 2
    }
    return ((1L shl (2 * key.z)) - 1) / 3 + d
}
