package de.adv990de.obd2

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo

/**
 * Wer den Vordergrunddienst braucht: die OBD-Sitzung ([obd]) und/oder die Streckenaufzeichnung ([track]). **Ein** Dienst, solange mindestens einer etwas will; das Trennen des Adapters
 * beendet eine laufende Aufzeichnung nicht und umgekehrt. Der Zustand steht nur im Speicher (`ObdServiceController`): ein Prozessende beendet Dienst und Aufzeichnung, nichts startet von selbst neu.
 */
data class ForegroundDemand(val obd: Boolean = false, val track: Boolean = false) {
    val any: Boolean get() = obd || track
}

/** Welche Benachrichtigung der Dienst zeigt. */
enum class NotificationKind { OBD, TRACK, BOTH }

/** Die Benachrichtigung zum Bedarf; `null`, wenn es keinen gibt (dann laeuft kein Dienst). */
fun notificationKind(demand: ForegroundDemand): NotificationKind? = when {
    demand.obd && demand.track -> NotificationKind.BOTH
    demand.obd -> NotificationKind.OBD
    demand.track -> NotificationKind.TRACK
    else -> null
}

/**
 * Der Dienst-Typ zum Bedarf. `connectedDevice` nur mit [ForegroundDemand.obd] (kein Bluetooth-Typ ohne Bluetooth-Nutzung); `location` bei [ForegroundDemand.track] **oder**, wenn die OBD-Sitzung
 * ihn will ([obdWantsLocation] = `shouldIncludeLocationServiceType`: GPS-Geschwindigkeit an und ein Standort erteilt — ohne diese Berechtigung wirft `startForeground()` ab API 34). Ohne Bedarf 0.
 */
@SuppressLint("InlinedApi")
internal fun foregroundServiceType(demand: ForegroundDemand, obdWantsLocation: Boolean): Int {
    var type = 0
    if (demand.obd) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    if (demand.track || (demand.obd && obdWantsLocation)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    return type
}

/** Ob [type] den Typ `location` einschliesst. */
@SuppressLint("InlinedApi")
internal fun hasLocationType(type: Int): Boolean = type and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0
