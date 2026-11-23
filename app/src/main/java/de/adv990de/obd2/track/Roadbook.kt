package de.adv990de.obd2.track

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/*
 * Das Roadbook beim Nachfahren: wann das HUD erscheint (RoadbookTracker) und wie weit die Karte nach Geschwindigkeit zoomt (AutoZoom) — rein, ohne Android, Zeiten als Parameter.
 * **Positionen sind sensibel:** nichts hiervon geht in ein Log.
 */

/** So viele Sekunden voraus zeigt das HUD die naechste Richtungsaenderung, bei hoher Geschwindigkeit also weiter weg. */
internal const val ROADBOOK_LOOKAHEAD_S = 18.0

/** Naeher als das (Meter) blendet das HUD nie ein, auch im Stand nicht; weiter als das ([ROADBOOK_MAX_TRIGGER_M]) auch bei hoher Geschwindigkeit nicht. */
internal const val ROADBOOK_MIN_TRIGGER_M = 200.0
internal const val ROADBOOK_MAX_TRIGGER_M = 1_000.0

/** Ein einmal sichtbares HUD bleibt, bis die Entfernung um diesen Faktor ueber der Einblendschwelle liegt (kein Flackern an der Grenze, wenn die Geschwindigkeit schwankt). */
internal const val ROADBOOK_HYSTERESIS = 1.1

/** Mindestens so lange (Millisekunden) bleibt ein sichtbares HUD stehen, auch wenn die Entfernung die Schwelle wieder ueberschreitet. */
internal const val ROADBOOK_MIN_DWELL_MS = 3_000L

/** Das HUD zeigt nur, solange der Abstand zum Track hoechstens so gross ist (Meter); ein sichtbares darf bis [ROADBOOK_OFF_TRACK_HIDE_M] abweichen, darueber blendet es aus, statt Falsches zu zeigen. */
internal const val ROADBOOK_OFF_TRACK_SHOW_M = 60.0
internal const val ROADBOOK_OFF_TRACK_HIDE_M = 100.0

/** Eine Richtungsaenderung gilt als durchfahren, wenn man so weit (Meter) hinter ihrem **Scheitelpunkt** ist; 0 = das HUD verschwindet, sobald die Entfernung 0 m erreicht. */
internal const val ROADBOOK_PASSED_M = 0.0

/** Das Ziel gilt als erreicht, wenn der Rest kuerzer ist (Meter); dann blendet das HUD aus. */
internal const val ROADBOOK_ARRIVED_M = 20.0

/** Folgt innerhalb dieser Strecke (Meter) auf die gezeigte Richtungsaenderung eine weitere, steht sie klein als „danach“ dabei. */
internal const val ROADBOOK_NEXT_WITHIN_M = 120.0

/** Der Ausschnitt der Karte im HUD: so viele Meter vor und hinter der Position beginnt er, und so weit reicht er hinter die Richtungsaenderung. */
internal const val ROADBOOK_PATH_BEHIND_M = 60.0
internal const val ROADBOOK_PATH_BEYOND_M = 120.0

/** Die Deckkraft des HUD: blass bei der Einblendschwelle, voll bei 40 Prozent der Schwelle. */
internal const val ROADBOOK_MIN_ALPHA = 0.6f

/** Bis zu welcher Entfernung (Meter vor der Richtungsaenderung) das HUD bei [speedKmh] einblendet; ohne Geschwindigkeit der kleinste Wert. */
fun roadbookTriggerM(speedKmh: Float?): Double {
    val v = (speedKmh ?: 0f).coerceAtLeast(0f) / 3.6
    return (v * ROADBOOK_LOOKAHEAD_S).coerceIn(ROADBOOK_MIN_TRIGGER_M, ROADBOOK_MAX_TRIGGER_M)
}

/**
 * Was das HUD zeigt: die Richtungsaenderung [maneuver] in [distanceM] Metern (bis zu ihrem Scheitelpunkt), der Balken fuellt sich von 0 bei [triggerM] bis 1 an der Stelle. [next] ist die
 * folgende Richtungsaenderung, wenn sie kurz danach kommt ([nextDistanceM] Meter nach der ersten). [path] ist der Ausschnitt des Tracks fuer die kleine Karte (von [ROADBOOK_PATH_BEHIND_M] hinter
 * der Position bis [ROADBOOK_PATH_BEYOND_M] hinter der Richtungsaenderung), [apex] ihr Scheitelpunkt, [position] die eigene Position, [bearingDeg] der Kurs des Tracks dort (er steht in der Karte oben).
 */
data class RoadbookHud(
    val maneuver: Maneuver,
    val distanceM: Double,
    val triggerM: Double,
    val next: Maneuver?,
    val nextDistanceM: Double?,
    val path: List<TrackPoint>,
    val apex: TrackPoint,
    val position: TrackPoint,
    val bearingDeg: Double,
) {
    /** Wie weit man schon ist: 0 bei der Einblendschwelle, 1 an der Richtungsaenderung. */
    val progress: Float get() = if (triggerM <= 0.0) 1f else (1.0 - distanceM / triggerM).coerceIn(0.0, 1.0).toFloat()

    /** Die Deckkraft: dezent beim Einblenden ([ROADBOOK_MIN_ALPHA]), voll, sobald nur noch 40 Prozent der Schwelle uebrig sind. */
    val alpha: Float get() = (ROADBOOK_MIN_ALPHA + (1f - ROADBOOK_MIN_ALPHA) * (progress / 0.6f).coerceIn(0f, 1f))
}

/**
 * Entscheidet je Position, ob und was das HUD zeigt. Die [maneuvers] kommen von [detectManeuvers] (nach Strecke sortiert, am Ende das Ziel), [line] ist dieselbe Linie. Ein Tracker gehoert zu
 * einer Linie und einer Richtung; er merkt sich nur, ob das HUD gerade steht (fuer die Totbaender und die Mindestdauer).
 */
class RoadbookTracker(private val maneuvers: List<Maneuver>, private val line: FollowLine) {
    private var visible = false
    private var visibleSinceMs = 0L
    private var shownApexM = Double.NaN
    private var lastSpeedKmh: Float? = null

    private var filterKey = Double.NaN
    private var filtered: List<Maneuver> = emptyList()

    private fun maneuversFor(minTurnDeg: Double): List<Maneuver> {
        if (filterKey != minTurnDeg) {
            filterKey = minTurnDeg
            filtered = maneuvers.filter { it.isDestination || abs(it.turnDeg) >= minTurnDeg }
        }
        return filtered
    }

    /** Das HUD fuer [result] (die Position auf der Linie) oder `null`; [nowMs] ist eine monotone Zeit, [minTurnDeg] die Empfindlichkeit ([RoadbookSensitivity.minTurnDeg]). */
    fun update(result: FollowResult, position: TrackPoint, speedKmh: Float?, nowMs: Long, minTurnDeg: Double): RoadbookHud? {
        if (speedKmh != null) lastSpeedKmh = speedKmh
        val limitOffTrack = if (visible) ROADBOOK_OFF_TRACK_HIDE_M else ROADBOOK_OFF_TRACK_SHOW_M
        if (result.distanceToTrackM > limitOffTrack) return hide()
        val list = maneuversFor(minTurnDeg)
        val along = result.alongM
        // Die erste Richtungsaenderung, deren Scheitelpunkt noch vor uns liegt (das Ziel gilt kurz davor als erreicht).
        val index = firstAhead(list, along)
        if (index < 0) return hide()
        val target = list[index]
        if (target.isDestination && result.remainingM < ROADBOOK_ARRIVED_M) return hide()
        val distance = (target.apexAlongM - along).coerceAtLeast(0.0)
        val trigger = roadbookTriggerM(speedKmh ?: lastSpeedKmh)
        val limit = if (visible && shownApexM == target.apexAlongM) trigger * ROADBOOK_HYSTERESIS else trigger
        val show = if (distance <= limit) {
            true
        } else {
            visible && shownApexM == target.apexAlongM && nowMs - visibleSinceMs < ROADBOOK_MIN_DWELL_MS
        }
        if (!show) return hide()
        if (!visible || shownApexM != target.apexAlongM) {
            if (!visible) visibleSinceMs = nowMs
            visible = true
            shownApexM = target.apexAlongM
        }
        val next = list.getOrNull(index + 1)?.takeIf { it.apexAlongM - target.apexAlongM <= ROADBOOK_NEXT_WITHIN_M && !target.isDestination }
        return RoadbookHud(
            maneuver = target,
            distanceM = distance,
            triggerM = trigger,
            next = next,
            nextDistanceM = next?.let { it.apexAlongM - target.apexAlongM },
            path = pathAround(along, target),
            apex = line.pointAt(target.apexAlongM),
            position = position,
            bearingDeg = result.bearingDeg,
        )
    }

    private fun hide(): RoadbookHud? {
        visible = false
        shownApexM = Double.NaN
        return null
    }

    /** Der Index der ersten Richtungsaenderung, deren Scheitelpunkt [along] noch nicht um [ROADBOOK_PASSED_M] hinter sich hat; −1, wenn es keine mehr gibt. */
    private fun firstAhead(list: List<Maneuver>, along: Double): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].apexAlongM + ROADBOOK_PASSED_M > along) hi = mid else lo = mid + 1
        }
        return if (lo < list.size) lo else -1
    }

    /** Die Punkte der Linie von [ROADBOOK_PATH_BEHIND_M] hinter [along] bis [ROADBOOK_PATH_BEYOND_M] hinter dem Ende von [target]. */
    private fun pathAround(along: Double, target: Maneuver): List<TrackPoint> {
        val from = (along - ROADBOOK_PATH_BEHIND_M).coerceAtLeast(0.0)
        val to = (target.endAlongM + ROADBOOK_PATH_BEYOND_M).coerceAtMost(line.totalM)
        val first = line.segmentAt(from)
        val last = line.segmentAt(to) + 1
        return line.points.subList(first.coerceAtLeast(0), (last + 1).coerceAtMost(line.points.size))
    }
}

/** So viele Meter zeigt die kuerzere Seite der Karte bei [speedKmh] (Auto-Zoom): `(60 m + 14 s * v) / 0,65`, zwischen 150 m und 1500 m; ohne Geschwindigkeit 300 m. */
fun autoZoomTargetSpanM(speedKmh: Float?): Double {
    if (speedKmh == null) return AUTO_ZOOM_UNKNOWN_SPAN_M
    val v = speedKmh.coerceAtLeast(0f) / 3.6
    return ((AUTO_ZOOM_HEAD_M + AUTO_ZOOM_LOOKAHEAD_S * v) / AUTO_ZOOM_AHEAD_SHARE).coerceIn(AUTO_ZOOM_MIN_SPAN_M, AUTO_ZOOM_MAX_SPAN_M)
}

internal const val AUTO_ZOOM_HEAD_M = 60.0
internal const val AUTO_ZOOM_LOOKAHEAD_S = 14.0

/** Der Anteil der Kartenhoehe vor der Position in der Ansicht „Fahrtrichtung oben“ (die Position steht bei 0,65 der Hoehe von oben, voraus liegen also 65 Prozent). */
internal const val AUTO_ZOOM_AHEAD_SHARE = 0.65
internal const val AUTO_ZOOM_MIN_SPAN_M = 150.0
internal const val AUTO_ZOOM_MAX_SPAN_M = 1_500.0
internal const val AUTO_ZOOM_UNKNOWN_SPAN_M = 300.0

/** Zeitkonstante der Glaettung (Sekunden) und Totband (Verhaeltnis): die Spanne aendert sich erst, wenn der geglaettete Zielwert um mehr als 15 Prozent abweicht. */
internal const val AUTO_ZOOM_TAU_S = 4.0
internal const val AUTO_ZOOM_DEADBAND = 1.15

/**
 * Die Spanne der Karte nach der Geschwindigkeit, geglaettet: im Logarithmus mit [AUTO_ZOOM_TAU_S] gegen den Zielwert, und sie springt nur, wenn sie um mehr als [AUTO_ZOOM_DEADBAND]
 * abweicht (kein „Atmen“ an einer Ampel). Ohne Geschwindigkeit bleibt der letzte Zielwert.
 */
class AutoZoom {
    private var logSmooth = Double.NaN
    private var shown = Double.NaN
    private var lastMs = 0L
    private var lastTarget = AUTO_ZOOM_UNKNOWN_SPAN_M

    /** Die Spanne in Metern fuer die Geschwindigkeit [speedKmh] zur monotonen Zeit [nowMs]. */
    fun update(speedKmh: Float?, nowMs: Long): Double {
        if (speedKmh != null) lastTarget = autoZoomTargetSpanM(speedKmh)
        val logTarget = ln(lastTarget)
        if (logSmooth.isNaN()) {
            logSmooth = logTarget
            shown = lastTarget
            lastMs = nowMs
            return shown
        }
        val dt = ((nowMs - lastMs) / 1000.0).coerceIn(0.0, 5.0)
        lastMs = nowMs
        logSmooth += (1.0 - exp(-dt / AUTO_ZOOM_TAU_S)) * (logTarget - logSmooth)
        val smooth = exp(logSmooth)
        if (abs(ln(smooth / shown)) > ln(AUTO_ZOOM_DEADBAND)) shown = smooth
        return shown
    }
}
