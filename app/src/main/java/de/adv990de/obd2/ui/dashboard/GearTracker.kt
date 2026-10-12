package de.adv990de.obd2.ui.dashboard

/** Was die Gang-Box zeigt: der Gang (`null` = "–") oder "N" ([isNeutral] schliesst einen Gang aus). */
internal data class GearReading(val gear: Int?, val isNeutral: Boolean) {
    companion object {
        val Unknown = GearReading(gear = null, isNeutral = false)
    }
}

/**
 * Die Ganganzeige ueber die Zyklen einer Verbindung: Gang, Anzeige-Persistenz, "N" und die Gegenprobe mit dem GPS. Herausgeloest aus
 * `DashboardViewModel`; die reinen Entscheidungen stehen in `GearIndicator.kt`, hier das, was sie von Zyklus zu Zyklus verbindet.
 *
 * Zustand ist nur der Beginn des Widerspruchs "ECU steht, GPS faehrt" ([standstillConflictSince], UID-19). Er gehoert wie die Sparkline zu EINER
 * Verbindung: [reset] bei Leerlauf, Verlust, Fehler und Veralten. [now] ist die monotone Uhr des Aufrufers.
 */
internal class GearTracker(private val now: () -> Long) {

    /** Beginn des Widerspruchs "ECU steht, GPS faehrt" ([standstillConflictSince]); `null` = keiner. */
    private var standstillConflictSinceMs: Long? = null

    fun reset() {
        standstillConflictSinceMs = null
    }

    /**
     * Berechnet die Anzeige fuer einen Zyklus. [previous] ist, was gerade angezeigt wird (Hysterese und Anzeige-Persistenz brauchen es).
     *
     * Der Gang kommt bewusst aus dem rohen GPS-Wert ([gpsSpeedKmh]), nicht aus [ecuSpeedKmh] (dem zusammengefuehrten Anzeigewert): `determineGear()`s
     * Annahmen (`MIN_SPEED_KMH` als GPS-Rauschgrenze, die 2-s-Staleness in `GpsSpeedSource`) sind auf GPS-Charakteristik zugeschnitten, nicht auf
     * die vom Fahrzeug gemeldete Geschwindigkeit. Fehlt das GPS (aus, keine Berechtigung, kein Fix), ist der Gang unbekannt, auch wenn das
     * Fahrzeug selbst eine Geschwindigkeit liefert.
     *
     * Anzeige-Persistenz (Nutzer-Feedback nach einer Testfahrt, v1.16.6): Kupplung ziehen oder Gas wegnehmen entkoppelt die Motordrehzahl kurz
     * von der Radgeschwindigkeit — `determineGear()` liefert dann fuer einen Zyklus `null`, obwohl das Fahrzeug erkennbar weiterrollt. Ohne
     * `holdGear()` spraenge die Anzeige kurz auf "–" und das Hysterese-Gedaechtnis ginge verloren (naechster Zyklus wieder `lastGear = null`): der
     * Gang muesste sich komplett neu einrasten (ENTER_TOLERANCE statt STAY_TOLERANCE), ein zaeher Wiedereinstieg. `holdGear()` haelt den zuletzt
     * angezeigten Gang, solange die Geschwindigkeit ueber `MIN_SPEED_KMH` bleibt, und loescht ihn nur bei echtem Stillstand oder fehlendem GPS-Fix.
     *
     * "N" nutzt bewusst [ecuSpeedKmh] (ECU zuerst): die ECU meldet den Stillstand sofort und ohne GPS-Fix, waehrend der Gang aus der
     * verzoegerten GPS-Geschwindigkeit noch einen Gang liefern kann. Dann gewinnt "N" und der Gang wird geleert (`isNeutral` => `gear == null`).
     * Gegenprobe mit dem rohen GPS (UID-19): meldet die ECU konstant 0, soll "N" nicht die ganze Fahrt stehen. Erst ein Widerspruch, der
     * ununterbrochen laenger dauert als das uebliche GPS-Nachhinken beim Anhalten, hebt "N" auf.
     */
    fun update(
        rpm: Float,
        ecuSpeedKmh: Float?,
        gpsSpeedKmh: Float?,
        previous: GearReading,
        ratios: GearRatios,
    ): GearReading {
        val computedGear = determineGear(rpm, gpsSpeedKmh, previous.gear, ratios)
        val checkedAt = now()
        standstillConflictSinceMs = standstillConflictSince(ecuSpeedKmh, gpsSpeedKmh, standstillConflictSinceMs, checkedAt)
        val neutral = isNeutral(
            rpm, ecuSpeedKmh, previous.isNeutral,
            gpsVeto = gpsVetoesNeutral(standstillConflictSinceMs, checkedAt),
        )
        val gear = if (neutral) null else holdGear(computedGear, gpsSpeedKmh, previous.gear)
        return GearReading(gear, neutral)
    }
}
