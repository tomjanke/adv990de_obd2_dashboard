package de.adv990de.obd2.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.ObdForegroundService
import de.adv990de.obd2.R
import javax.inject.Inject

/**
 * Die Id des Alarm-Kanals. **Persistierter Zustand — nie umbenennen:** der Nutzer stellt Ton und Nicht stören in den Systemeinstellungen dieses Kanals ein; eine andere Id
 * legte einen zweiten Kanal an und ließe seine Wahl beim alten zurück.
 */
internal const val ALARM_CHANNEL_ID = "obd2_alarms"

// Je Art eine feste Benachrichtigungs-Id: ein neuer Alarm derselben Art ersetzt den alten, die Arten stehen nebeneinander. Die Id 1 gehört dem Vordergrunddienst.
private const val NOTIFICATION_ID_VOLTAGE = 2001
private const val NOTIFICATION_ID_COOLANT = 2002
private const val NOTIFICATION_ID_FAULT_CODE = 2003

/**
 * Legt den Kanal der Alarme an (idempotent — Android ignoriert ein erneutes Anlegen mit gleicher Id, und was der Nutzer am Kanal eingestellt hat, bleibt). Wie
 * [de.adv990de.obd2.createObdNotificationChannel] eine Funktion für `Obd2Application` UND die Tests. `IMPORTANCE_HIGH` mit Vibration: ein Alarm muss sich bemerkbar machen
 * (Heads-up), der Nutzer behält die Hoheit über Ton und Nicht stören. Kein SDK_INT-Guard: minSdk 26 hat Kanäle auf jedem Gerät.
 */
fun createAlarmNotificationChannel(context: Context) {
    val channel = NotificationChannel(ALARM_CHANNEL_ID, context.getString(R.string.notif_alarm_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
        enableVibration(true)
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

/**
 * Wird überhaupt gepostet? Nur wenn Benachrichtigungen erlaubt sind (`areNotificationsEnabled()`: ab Android 13 auch die Berechtigung `POST_NOTIFICATIONS`). Eine kleine eigene
 * Entscheidung, weil sich der gesperrte Zweig am Gerät nicht erzwingen lässt (die Berechtigung zur Laufzeit zu entziehen beendet den Testprozess); das Band bleibt dann die
 * einzige Anzeige.
 */
internal fun shouldPost(enabled: Boolean): Boolean = enabled

internal fun notificationId(kind: AlarmKind): Int = when (kind) {
    AlarmKind.VOLTAGE -> NOTIFICATION_ID_VOLTAGE
    AlarmKind.COOLANT -> NOTIFICATION_ID_COOLANT
    AlarmKind.FAULT_CODE -> NOTIFICATION_ID_FAULT_CODE
}

/**
 * Die Systembenachrichtigung eines Alarms. Der Text steht fest zum Zeitpunkt des Auslösens (kein Live-Wert — wer mehr sehen will, tippt und sieht das Band). Kategorie
 * `CATEGORY_STATUS`, **nicht** `CATEGORY_ALARM`: die dürfte Nicht stören umgehen, das entscheidet der Nutzer am Kanal. Antippen öffnet die App ([ObdForegroundService.openAppIntent]
 * bringt eine laufende Activity nach vorn), `setAutoCancel(true)` räumt sie dabei ab. Das kleine Symbol ist ein Warndreieck (Entscheidung des Nutzers); das Bluetooth-Symbol
 * gehört dem Vordergrunddienst, beide stehen nebeneinander in der Statusleiste.
 */
class AndroidAlarmNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlarmNotifier {

    override fun show(alarm: Alarm) {
        val manager = NotificationManagerCompat.from(context)
        if (!shouldPost(manager.areNotificationsEnabled())) return

        val id = notificationId(alarm.kind)
        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm_warning)
            .setContentTitle(alarmMessage(context.resources, alarm))
            .setContentText(alarmDetail(context.resources, alarm))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, ObdForegroundService.openAppIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        // Erst abräumen, dann neu posten (Vorgabe der Spezifikation): ein Alarm bei höherer Stufe soll sicher als NEUE Benachrichtigung mit neuem Alarmton erscheinen und nicht als
        // Update der alten. Ob ein bloßes Update am Gerät lautlos bliebe, ist nicht gemessen — und kein Test sieht den Unterschied (s. `AndroidAlarmNotifierDeviceTest`).
        manager.cancel(id)
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Die Berechtigung fehlt trotz der Prüfung oben (Entzug zwischen Prüfung und Aufruf): das Band zeigt den Alarm, mehr gibt es nicht zu tun.
        }
    }

    override fun clear(kind: AlarmKind) {
        NotificationManagerCompat.from(context).cancel(notificationId(kind))
    }
}
