package de.adv990de.obd2.map

/**
 * Das kleine Karten-Archiv der Geraete-Tests (`app/src/androidTest/assets/map/sample.pmtiles`), erfunden und deterministisch gebaut: ein Block von 3 x 3 Kacheln der Stufe 12 um die Kachel
 * ([TILE_X], [TILE_Y]); in jeder Kachel ein Wald ueber die ganze Flaeche, darauf ein Gewaesser links unten (Viertel) und eine Hauptstrasse waagerecht bei 7/16 der Hoehe von oben; ohne
 * Packung, damit die Bytes nicht von der JDK-Version abhaengen. Der Mittelpunkt der mittleren Kachel ist ([centerLat], [centerLon]): dort stossen Wald und Gewaesser aneinander. `DeviceMapFixtureTest` haelt die Datei gleich diesem Aufbau (mit `REGENERATE_MAP_FIXTURE=1` wird sie neu geschrieben).
 */
internal object DeviceMapFixture {
    const val ZOOM = 12
    const val TILE_X = 2162
    const val TILE_Y = 1383

    private fun planeCenterY() = (tileNorthY(TILE_Y, ZOOM) + tileNorthY(TILE_Y + 1, ZOOM)) / 2

    /** Die Breite des Mittelpunkts der mittleren Kachel in Grad. */
    fun centerLat(): Double = de.adv990de.obd2.ui.tracks.unprojectLat(planeCenterY())

    /** Die Laenge des Mittelpunkts der mittleren Kachel in Grad. */
    fun centerLon(): Double = (tileWestDeg(TILE_X, ZOOM) + tileWestDeg(TILE_X + 1, ZOOM)) / 2

    /** Das Rechteck des Blocks in Grad: Sued, West, Nord, Ost. */
    fun bbox(): DoubleArray = doubleArrayOf(
        de.adv990de.obd2.ui.tracks.unprojectLat(tileNorthY(TILE_Y + 2, ZOOM)), tileWestDeg(TILE_X - 1, ZOOM),
        de.adv990de.obd2.ui.tracks.unprojectLat(tileNorthY(TILE_Y - 1, ZOOM)), tileWestDeg(TILE_X + 2, ZOOM),
    )

    private val SQUARE = listOf(0 to 0, 4096 to 0, 4096 to 4096, 0 to 4096)
    private val LAKE = listOf(0 to 2048, 2048 to 2048, 2048 to 4096, 0 to 4096)

    private fun tileBytes() = MvtTestWriter.tile(
        MvtTestWriter.Layer("landuse", listOf(MvtTestWriter.polygon(SQUARE, tags = mapOf("kind" to "forest")))),
        MvtTestWriter.Layer("water", listOf(MvtTestWriter.polygon(LAKE, tags = mapOf("kind" to "lake")))),
        MvtTestWriter.Layer("roads", listOf(MvtTestWriter.line(0 to 1792, 4096 to 1792, tags = mapOf("kind" to "major_road")))),
    )

    fun center(): TileKey = TileKey(ZOOM, TILE_X, TILE_Y)

    fun bytes(): ByteArray {
        val c = center()
        val tiles = ArrayList<PmTilesTestWriter.Tile>()
        for (dx in -1..1) for (dy in -1..1) tiles.add(PmTilesTestWriter.Tile(TileKey(ZOOM, c.x + dx, c.y + dy), tileBytes()))
        return PmTilesTestWriter.archive(
            tiles, internal = 1, tileCompression = 1, minZoom = ZOOM, maxZoom = ZOOM,
            metadata = """{"name":"Testkarte","attribution":"erfunden"}""", bbox = bbox().let { doubleArrayOf(it[1], it[0], it[3], it[2]) }, // PMTiles: West, Sued, Ost, Nord
        )
    }
}
