package de.adv990de.obd2.track

import java.util.Locale
import kotlin.math.roundToLong

/*
 * Die Statuszeile beim Nachfahren (Mitte der Ambient-Statuszeile, s. `AmbientStatusRow`): zeigt, ob man auf dem Track ist und wie weit es noch ist, auch wenn gerade kein Roadbook-HUD steht.
 * Rein, ohne Android. **Positionen stehen in keinem Log.**
 */

/** Bis zu diesem Abstand zum Track (Meter) gilt man als „auf dem Track“; ein GPS-Fix schwankt um 5 bis 15 m, 10 m wuerde bei jedem Fix wechseln. */
internal const val FOLLOW_STATUS_ON_TRACK_M = 20.0

/** Wer auf dem Track ist, bleibt es bis zu diesem Abstand (Meter): die Totzone gegen Flackern an der Grenze. */
internal const val FOLLOW_STATUS_OFF_TRACK_M = 30.0

/** Was die Statuszeile zeigt. */
sealed interface FollowStatus {
    /** Die Standortberechtigung fehlt. */
    data object NoLocation : FollowStatus

    /** Noch keine brauchbare Position. */
    data object Waiting : FollowStatus

    /** Auf dem Track; [remainingM] ist der Rest bis zum Ziel. */
    data class OnTrack(val remainingM: Double) : FollowStatus

    /** Auf dem Track und das Ziel ist erreicht (weniger als [ROADBOOK_ARRIVED_M] Rest). */
    data object Arrived : FollowStatus

    /** Abseits; [distanceM] ist der Abstand zum Track (Luftlinie zum naechsten Punkt). */
    data class OffTrack(val distanceM: Double) : FollowStatus
}

/**
 * Macht aus einer [FollowReading] den Status. Er merkt sich, ob man zuletzt auf dem Track war (Totzone [FOLLOW_STATUS_ON_TRACK_M] bis [FOLLOW_STATUS_OFF_TRACK_M]); ohne Position
 * beginnt er neu.
 */
class FollowStatusTracker {
    private var wasOnTrack = false

    fun update(reading: FollowReading): FollowStatus = when (reading) {
        FollowReading.NoPermission -> {
            wasOnTrack = false
            FollowStatus.NoLocation
        }
        FollowReading.Searching -> {
            wasOnTrack = false
            FollowStatus.Waiting
        }
        is FollowReading.Following -> {
            val distance = reading.result.distanceToTrackM
            val onTrack = distance <= if (wasOnTrack) FOLLOW_STATUS_OFF_TRACK_M else FOLLOW_STATUS_ON_TRACK_M
            wasOnTrack = onTrack
            when {
                !onTrack -> FollowStatus.OffTrack(distance)
                reading.result.remainingM < ROADBOOK_ARRIVED_M -> FollowStatus.Arrived
                else -> FollowStatus.OnTrack(reading.result.remainingM)
            }
        }
    }
}

/**
 * Eine Entfernung fuer die Zeile: unter einem Kilometer in Metern auf 5 m gerundet („85 m“), ab einem Kilometer mit einer Nachkommastelle in der Sprache des Geraetes („12,4 km“). Eine
 * Zahl, die nicht endlich oder negativ ist, zeigt „0 m“.
 */
fun formatFollowDistance(meters: Double, locale: Locale): String {
    val m = if (meters.isFinite() && meters > 0.0) meters else 0.0
    val rounded = (m / 5.0).roundToLong() * 5L
    return if (rounded >= 1_000L) String.format(locale, "%.1f km", m / 1000.0) else "$rounded m"
}
