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

/** Unter dieser Geschwindigkeit gilt das Fahrzeug als stehend (Eintritt in "N"). Bewusst weit unter
 *  [MIN_SPEED_KMH]: dazwischen ist weder "N" noch ein Gang behauptbar, die Anzeige bleibt "–". */
private const val NEUTRAL_ENTER_SPEED_KMH = 2.0f

/** Ein bereits angezeigtes "N" bleibt, solange die Geschwindigkeit darunter bleibt — Schmitt-Trigger
 *  gegen GPS-Rauschen um [NEUTRAL_ENTER_SPEED_KMH]. */
private const val NEUTRAL_HOLD_SPEED_KMH = 4.0f

/** Ab dieser GPS-Geschwindigkeit widerspricht das GPS einem Stillstand der ECU — dieselbe Schwelle wie [MIN_SPEED_KMH], die
 *  GPS-Rauschgrenze: darunter ist GPS im Stand unzuverlaessig (Rauschen, Mehrwegeffekte), darueber ist "faehrt" belastbar. */
private const val NEUTRAL_GPS_CONFLICT_KMH = MIN_SPEED_KMH

/** So lange muss der Widerspruch ECU "steht" gegen GPS "faehrt" ununterbrochen bestehen, bevor das GPS "N" aufhebt. Das GPS
 *  hinkt beim Anhalten ein bis zwei Sekunden hinterher (die ECU meldet den Stillstand sofort) — das ist KEIN Widerspruch, sondern
 *  der Normalfall und darf "N" nicht verzoegern. Eine ECU, die konstant 0 meldet, widerspricht dem GPS dagegen dauerhaft. */
internal const val NEUTRAL_VETO_AFTER_MS = 5_000L

/** Unter dieser Drehzahl laeuft der Motor nicht (Zuendung aus, Anlasser dreht mit ~250 rpm). */
private const val ENGINE_RUNNING_MIN_RPM = 500f

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
    // Ein Gang mit Uebersetzung 0 ist abgeschaltet (B22: ein 5-Gang-Getriebe hat keinen sechsten) — er nimmt nicht teil.
    val deviations = ratios.gearRatios.mapIndexedNotNull { index, gearRatio ->
        if (gearRatio <= 0f) return@mapIndexedNotNull null
        val totalRatio = ratios.primaryRatio * ratios.secondaryRatio * gearRatio
        (index + 1) to abs(actualRatio - totalRatio) / totalRatio
    }

    val lastDeviation = lastGear?.let { g -> deviations.firstOrNull { it.first == g }?.second }
    if (lastDeviation != null && lastDeviation <= STAY_TOLERANCE) return lastGear

    val best = deviations.minByOrNull { it.second } ?: return null
    return if (best.second <= ENTER_TOLERANCE) best.first else null
}

/**
 * Anzeige-Persistenz fuer [determineGear]s Ergebnis: Ein einzelner unplausibler Zyklus (Kupplung
 * gezogen, Gas weg — die Motordrehzahl entkoppelt sich kurz von der Radgeschwindigkeit) soll die
 * Anzeige nicht sofort auf "unbekannt" zuruecksetzen, wie es ein Fahrer nicht erwartet. Nur ein
 * echter Stillstand (keine oder zu geringe Geschwindigkeit, dieselbe [MIN_SPEED_KMH]-Schwelle wie
 * in [determineGear]) loescht den gehaltenen Gang.
 *
 * [heldGear] ist der zuletzt ANGEZEIGTE Gang (nicht zwingend derselbe, den der Aufrufer als
 * `lastGear` an [determineGear] uebergeben hat — s. `DashboardViewModel.applyReadings()`, das
 * beide bewusst auf denselben Zustand zurueckfuehrt, damit die Hysterese nach einer gehaltenen
 * Anzeige weiterhin denselben Gang zuerst erneut zu bestaetigen versucht).
 */
fun holdGear(computedGear: Int?, speedKmh: Float?, heldGear: Int?): Int? = when {
    computedGear != null -> computedGear
    speedKmh == null || speedKmh < MIN_SPEED_KMH -> null
    else -> heldGear
}

/**
 * Leerlauf-Erkennung ("N"): das Fahrzeug steht und der Motor laeuft. Reine Funktion wie
 * [determineGear].
 *
 * OBD2 kennt weder Leerlaufschalter noch Gang-PID, "N" ist deshalb eine Heuristik und bedeutet
 * "steht, Motor laeuft": Leerlauf ODER Kupplung gezogen (an der Ampel nicht unterscheidbar).
 * Leerlauf im Rollen wird nicht erkannt.
 *
 * [speedKmh] ist die zusammengefuehrte Anzeige-Geschwindigkeit (ECU zuerst, GPS als Fallback), `null`
 * heisst unbekannt — dann nie "N" (anders als die Fahrterkennung, wo "fehlt" als "steht" gilt).
 * Hysterese: Eintritt unter [NEUTRAL_ENTER_SPEED_KMH], ein bereits angezeigtes "N" ([wasNeutral])
 * bleibt bis [NEUTRAL_HOLD_SPEED_KMH]. Das negierte `>=` macht eine NaN-Drehzahl zu "Motor aus".
 *
 * Plausibilitaet gegen das GPS (UID-19): [speedKmh] kommt zuerst von der ECU. Meldet sie konstant 0 (ein haengendes oder nie
 * befuelltes Geschwindigkeits-PID), stuende "N" waehrend der ganzen Fahrt. Hat das GPS der ECU lange genug widersprochen
 * ([gpsVetoesNeutral], aus [standstillConflictSince]), gilt kein "N" ([gpsVeto]) — und der Gang kommt aus dem GPS-Wert.
 */
fun isNeutral(engineRpm: Float, speedKmh: Float?, wasNeutral: Boolean, gpsVeto: Boolean = false): Boolean {
    if (gpsVeto || speedKmh == null || !(engineRpm >= ENGINE_RUNNING_MIN_RPM)) return false
    return speedKmh < if (wasNeutral) NEUTRAL_HOLD_SPEED_KMH else NEUTRAL_ENTER_SPEED_KMH
}

/**
 * Seit wann widerspricht das GPS dem Stillstand der ECU? [sinceMs] ist der bisherige Beginn des Widerspruchs (`null` = keiner),
 * [nowMs] die monotone Zeit. Ein Widerspruch besteht, solange die ECU ein Stehen meldet ([speedKmh] unter der Haltegrenze
 * von "N") UND das GPS ([gpsSpeedKmh], roh, `null` ohne Fix) mindestens [NEUTRAL_GPS_CONFLICT_KMH] zeigt; NaN und negative Werte sind
 * kein Beleg. Endet er auch nur fuer einen Zyklus, beginnt die Zaehlung von vorn. Rueckgabe: der (bestehende oder neue) Beginn,
 * oder `null`, wenn kein Widerspruch besteht.
 */
fun standstillConflictSince(speedKmh: Float?, gpsSpeedKmh: Float?, sinceMs: Long?, nowMs: Long): Long? {
    val ecuStanding = speedKmh != null && speedKmh < NEUTRAL_HOLD_SPEED_KMH
    val gpsMoving = gpsSpeedKmh != null && gpsSpeedKmh >= NEUTRAL_GPS_CONFLICT_KMH
    return if (ecuStanding && gpsMoving) sinceMs ?: nowMs else null
}

/** Hebt das GPS "N" auf? Erst, wenn der Widerspruch seit mindestens [NEUTRAL_VETO_AFTER_MS] besteht ([conflictSinceMs] aus [standstillConflictSince]). */
fun gpsVetoesNeutral(conflictSinceMs: Long?, nowMs: Long): Boolean =
    conflictSinceMs != null && nowMs - conflictSinceMs >= NEUTRAL_VETO_AFTER_MS

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
    // Abgeschaltete Gaenge (Uebersetzung 0, B22) liegen in keinem Paar — sonst teilte die Abweichung durch null.
    val totals = gearRatios.mapIndexedNotNull { index, gearRatio ->
        if (gearRatio <= 0f) null else (index + 1) to primaryRatio * secondaryRatio * gearRatio
    }
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
