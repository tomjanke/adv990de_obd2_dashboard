package de.adv990de.obd2.ui.dashboard

/**
 * Die reine Rechnung des Lade-/Entlade-Balkens der Spannungs-Kachel (Idee F3, Entwurf `<intern>/superpowers/archive/specs/2026-10-19-voltage-gauge-design.md`): Zustand und Geometrie.
 * Ohne Compose und Android, damit sie im JVM-Test steht (Muster `determineGear`). Die Glättung über die Zyklen steht in [VoltageTrendTracker].
 */

/** Wohin die Batterie läuft — eine Aussage über die Lichtmaschine, nicht über die Gesundheit der Batterie. */
enum class ChargeState { Charging, Marginal, Discharging }

/**
 * Die geglättete Spannung und der Zustand, den die Kachel zeigt. [engineRunning] sagt, ob der Motor lief, als sie gemittelt wurde: bei laufendem Motor beschreibt der Zustand die Lichtmaschine
 * („lädt“, „lädt kaum“, „entlädt“); **vor dem Start** (verbunden, Motor aus, Nutzerwunsch 2026-10-19) zeigt der Balken nur die Spannung, und die Wörter wären falsch — „lädt kaum“ bei 12,9 V
 * und stehendem Motor lädt gar nichts. Der Balken malt beides gleich, nur die Beschreibung für Screenreader lässt das Wort dann weg.
 */
data class VoltageTrend(val volts: Float, val state: ChargeState, val engineRunning: Boolean = true)

// Skala des Balkens: 11 bis 15 V; die Nulllage liegt bei der Ruhespannung einer vollen 12-V-Batterie (Faustregel 12,6–12,8 V, für dieses Motorrad nicht gemessen).
internal const val VOLTAGE_BAR_MIN_V = 11f
internal const val VOLTAGE_BAR_MAX_V = 15f
internal const val VOLTAGE_BAR_ZERO_V = 12.7f

/** Ab dieser Spannung lädt die Lichtmaschine (Normalwert des Motorrads: Median 13,6–13,7 V). */
internal const val CHARGING_FROM_V = 13.3f

/** Die Nulllage ist zugleich die Grenze zum Entladen: darunter versorgt die Batterie die Verbraucher. */
internal const val DISCHARGING_BELOW_V = VOLTAGE_BAR_ZERO_V

// Hysterese 0,1 V = Auflösung des Steuergeräts. Als Literale statt Rechnung (13,3 − 0,1): Float-Rundung läge sonst an der Grenze selbst.
// Kleiner als die 0,2 V der Alarme, weil Füllung (Lage) und Farbe (Zustand) nur um die Auflösung auseinanderlaufen dürfen.
private const val CHARGING_LEAVE_BELOW_V = 13.2f
private const val DISCHARGING_LEAVE_FROM_V = 12.8f

/** Glättung: Median der Proben der letzten 10 s (seit 1.44.9; vorher Mittel), frühestens ab 3 Proben (bei 1,1–1,6 s Zyklus rund 4 s nach dem Beginn). */
internal const val TREND_WINDOW_MS = 10_000L
internal const val TREND_MIN_SAMPLES = 3

/**
 * Der Zustand zu einer **geglätteten** Spannung. [previous] ist der Zustand davor (null = keiner): ein Zustand wird erst verlassen, wenn der Wert die Grenze um die
 * Hysterese überschreitet (aus „lädt“ erst unter 13,2 V, aus „entlädt“ erst ab 12,8 V), damit die Farbe an einer Grenze nicht flackert.
 *
 * Verlangt einen endlichen Wert: für „kein Wert“ gibt es keinen Zustand — der Aufrufer prüft zuerst, ein erfundener Zustand wäre eine Aussage ohne Grundlage.
 */
internal fun chargeState(volts: Float, previous: ChargeState?): ChargeState {
    require(volts.isFinite()) { "volts must be finite, was $volts" }
    val chargingFrom = if (previous == ChargeState.Charging) CHARGING_LEAVE_BELOW_V else CHARGING_FROM_V
    val dischargingBelow = if (previous == ChargeState.Discharging) DISCHARGING_LEAVE_FROM_V else DISCHARGING_BELOW_V
    return when {
        volts >= chargingFrom -> ChargeState.Charging
        volts < dischargingBelow -> ChargeState.Discharging
        else -> ChargeState.Marginal
    }
}

/** Die Nulllage als Anteil der Balkenhöhe von unten (12,7 V auf der Skala 11–15 V = 42,5 %). */
internal const val VOLTAGE_BAR_ZERO_FRACTION = (VOLTAGE_BAR_ZERO_V - VOLTAGE_BAR_MIN_V) / (VOLTAGE_BAR_MAX_V - VOLTAGE_BAR_MIN_V)

/** Die Füllung des Balkens als Anteile der Höhe **von unten**, `fromFraction ≤ toFraction`: von der Nulllage bis zum Wert, darüber nach oben, darunter nach unten. */
internal data class VoltageBarFill(val fromFraction: Float, val toFraction: Float)

/** Die Füllung zu [volts]; Werte außerhalb der Skala werden an den Enden abgeschnitten, ein Wert ohne Zahl hat keine (null). */
internal fun voltageBarFill(volts: Float): VoltageBarFill? {
    if (!volts.isFinite()) return null
    val clamped = volts.coerceIn(VOLTAGE_BAR_MIN_V, VOLTAGE_BAR_MAX_V)
    val value = (clamped - VOLTAGE_BAR_MIN_V) / (VOLTAGE_BAR_MAX_V - VOLTAGE_BAR_MIN_V)
    return if (value >= VOLTAGE_BAR_ZERO_FRACTION) VoltageBarFill(VOLTAGE_BAR_ZERO_FRACTION, value) else VoltageBarFill(value, VOLTAGE_BAR_ZERO_FRACTION)
}
