package de.adv990de.obd2.ui.dashboard

import kotlin.math.abs

/** Fahrzeugspezifische Getriebedaten fuer die virtuelle Ganganzeige. */
data class GearRatios(
    val wheelCircumferenceMm: Float,
    val primaryRatio: Float,
    val secondaryRatio: Float,
    /** Getriebe-interne Uebersetzung je Gang, Index 0 = 1. Gang ... Index 5 = 6. Gang. */
    val gearRatios: List<Float>,
)

/** Unter dieser Geschwindigkeit ist die GPS-Geschwindigkeit zu verrauscht fuer eine Aussage. */
private const val MIN_SPEED_KMH = 8.0f

/** Ein Gang wird nur "eingerastet" (neu uebernommen), wenn seine Abweichung darunter liegt. */
private const val ENTER_TOLERANCE = 0.04f

/** Ein bereits eingerasteter Gang bleibt stehen, solange seine EIGENE Abweichung darunter
 *  bleibt — unabhaengig davon, ob ein Nachbar-Gang gerade naeher liegt. Muss unter dem Abstand
 *  der am dichtesten benachbarten Gesamt-Uebersetzungen bleiben (hier 5./6. Gang, ~7.5%), sonst
 *  wuerde ein echter Gangwechsel zwischen ihnen nie erkannt. */
private const val STAY_TOLERANCE = 0.06f

/**
 * Bestimmt den aktuell eingelegten Gang aus Motordrehzahl und GPS-Geschwindigkeit.
 *
 * Reine Funktion ohne Android-/Coroutines-Bezug, direkt testbar (Projekt-Konvention fuer
 * Entscheidungslogik, vgl. `mergeGpsSpeed`, `dashboardStatusDotState`).
 *
 * Rueckgabe 1-basiert (1 = erster Gang ... 6 = sechster Gang), oder `null` wenn kein Gang mit
 * ausreichender Sicherheit bestimmbar ist.
 *
 * Hysterese (Schmitt-Trigger) gegen Flackern an eng benachbarten Uebersetzungen: Ein bereits
 * gesperrter Gang ([lastGear]) bleibt stehen, solange seine EIGENE Abweichung <= [STAY_TOLERANCE]
 * bleibt — dieser Vergleich zieht bewusst NIE die Abweichung eines anderen Gangs heran, sonst
 * wuerde genau das Flackern zurueckkommen, das die Hysterese verhindern soll. Erst wenn der
 * gesperrte Gang selbst nicht mehr passt (oder es noch keinen gab), wird der naechstliegende Gang
 * uebernommen — aber nur, wenn dessen Abweichung <= [ENTER_TOLERANCE] ist.
 */
fun determineGear(
    engineRpm: Float,
    speedKmh: Float?,
    lastGear: Int?,
    ratios: GearRatios,
): Int? {
    if (speedKmh == null || speedKmh < MIN_SPEED_KMH) return null
    val wheelRpm = speedKmh * 1_000_000f / (60f * ratios.wheelCircumferenceMm)
    if (wheelRpm <= 0f) return null

    val actualRatio = engineRpm / wheelRpm
    val deviations = ratios.gearRatios.mapIndexed { index, gearRatio ->
        val totalRatio = ratios.primaryRatio * ratios.secondaryRatio * gearRatio
        (index + 1) to abs(actualRatio - totalRatio) / totalRatio
    }

    val lastDeviation = lastGear?.let { g -> deviations.firstOrNull { it.first == g }?.second }
    if (lastDeviation != null && lastDeviation <= STAY_TOLERANCE) return lastGear

    val best = deviations.minByOrNull { it.second } ?: return null
    return if (best.second <= ENTER_TOLERANCE) best.first else null
}

/**
 * Findet Gangpaare, deren Gesamt-Uebersetzung so nah beieinander liegt (relative Abweichung
 * <= [STAY_TOLERANCE]), dass die Hysterese in [determineGear] einen echten Wechsel zwischen ihnen
 * nicht mehr zuverlaessig erkennen koennte — siehe deren KDoc: ein bereits gesperrter Gang bleibt
 * stehen, solange seine EIGENE Abweichung darunter bleibt, unabhaengig davon, ob der tatsaechliche
 * neue Gang naeher liegt. Prueft alle Paare, nicht nur indexbenachbarte, falls Gaenge nicht streng
 * monoton eingetragen wurden.
 *
 * Rueckgabe als 1-basierte Gangnummern-Paare (kleinere Nummer zuerst), leer wenn alle Gaenge
 * ausreichend Abstand haben.
 */
fun closeGearPairs(primaryRatio: Float, secondaryRatio: Float, gearRatios: List<Float>): List<Pair<Int, Int>> {
    val totals = gearRatios.mapIndexed { index, gearRatio -> (index + 1) to primaryRatio * secondaryRatio * gearRatio }
    val conflicts = mutableListOf<Pair<Int, Int>>()
    for (i in totals.indices) {
        for (j in i + 1 until totals.size) {
            val (gearA, ratioA) = totals[i]
            val (gearB, ratioB) = totals[j]
            val deviation = abs(ratioA - ratioB) / maxOf(ratioA, ratioB)
            if (deviation <= STAY_TOLERANCE) conflicts.add(gearA to gearB)
        }
    }
    return conflicts
}
