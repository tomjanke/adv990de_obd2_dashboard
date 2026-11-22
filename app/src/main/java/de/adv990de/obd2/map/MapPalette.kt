package de.adv990de.obd2.map

import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Die Farben der Karte, abgeleitet aus den Farben der App: jede Kartenfarbe ist der Grund ([background]) ein Stueck Richtung einer Zielfarbe (Strassen: [onBackground], Flaechen: ein Farbton
 * der Karte), und zwar **genau so weit, dass ein vorgegebenes Kontrastverhaeltnis zum Grund herauskommt**. So bleibt die Karte in jedem Farbschema (Cockpit-Dunkel, Standard hell und dunkel)
 * ein ruhiger Hintergrund: ihr Kontrast zum Grund ist kleiner als der der Track-Linie, die immer die klarste Form bleibt.
 */

/** Die Farbtoene der Kartenflaechen als ARGB-Ganzzahlen (feste Farbtoene, die Themendatei der App legt sie fest; die Staerke bestimmt [MapPalette.derive]). */
data class MapTints(val water: Int, val wood: Int, val grass: Int, val farm: Int, val scrub: Int)

/** Die abgeleiteten Farben (ARGB, deckend). */
data class MapPalette(
    val water: Int,
    val wood: Int,
    val grass: Int,
    val farm: Int,
    val scrub: Int,
    val roadHighway: Int,
    val roadMajor: Int,
    val roadMinor: Int,
    val roadService: Int,
    val roadTrack: Int,
    val roadPath: Int,
    val rail: Int,
) {
    companion object {
        /**
         * Die Palette fuer [profile]: Kontrastverhaeltnis jeder Farbe zu [background] nach [HUD_TARGETS] bzw. [TRACK_VIEW_TARGETS]. [onBackground] ist die Schriftfarbe auf dem Grund (die Strassen
         * ziehen dorthin). Ist ein Kontrast mit den Farben nicht zu erreichen, wird der Anteil auf [MAX_MIX] begrenzt (die Farbe bleibt dann am Grund). **Die Linie des Tracks ([line]) bleibt
         * die klarste Form:** jede Kartenfarbe hat gegen sie mindestens [LINE_MIN_CONTRAST]; wo das Ziel zum Grund das verletzte (helle Schemata, deren Linie nur mittelhell ist), wird die Farbe
         * schwaecher.
         */
        fun derive(background: Int, onBackground: Int, tints: MapTints, profile: MapProfile, line: Int): MapPalette {
            val t = if (profile == MapProfile.HUD) HUD_TARGETS else TRACK_VIEW_TARGETS
            fun toward(color: Int, ratio: Double) = mixToContrast(background, color, ratio, avoid = line, avoidMin = LINE_MIN_CONTRAST)
            return MapPalette(
                water = toward(tints.water, t.water), wood = toward(tints.wood, t.wood), grass = toward(tints.grass, t.grass),
                farm = toward(tints.farm, t.farm), scrub = toward(tints.scrub, t.scrub),
                roadHighway = toward(onBackground, t.roadHighway), roadMajor = toward(onBackground, t.roadMajor), roadMinor = toward(onBackground, t.roadMinor),
                roadService = toward(onBackground, t.roadService), roadTrack = toward(onBackground, t.roadTrack), roadPath = toward(onBackground, t.roadPath),
                rail = toward(onBackground, t.rail),
            )
        }
    }
}

/** Die Zielkontraste (WCAG-Verhaeltnis zum Grund) je Farbe. */
data class ContrastTargets(
    val water: Double, val wood: Double, val grass: Double, val farm: Double, val scrub: Double,
    val roadHighway: Double, val roadMajor: Double, val roadMinor: Double, val roadService: Double, val roadTrack: Double, val roadPath: Double, val rail: Double,
)

/** Im HUD: Strassen hoechstens 1,5:1 und Flaechen hoechstens 1,3:1 gegen den Grund (Konzept 6; meine Schaetzung, am Bild zu pruefen) — die Linie des Tracks bleibt der klarste Strich. */
val HUD_TARGETS = ContrastTargets(
    water = 1.25, wood = 1.2, grass = 1.12, farm = 1.1, scrub = 1.1,
    roadHighway = 1.5, roadMajor = 1.45, roadMinor = 1.35, roadService = 1.25, roadTrack = 1.4, roadPath = 1.3, rail = 1.25,
)

/** In der Trackansicht (mehr Platz, mehr Ruhe noetig): etwas kraeftiger, aber weiter deutlich unter der Linie des Tracks. */
val TRACK_VIEW_TARGETS = ContrastTargets(
    water = 1.45, wood = 1.35, grass = 1.2, farm = 1.15, scrub = 1.2,
    roadHighway = 2.4, roadMajor = 2.0, roadMinor = 1.7, roadService = 1.45, roadTrack = 1.9, roadPath = 1.7, rail = 1.5,
)

/** So viel der Zielfarbe darf in die Mischung hoechstens eingehen. */
const val MAX_MIX = 0.6

/** Das Mindestverhaeltnis jeder Kartenfarbe zur Linie des Tracks (etwas ueber den 3:1, die die Linie nach Konzept haben soll). */
const val LINE_MIN_CONTRAST = 3.2

// ── Farbrechnung (sRGB, WCAG 2.x) ──────────────────────────────────────────────────────────────────────────────────

private fun channel(c: Int) = c / 255.0
private fun linear(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

/** Die relative Leuchtdichte einer ARGB-Farbe (Alpha wird ignoriert). */
fun luminance(argb: Int): Double {
    val r = linear(channel((argb shr 16) and 0xFF))
    val g = linear(channel((argb shr 8) and 0xFF))
    val b = linear(channel(argb and 0xFF))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** Das Kontrastverhaeltnis zweier Farben, 1 bis 21. */
fun contrastRatio(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** Die Mischung `a · (1 − t) + b · t` je Kanal, deckend. */
fun mixArgb(a: Int, b: Int, t: Double): Int {
    fun ch(shift: Int): Int {
        val x = (a shr shift) and 0xFF
        val y = (b shr shift) and 0xFF
        return (x + (y - x) * t).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * Mischt [base] so weit Richtung [toward], dass das Kontrastverhaeltnis zu [base] [target] ist (Halbierungssuche; der Kontrast waechst mit dem Anteil, solange [toward] weiter von [base]
 * entfernt ist als die Mischung). Erreicht auch [MAX_MIX] das Ziel nicht, ist es die Mischung bei [MAX_MIX]; ist [target] hoechstens 1, bleibt es (von selbst) [base]. Mit [avoid] gilt zusaetzlich:
 * die Mischung hat gegen [avoid] mindestens [avoidMin] — dafuer wird der Anteil, wenn noetig, kleiner (der Kontrast zu [avoid] sinkt, je weiter die Mischung vom Grund wegwandert).
 */
fun mixToContrast(base: Int, toward: Int, target: Double, avoid: Int? = null, avoidMin: Double = 1.0): Int {
    // Halbierungssuche zwischen 0 und MAX_MIX; wird das Ziel auch bei MAX_MIX nicht erreicht, laeuft sie gegen MAX_MIX.
    var t: Double
    run {
        var lo = 0.0
        var hi = MAX_MIX
        repeat(40) {
            val mid = (lo + hi) / 2
            if (contrastRatio(base, mixArgb(base, toward, mid)) < target) lo = mid else hi = mid
        }
        t = (lo + hi) / 2
    }
    if (avoid != null && contrastRatio(avoid, mixArgb(base, toward, t)) < avoidMin) {
        var lo = 0.0
        var hi = t
        repeat(40) {
            val mid = (lo + hi) / 2
            if (contrastRatio(avoid, mixArgb(base, toward, mid)) >= avoidMin) lo = mid else hi = mid
        }
        t = lo
    }
    return mixArgb(base, toward, t)
}
