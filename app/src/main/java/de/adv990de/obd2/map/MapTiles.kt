package de.adv990de.obd2.map

/**
 * Die eingespielte Karte, wie der Zeichner sie braucht: der Leser [archive] und was wir ueber die Datei wissen ([info]). [coversArea] sagt, ob das Rechteck eines Tracks die Karte beruehrt;
 * ohne Beruehrung gibt es keine Kachel, und die Oberflaeche sagt „Karte deckt diesen Track nicht ab“. Die Datei nennt nur ihre Ausdehnung als Rechteck, nicht, wo wirklich Kacheln liegen.
 */
class MapTiles(val archive: PmTilesArchive, val info: MapInfo) {
    fun coversArea(south: Double, west: Double, north: Double, east: Double): Boolean = info.overlaps(south, west, north, east)
}
