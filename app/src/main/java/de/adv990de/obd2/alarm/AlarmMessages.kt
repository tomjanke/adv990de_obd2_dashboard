package de.adv990de.obd2.alarm

import android.content.res.Resources
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.dtcDescriptionRes
import de.adv990de.obd2.obd.formatSensorNumber

/** Platzhalter für einen Wert, den der Alarm nicht trägt — besser Striche als eine erfundene Zahl. */
private const val NO_VALUE = "--"

/**
 * Der Text eines Alarms („Spannung niedrig: 12,3 V“, „Kühlmittel heiß: 97 °C“, „Neuer Fehlercode: P0301“) — die eine Stelle für Benachrichtigung und Band. Die Zahlen gehen durch
 * `formatSensorNumber` (Volt mit einer Nachkommastelle, sonst ganze Zahl, Locale des Geräts), nicht durch ein eigenes `format`. Ohne Wert oder mit einem nicht endlichen
 * (kein Alarm der Regeln liefert das) stehen Striche. Der Plural des Fehler-Alarms richtet sich nach der Zahl der Codes.
 */
fun alarmMessage(resources: Resources, alarm: Alarm): String {
    val value = alarmValueText(alarm)
    return when (alarm.kind) {
        AlarmKind.VOLTAGE -> resources.getString(R.string.alarm_voltage_low, value)
        AlarmKind.COOLANT -> resources.getString(R.string.alarm_coolant_hot, value)
        AlarmKind.FAULT_CODE -> resources.getQuantityString(R.plurals.alarm_fault_new, alarm.codes.size, value)
    }
}

/**
 * Der Alarm **ohne die Live-Zahl** („Spannung niedrig“, „Kühlmittel heiß“, „Neuer Fehlercode: P0301“) — für die Beschreibung des Alarmbands. Das Band ist eine Assertive-Live-Region:
 * ändert sich seine Beschreibung, sagt TalkBack sie neu an, und die Zahl wechselt mit jedem OBD-Zyklus. Art und Codes ändern sich nur, wenn der Alarm ein anderer ist; die Codes
 * stehen mit drin (ihre Liste wächst nur mit einem neuen Fehler). Der Wert bleibt sichtbar im Text des Bandes und in der Beschreibung der Kachel.
 */
fun alarmSummary(resources: Resources, alarm: Alarm): String = when (alarm.kind) {
    AlarmKind.VOLTAGE -> resources.getString(R.string.alarm_voltage_low_short)
    AlarmKind.COOLANT -> resources.getString(R.string.alarm_coolant_hot_short)
    AlarmKind.FAULT_CODE -> alarmMessage(resources, alarm)
}

/**
 * Die Zahl bzw. die Codes, die [alarmMessage] in den Platzhalter setzt („12,3“, „97“, „P0301, P0420“) — die eine Stelle für den Wortlaut und für das Alarmband, das diese Stelle im
 * fertigen Text findet und in der Zahlenschrift setzt (`emphasizeValue`): so formatiert nichts die Zahl ein zweites Mal.
 */
internal fun alarmValueText(alarm: Alarm): String = when (alarm.kind) {
    AlarmKind.VOLTAGE -> formatAlarmValue(alarm.value, "V")
    AlarmKind.COOLANT -> formatAlarmValue(alarm.value, "°C")
    AlarmKind.FAULT_CODE -> alarm.codes.joinToString(", ")
}

/**
 * Die zweite Zeile der Benachrichtigung: bei **einem** neuen Fehlercode seine Beschreibung (`dtcDescriptionRes`, wie auf der Fehlercode-Seite; ein unbekannter Code sagt
 * „Unbekannter Fehlercode“). Bei mehreren Codes gäbe es nur die Beschreibung für einen von ihnen, bei Spannung und Kühlmittel sagt die erste Zeile schon alles — dort `null`.
 */
internal fun alarmDetail(resources: Resources, alarm: Alarm): String? =
    if (alarm.kind == AlarmKind.FAULT_CODE && alarm.codes.size == 1) resources.getString(dtcDescriptionRes(alarm.codes.single())) else null

private fun formatAlarmValue(value: Float?, rawUnit: String): String =
    if (value != null && value.isFinite()) formatSensorNumber(value, rawUnit) else NO_VALUE
