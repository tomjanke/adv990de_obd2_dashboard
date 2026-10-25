package de.adv990de.obd2.fuel

/**
 * Die Strecke der Tankanzeige im `DashboardViewModel`: macht aus den GPS-Geschwindigkeiten einer Sitzung ([sample]) die gefahrenen Kilometer ([DistanceIntegrator]) und gibt sie an die
 * Naht [FuelDistanceSink] weiter (Hilt bindet das [FuelRepository], Tests nehmen [FuelDistanceSink.None]). Herausgeloest aus dem ViewModel; [now] ist dessen monotone Uhr.
 *
 * Ein Intervall endet ([endInterval]) mit Leerlauf, Verbindungsverlust und Fehler — dann gibt es keine Strecke, die man raten duerfte, und der Zaehler wird gespeichert; **nicht** mit
 * "veraltet" und "eingefroren" (das GPS haengt nicht am Adapter, die Strecke zaehlt dort weiter, Entscheidung D4 vom 2026-10-24).
 */
internal class FuelDistanceTracker(
    private val sink: FuelDistanceSink,
    private val now: () -> Long,
) {
    private val integrator = DistanceIntegrator()

    /** Meldet die GPS-Geschwindigkeit [speedKmh] (die Strecke haengt an der Zeit und dem GPS, nicht an den OBD-Zyklen); gefahrene Kilometer gehen an die Tankanzeige. */
    fun sample(speedKmh: Float?) {
        val km = integrator.sample(speedKmh, now())
        if (km > 0f) sink.addDistance(km)
    }

    /** Ohne Verbindung gibt es keine Strecke: das Intervall endet (kein Raten ueber die Luecke) und der Zaehler wird gespeichert. */
    fun endInterval() {
        integrator.reset()
        sink.flush()
    }

    /** Schreibt den Zaehler jetzt in den Speicher (das ViewModel endet), ohne das Intervall zu beenden. */
    fun flush() = sink.flush()
}
