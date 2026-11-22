package de.adv990de.obd2.map

/*
 * Aus einer dekodierten Vektorkachel wird eine Szene: die Zeichenanweisungen des Stils, nach gleicher Anweisung zusammengefasst und von unten nach oben geordnet. Die Szene kennt kein Android
 * (nur Zahlen in Kachelkoordinaten), damit jede Regel JVM-getestet ist; die Pfade und das Zeichnen stehen in `ui/tracks/MapTileLayer.kt`.
 */

/**
 * Alle Teile einer Anweisung [op] in einer Kachel. [parts] sind Folgen von x, y in Kachelkoordinaten: bei einer Flaeche die Ringe (aeusserer und innere; gezeichnet mit der Gerade-Ungerade-Regel,
 * damit innere Ringe Loecher sind), bei einer Linie die Linienzuege.
 */
class SceneGroup(val op: DrawOp, val parts: List<FloatArray>)

/** Eine Kachel, bereit zum Zeichnen: [groups] nach [DrawOp.order] aufsteigend, [extent] die Kantenlaenge der Kachelkoordinaten. */
class TileScene(val extent: Int, val groups: List<SceneGroup>) {
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * Baut die Szene von [tile] mit dem [style]. [zoom] ist die Stufe, die der Stil fuer die Linienbreiten annimmt (die Stufe der Kachel). Merkmale, die der Stil nicht zeichnet, fehlen; Teile mit
 * zu wenigen Punkten (Linie unter zwei, Ring unter drei) entfallen. Die Schichten kommen in der Reihenfolge der Kachel, die Gruppen werden stabil nach [DrawOp.order] sortiert.
 */
fun buildTileScene(tile: VectorTile, style: MapStyle, zoom: Double): TileScene {
    val groups = LinkedHashMap<DrawOp, MutableList<FloatArray>>()
    var extent = DEFAULT_SCENE_EXTENT
    for (layer in tile.layers) {
        extent = layer.extent
        for (feature in layer.features) {
            val op = style.op(layer.name, feature, zoom) ?: continue
            val minPoints = if (op is DrawOp.Fill) 3 else 2
            for (part in feature.parts) {
                val n = part.size / 2
                if (n < minPoints) continue
                groups.getOrPut(op) { ArrayList() }.add(FloatArray(n * 2) { part[it].toFloat() })
            }
        }
    }
    val sorted = groups.entries.sortedBy { it.key.order }.map { SceneGroup(it.key, it.value) }
    return TileScene(extent, sorted)
}

private const val DEFAULT_SCENE_EXTENT = 4096
