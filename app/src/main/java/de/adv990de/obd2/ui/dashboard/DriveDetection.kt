package de.adv990de.obd2.ui.dashboard

/**
 * Ab dieser Geschwindigkeit beginnt eine Fahrt. Darunter liegt das Rauschen eines GPS-Empfaengers im
 * Stand (gemessene Zacken bis zu einigen km/h; dieselbe Groessenordnung, ab der `determineGear` dem
 * GPS-Wert nicht traut) — bis 1.24.2 genuegte jeder Wert ueber 0 und das Hauptmenue sprang im Stand
 * an der Ampel hin und her.
 */
internal const val MOVING_ENTER_KMH = 5f

/**
 * Unter dieser Geschwindigkeit endet eine laufende Fahrt. Bewusst unter [MOVING_ENTER_KMH]: Im Stau
 * (6 -> 4 km/h) bleibt es Fahrt, und ein einzelner Zacken im Stand kippt die Anzeige nicht zurueck.
 */
internal const val MOVING_LEAVE_KMH = 3f

/**
 * Fahrterkennung mit Hysterese: Eine Fahrt beginnt ab [MOVING_ENTER_KMH] und endet erst unter
 * [MOVING_LEAVE_KMH]; dazwischen entscheidet der bisherige Zustand [wasMoving].
 *
 * Eine fehlende oder unplausible Geschwindigkeit (null, NaN, unendlich, negativ) gilt als "steht" —
 * die Navigationsleiste soll nie dauerhaft ausgeblendet bleiben, nur weil eine Quelle schweigt.
 *
 * Reine Funktion ohne Android-Bezug, wie [isNeutral] und [determineGear].
 */
fun isMoving(speedKmh: Float?, wasMoving: Boolean): Boolean {
    if (speedKmh == null || speedKmh.isNaN() || speedKmh.isInfinite() || speedKmh < 0f) return false
    return speedKmh >= if (wasMoving) MOVING_LEAVE_KMH else MOVING_ENTER_KMH
}
