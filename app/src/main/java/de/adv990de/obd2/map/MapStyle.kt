package de.adv990de.obd2.map

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow

/*
 * Der Stil: aus einem Merkmal einer Vektorkachel wird eine Zeichenanweisung — oder nichts. Schichten und Werte stammen aus dem Schema der Protomaps-Basiskarte, Fassung 4 (docs.protomaps.com/
 * basemaps/layers, gelesen am 2026-11-22): `earth`, `water`, `landcover` (nur Stufen 0 bis 7), `landuse`, `roads`, mit `kind` und `kind_detail`. **Was der Stil nicht kennt, wird nicht gezeichnet**
 * (Ergebnis `null`) — er raet keine Farbe fuer einen unbekannten Wert.
 */

/** Wofuer die Karte gezeichnet wird: die Trackansicht (mehr Flaeche und Ruhe) oder das HUD (kleine Mini-Karte: Wald, Wiese, Wasser, Strassen; keine Fusswege, keine Acker). */
enum class MapProfile { TRACK_VIEW, HUD }

/** Eine Zeichenanweisung. [order] sortiert von unten (klein) nach oben (gross); gleiche Anweisungen (gleiche Farbe, Breite, Ordnung) lassen sich zu einem Pfad zusammenfassen. */
sealed interface DrawOp {
    val order: Int

    data class Fill(val argb: Int, override val order: Int) : DrawOp

    /** Eine Linie; [widthDp] ist die Breite auf dem Bildschirm in dp (nicht in Kachelkoordinaten) bei der Zoomstufe, mit der gefragt wurde. */
    data class Line(val argb: Int, val widthDp: Float, override val order: Int) : DrawOp
}

/** Die Zoomstufe, die ein Massstab ([pxPerUnit], Bildpunkte je Einheit der Ebene) fuer 512-Bildpunkt-Kacheln haette — stetig, auch ueber die hoechste Stufe der Quelle hinaus. */
fun effectiveZoom(pxPerUnit: Double): Double = if (pxPerUnit > 0 && pxPerUnit.isFinite()) ln(pxPerUnit * 2 * PI / 512.0) / ln(2.0) else 0.0

enum class RoadClass { HIGHWAY, MAJOR, MINOR, SERVICE, TRACK, PATH, RAIL }

private val DETAIL_SERVICE = setOf("service", "parking_aisle", "driveway", "alley", "drive-through", "emergency_access", "utility")
private val DETAIL_PATH = setOf("path", "footway", "cycleway", "bridleway", "pedestrian")
private val DETAIL_IGNORED = setOf("steps", "sidewalk", "crossing", "corridor", "irrigation", "disused", "slipway", "cable_car", "funicular", "pier", "runway", "taxiway")

/** Die Strassenklasse; `null` fuer das, was nie gezeichnet wird (Treppen, Gehwege, Faehren, Rollbahnen, Seilbahnen ...). Zuerst entscheidet `kind_detail`, dann `kind`. */
fun roadClass(kind: String?, detail: String?): RoadClass? {
    if (detail == "track") return RoadClass.TRACK
    if (detail != null && detail in DETAIL_IGNORED) return null
    if (kind == "rail") return RoadClass.RAIL
    if (detail != null && detail in DETAIL_SERVICE) return RoadClass.SERVICE
    if (detail != null && detail in DETAIL_PATH) return RoadClass.PATH
    return when (kind) {
        "highway" -> RoadClass.HIGHWAY
        "major_road" -> RoadClass.MAJOR
        "minor_road" -> RoadClass.MINOR
        "path" -> RoadClass.PATH
        else -> null
    }
}

enum class AreaClass { WOOD, GRASS, FARM, SCRUB }

private val LANDUSE_WOOD = setOf("forest", "wood")
private val LANDUSE_GRASS = setOf("meadow", "grass", "park", "recreation_ground", "garden", "dog_park")
private val LANDUSE_FARM = setOf("farmland", "farmyard", "orchard")
private val LANDUSE_SCRUB = setOf("scrub")

/** Die Flaechenklasse eines `landuse`-Werts; `null` fuer alles andere (Siedlung, Gewerbe, Schutzgebiete, Sportanlagen ...: sie bleiben Grund). */
fun landuseClass(kind: String?): AreaClass? = when (kind) {
    in LANDUSE_WOOD -> AreaClass.WOOD
    in LANDUSE_GRASS -> AreaClass.GRASS
    in LANDUSE_FARM -> AreaClass.FARM
    in LANDUSE_SCRUB -> AreaClass.SCRUB
    else -> null
}

/** Die Flaechenklasse eines `landcover`-Werts (Stufen 0 bis 7); `barren`, `glacier` und `urban_area` bleiben Grund. */
fun landcoverClass(kind: String?): AreaClass? = when (kind) {
    "forest" -> AreaClass.WOOD
    "grassland" -> AreaClass.GRASS
    "farmland" -> AreaClass.FARM
    "scrub" -> AreaClass.SCRUB
    else -> null
}

/** Die Linienbreite einer Strassenklasse in dp bei der stetigen Stufe [zoom]: wachsend mit der Stufe, nie unter einem halben dp. */
fun roadWidthDp(cls: RoadClass, zoom: Double): Float {
    val base = when (cls) {
        RoadClass.HIGHWAY -> 3.2
        RoadClass.MAJOR -> 2.6
        RoadClass.MINOR -> 1.8
        RoadClass.SERVICE -> 1.0
        RoadClass.TRACK -> 1.5
        RoadClass.PATH -> 0.9
        RoadClass.RAIL -> 1.0
    }
    val z = zoom.coerceIn(8.0, 20.0)
    return (base * 2.0.pow((z - 14.0) * 0.45)).coerceAtLeast(0.5).toFloat()
}

/** Der Stil fuer eine [palette] und ein [profile]. [op] liefert die Anweisung eines Merkmals der Schicht [layer] bei der stetigen Stufe [zoom]. */
class MapStyle(private val palette: MapPalette, val profile: MapProfile) {

    fun op(layer: String, feature: TileFeature, zoom: Double): DrawOp? = when (layer) {
        "landcover" -> if (feature.type == TileGeometryType.POLYGON) area(landcoverClass(feature.string("kind")), 10) else null
        "landuse" -> if (feature.type == TileGeometryType.POLYGON) area(landuseClass(feature.string("kind")), 20) else null
        "water" -> water(feature, zoom)
        "roads" -> if (feature.type == TileGeometryType.LINESTRING) road(roadClass(feature.string("kind"), feature.string("kind_detail")), zoom) else null
        else -> null // earth ist der Grund, Gebaeude, Namen, Orte, Grenzen und Sehenswuerdigkeiten werden nie gezeichnet
    }

    private fun area(cls: AreaClass?, baseOrder: Int): DrawOp? {
        cls ?: return null
        if (profile == MapProfile.HUD && (cls == AreaClass.FARM || cls == AreaClass.SCRUB)) return null
        return when (cls) {
            AreaClass.FARM -> DrawOp.Fill(palette.farm, baseOrder)
            AreaClass.SCRUB -> DrawOp.Fill(palette.scrub, baseOrder + 1)
            AreaClass.GRASS -> DrawOp.Fill(palette.grass, baseOrder + 2)
            AreaClass.WOOD -> DrawOp.Fill(palette.wood, baseOrder + 3)
        }
    }

    private fun water(feature: TileFeature, zoom: Double): DrawOp? = when (feature.type) {
        TileGeometryType.POLYGON -> DrawOp.Fill(palette.water, ORDER_WATER_FILL)
        TileGeometryType.LINESTRING -> when (feature.string("kind_detail")) {
            "river", "canal" -> DrawOp.Line(palette.water, roadWidthDp(RoadClass.MINOR, zoom), ORDER_WATER_LINE)
            "stream", "ditch", "drain" -> DrawOp.Line(palette.water, roadWidthDp(RoadClass.SERVICE, zoom), ORDER_WATER_LINE)
            else -> null
        }
        else -> null
    }

    private fun road(cls: RoadClass?, zoom: Double): DrawOp? {
        cls ?: return null
        if (profile == MapProfile.HUD && (cls == RoadClass.SERVICE || cls == RoadClass.PATH || cls == RoadClass.RAIL)) return null
        val (color, order) = when (cls) {
            RoadClass.RAIL -> palette.rail to ORDER_ROAD
            RoadClass.PATH -> palette.roadPath to ORDER_ROAD + 1
            RoadClass.TRACK -> palette.roadTrack to ORDER_ROAD + 2
            RoadClass.SERVICE -> palette.roadService to ORDER_ROAD + 3
            RoadClass.MINOR -> palette.roadMinor to ORDER_ROAD + 4
            RoadClass.MAJOR -> palette.roadMajor to ORDER_ROAD + 5
            RoadClass.HIGHWAY -> palette.roadHighway to ORDER_ROAD + 6
        }
        return DrawOp.Line(color, roadWidthDp(cls, zoom), order)
    }

    private companion object {
        const val ORDER_WATER_FILL = 30
        const val ORDER_WATER_LINE = 31
        const val ORDER_ROAD = 40
    }
}
