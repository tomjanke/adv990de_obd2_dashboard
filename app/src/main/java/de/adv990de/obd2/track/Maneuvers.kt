package de.adv990de.obd2.track

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Die Richtungsaenderungen eines Tracks (das Roadbook) — rein, ohne Android. **Nur die Form der Linie:** das Roadbook kennt keine Strassen, eine Kurve und eine Kreuzung sehen
 * gleich aus, auf einer Kehrenstrasse loest jede Kehre aus. Die Schwellen sind Schaetzungen, abgestimmt an Tracks des Nutzers (s. ManeuversTest und CLAUDE.md).
 */

/** Wie scharf eine Richtungsaenderung ist; [DESTINATION] ist keine Kurve, sondern das Ende der Linie. */
enum class ManeuverKind { SLIGHT, NORMAL, SHARP, HAIRPIN, DESTINATION }

/**
 * Eine bedeutsame Richtungsaenderung: wo sie beginnt ([startAlongM]), wo sie am staerksten ist ([apexAlongM], dorthin misst das Roadbook die Entfernung) und wo sie endet ([endAlongM]),
 * jeweils in Metern vom Anfang der Linie. [turnDeg] ist die Summe der Richtungsaenderung in Grad: **positiv = rechts** (im Uhrzeigersinn), negativ = links; beim Ziel 0.
 */
data class Maneuver(val startAlongM: Double, val apexAlongM: Double, val endAlongM: Double, val turnDeg: Double, val kind: ManeuverKind) {
    val isDestination: Boolean get() = kind == ManeuverKind.DESTINATION
    val isRight: Boolean get() = turnDeg > 0
}

/** Der Abstand der Stuetzpunkte, auf die die Linie neu abgetastet wird (Meter): importierte GPX-Dateien haben oft wenige oder sehr ungleich verteilte Punkte. */
internal const val MANEUVER_STEP_M = 10.0

/** Die Richtung an einem Stuetzpunkt ist die der Sehne ueber so viele Stuetzpunkte davor und dahinter (2 = 20 m vor und zurueck): das glaettet das GPS-Rauschen von drei bis fuenf Metern. */
internal const val MANEUVER_CHORD_STEPS = 2

/** Die Richtungsaenderung wird ueber so viele Stuetzpunkte gemessen (5 = 50 m): eine Kurve, die sich ueber mehr Strecke zieht, ist ein Bogen, keine Abbiegung. */
internal const val MANEUVER_WINDOW_STEPS = 5

/** Ab dieser Aenderung (Grad) auf einem Fenster ist ein Stueck der Linie Teil einer Richtungsaenderung; mehr ist der Wert der Empfindlichkeit „auch leichte“. */
const val MANEUVER_CANDIDATE_MIN_DEG = 40.0

/** Gleichsinnige Richtungsaenderungen, die naeher zusammenliegen als das (Meter), gelten als eine. */
internal const val MANEUVER_MERGE_GAP_M = 40.0

/** Das Ziel zeigt das Roadbook nur, wenn die Linie mindestens so lang ist. */
internal const val DESTINATION_MIN_LINE_M = 100.0

/** Die Klasse einer Richtungsaenderung nach ihrem Winkel: unter 60 Grad leicht, bis 110 normal, bis 150 scharf, darueber eine Kehre. */
fun maneuverKindFor(turnDeg: Double): ManeuverKind {
    val a = abs(turnDeg)
    return when {
        a < 60.0 -> ManeuverKind.SLIGHT
        a < 110.0 -> ManeuverKind.NORMAL
        a < 150.0 -> ManeuverKind.SHARP
        else -> ManeuverKind.HAIRPIN
    }
}

/**
 * Die Richtungsaenderungen der [line] (in Fahrtrichtung) ab [MANEUVER_CANDIDATE_MIN_DEG], nach Strecke sortiert, am Ende das Ziel. Die Empfindlichkeit filtert erst die Anzeige
 * ([RoadbookSensitivity]): hier steht alles ab der kleinsten Schwelle, damit das Zusammenfassen nicht von der Einstellung abhaengt.
 *
 * 1. Die Linie wird als ebene Kette gerechnet (jedes Teilstueck in lokalen Metern) und auf [MANEUVER_STEP_M] neu abgetastet.
 * 2. Die Richtung je Stuetzpunkt ist die einer Sehne ([MANEUVER_CHORD_STEPS]); sie wird entfaltet (kein Sprung bei 360 Grad).
 * 3. Die Aenderung der Richtung ueber ein Fenster ([MANEUVER_WINDOW_STEPS]) ab der Schwelle gehoert zu einer Richtungsaenderung; aufeinanderfolgende Fenster gleichen Vorzeichens sind **eine**.
 * 4. Gleichsinnige Aenderungen mit weniger als [MANEUVER_MERGE_GAP_M] Abstand werden zusammengefasst, ein S (links, rechts) bleibt zwei.
 */
fun detectManeuvers(line: FollowLine): List<Maneuver> {
    if (!line.canFollow) return emptyList()
    val pts = line.points
    val n = pts.size
    val ex = DoubleArray(n)
    val ny = DoubleArray(n)
    for (i in 1 until n) {
        val a = pts[i - 1]
        val b = pts[i]
        val latMid = Math.toRadians((a.lat + b.lat) / 2)
        ex[i] = ex[i - 1] + Math.toRadians(wrapLonDegrees(b.lon - a.lon)) * cos(latMid) * EARTH_RADIUS_M
        ny[i] = ny[i - 1] + Math.toRadians(b.lat - a.lat) * EARTH_RADIUS_M
    }
    val cum = DoubleArray(n)
    for (i in 1 until n) cum[i] = cum[i - 1] + hypot(ex[i] - ex[i - 1], ny[i] - ny[i - 1])
    val planarTotal = cum[n - 1]
    if (planarTotal <= 0.0) return emptyList()
    // Neu abtasten.
    val samples = floor(planarTotal / MANEUVER_STEP_M).toInt() + 1
    if (samples < 2 * MANEUVER_CHORD_STEPS + MANEUVER_WINDOW_STEPS + 2) return destinationOnly(line)
    val sx = DoubleArray(samples)
    val sy = DoubleArray(samples)
    var seg = 0
    for (k in 0 until samples) {
        val s = min(k * MANEUVER_STEP_M, planarTotal)
        while (seg < n - 2 && cum[seg + 1] < s) seg++
        val len = cum[seg + 1] - cum[seg]
        val f = if (len > 0.0) ((s - cum[seg]) / len).coerceIn(0.0, 1.0) else 0.0
        sx[k] = ex[seg] + f * (ex[seg + 1] - ex[seg])
        sy[k] = ny[seg] + f * (ny[seg + 1] - ny[seg])
    }
    // Richtung je Stuetzpunkt (Sehne), entfaltet.
    val theta = DoubleArray(samples)
    var last = 0.0
    var haveLast = false
    for (k in 0 until samples) {
        val a = max(0, k - MANEUVER_CHORD_STEPS)
        val b = min(samples - 1, k + MANEUVER_CHORD_STEPS)
        val dx = sx[b] - sx[a]
        val dy = sy[b] - sy[a]
        val raw = if (hypot(dx, dy) < 1.0) null else Math.toDegrees(atan2(dx, dy))
        theta[k] = when {
            raw == null -> if (haveLast) last else 0.0
            !haveLast -> raw
            else -> last + shortest(last, raw)
        }
        last = theta[k]
        haveLast = true
    }
    // Aenderung ueber ein Fenster und die Laeufe gleichen Vorzeichens.
    val w = MANEUVER_WINDOW_STEPS
    class Run(var k0: Int, var k1: Int, val sign: Int, var peakK: Int, var peak: Double)
    val runs = ArrayList<Run>()
    var current: Run? = null
    for (k in 0 until samples - w) {
        val delta = theta[k + w] - theta[k]
        val sign = if (delta >= MANEUVER_CANDIDATE_MIN_DEG) 1 else if (delta <= -MANEUVER_CANDIDATE_MIN_DEG) -1 else 0
        val c = current
        if (sign == 0) {
            if (c != null) runs.add(c)
            current = null
        } else if (c != null && c.sign == sign) {
            c.k1 = k
            if (abs(delta) > c.peak) { c.peak = abs(delta); c.peakK = k }
        } else {
            if (c != null) runs.add(c)
            current = Run(k, k, sign, k, abs(delta))
        }
    }
    current?.let { runs.add(it) }
    // Gleichsinnige, nahe beieinanderliegende Laeufe zusammenfassen.
    val merged = ArrayList<Run>()
    for (r in runs) {
        val prev = merged.lastOrNull()
        val gapM = if (prev == null) Double.MAX_VALUE else (r.k0 - (prev.k1 + w)) * MANEUVER_STEP_M
        if (prev != null && prev.sign == r.sign && gapM < MANEUVER_MERGE_GAP_M) {
            prev.k1 = r.k1
            if (r.peak > prev.peak) { prev.peak = r.peak; prev.peakK = r.peakK }
        } else {
            merged.add(Run(r.k0, r.k1, r.sign, r.peakK, r.peak))
        }
    }
    // In Meter auf der Linie umrechnen (die ebene Kette ist so lang wie die Linie bis auf Rundung).
    val scale = if (planarTotal > 0.0) line.totalM / planarTotal else 1.0
    val out = ArrayList<Maneuver>()
    for (r in merged) {
        val endK = min(samples - 1, r.k1 + w)
        val turn = theta[endK] - theta[r.k0]
        if (abs(turn) < MANEUVER_CANDIDATE_MIN_DEG) continue
        out.add(
            Maneuver(
                startAlongM = r.k0 * MANEUVER_STEP_M * scale,
                apexAlongM = (r.peakK + w / 2.0) * MANEUVER_STEP_M * scale,
                endAlongM = endK * MANEUVER_STEP_M * scale,
                turnDeg = turn,
                kind = maneuverKindFor(turn),
            ),
        )
    }
    return out + destinationOnly(line)
}

private fun destinationOnly(line: FollowLine): List<Maneuver> =
    if (line.totalM >= DESTINATION_MIN_LINE_M) listOf(Maneuver(line.totalM, line.totalM, line.totalM, 0.0, ManeuverKind.DESTINATION)) else emptyList()

/** Der kuerzeste Winkel von [from] nach [to] in Grad (−180, 180]. */
private fun shortest(from: Double, to: Double): Double {
    val d = ((to - from) % 360.0 + 360.0) % 360.0
    return if (d > 180.0) d - 360.0 else d
}

/**
 * Wie viele Richtungsaenderungen das Roadbook zeigt (Einstellung „Empfindlichkeit“): nur scharfe ab 90 Grad, Standard ab 60 Grad, auch leichte ab 40 Grad. Die Zahl ist der gespeicherte Wert
 * (persistierter Zustand, nie umnummerieren).
 */
enum class RoadbookSensitivity(val level: Int, val minTurnDeg: Double) {
    ONLY_SHARP(1, 90.0),
    STANDARD(2, 60.0),
    ALSO_SLIGHT(3, MANEUVER_CANDIDATE_MIN_DEG),
    ;

    companion object {
        val OPTIONS: List<Int> = entries.map { it.level }
        const val DEFAULT_LEVEL = 2

        /** Die Stufe zu einer gespeicherten Zahl; alles Unbekannte ist der Standard. */
        fun fromLevel(level: Int): RoadbookSensitivity = entries.firstOrNull { it.level == level } ?: STANDARD
    }
}
