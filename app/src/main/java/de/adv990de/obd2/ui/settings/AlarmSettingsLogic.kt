package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.alarm.AlarmSettings
import java.util.Locale

/**
 * Die Beschriftung eines Spannungs-Segments: Zehntelvolt als Volt mit EINER festen Nachkommastelle in der [locale] des Geräts (125 → „12,5“ bzw. „12.5“; 120 → „12,0“,
 * nicht „12“ — ohne die Null sähe das Segment wie eine ganze Zahl aus). Die Locale ist ein Parameter statt `Locale.getDefault()`, damit die Anzeige der
 * Konfiguration der Oberfläche folgt und ein Test beide Schreibweisen prüfen kann. Die Kühlmittel-Segmente brauchen keine eigene Funktion: sie zeigen die ganze °C-Zahl
 * der Option, ohne Umrechnung — auch bei imperialen Einheiten (die Schwellen sind °C-Entscheidungen, der Alarm-Text sagt ebenfalls immer °C).
 */
internal fun formatVoltageOption(deciVolts: Int, locale: Locale): String = String.format(locale, "%.1f", deciVolts / 10.0)

/**
 * Zeigt die Gruppe „Alarme“ den Hinweis „Ohne Benachrichtigungen erscheinen Alarme nur im Dashboard“?
 *
 * Zwei Eingänge, beide tragen eine echte Entscheidung: [notificationsEnabled] ist `NotificationManagerCompat.areNotificationsEnabled()` und damit **falsch in beiden
 * Fällen, in denen keine Benachrichtigung ankommt** — ab Android 13 fehlt `POST_NOTIFICATIONS`, oder der Nutzer hat sie in den Systemeinstellungen gesperrt (auf jeder
 * Stufe). Eine SDK-Stufe ergänzte darum nichts und steht nicht in der Signatur. Der zweite Eingang sind die Schalter: ist **kein** Alarm eingeschaltet, gibt es nichts
 * zu melden, und der Hinweis wäre Lärm (dasselbe Muster wie `gpsSpeedPermissionHint`, der nur bei eingeschaltetem GPS-Schalter warnt).
 */
internal fun alarmNotificationHint(settings: AlarmSettings, notificationsEnabled: Boolean): Boolean =
    (settings.voltageEnabled || settings.coolantEnabled || settings.faultsEnabled) && !notificationsEnabled
