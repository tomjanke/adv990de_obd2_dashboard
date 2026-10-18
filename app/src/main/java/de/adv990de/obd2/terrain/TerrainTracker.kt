package de.adv990de.obd2.terrain

/**
 * Fasst Hoehe, Steigung und Peilung zusammen — jede Regel steht im jeweiligen Schaetzer, hier wird nur gereicht. Gehoert zu EINER Verbindung: [reset] bei Verbindungsende
 * (der Verlauf des Graphen und die Kalibrierung gelten nicht ueber eine Luecke hinweg). Wie `GearTracker` und `OilBarTracker` im `DashboardViewModel` gehalten.
 */
class TerrainTracker {

    private val elevation = ElevationEstimator()
    private val grade = GradeEstimator()
    private val heading = HeadingEstimator()

    fun update(nowMs: Long, input: TerrainInput): TerrainDisplay = TerrainDisplay(
        altitudeM = elevation.update(nowMs, input),
        gradePercent = grade.update(nowMs, input),
        headingDeg = heading.update(nowMs, input.fix),
        gradeHistory = grade.history,
    )

    fun reset() {
        elevation.reset()
        grade.reset()
        heading.reset()
    }
}
