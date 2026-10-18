package de.adv990de.obd2.alarm

/** Die Art eines Alarms; die Reihenfolge ist der Rang in der Anzeige innerhalb einer Stufe. */
enum class AlarmKind { COOLANT, VOLTAGE, FAULT_CODE }

/** Hinweis (gelb) oder Warnung (rot); [WARNING] ist die höhere Stufe. */
enum class AlarmSeverity { NOTICE, WARNING }

/**
 * Ein Alarm für die Anzeige. [value] ist die letzte Probe (Volt bzw. °C) und ändert sich laufend; [codes] nennt beim Fehler-Alarm die neuen Codes.
 * Zwei Alarme sind derselbe Alarm ([sameAlert]), wenn Art, Stufe und Codes gleich sind — der Wert zählt nicht.
 */
data class Alarm(
    val kind: AlarmKind,
    val severity: AlarmSeverity,
    val value: Float? = null,
    val codes: List<String> = emptyList(),
) {
    fun sameAlert(other: Alarm?): Boolean =
        other != null && kind == other.kind && severity == other.severity && codes == other.codes
}

/**
 * Die Einstellungen der Alarme. Spannungen in Zehntelvolt (125 = 12,5 V), Temperaturen in °C. Die Auswahlmengen sind je Paar disjunkt (größter Warnwert unter dem kleinsten
 * Hinweiswert), die Warnschwelle liegt also immer unter der Hinweisschwelle bei der Spannung bzw. darüber beim Kühlmittel.
 */
data class AlarmSettings(
    val voltageEnabled: Boolean = true,
    val coolantEnabled: Boolean = true,
    val faultsEnabled: Boolean = true,
    val voltageNoticeDeciV: Int = DEFAULT_VOLTAGE_NOTICE_DV,
    val voltageWarningDeciV: Int = DEFAULT_VOLTAGE_WARNING_DV,
    val coolantNoticeC: Int = DEFAULT_COOLANT_NOTICE_C,
    val coolantWarningC: Int = DEFAULT_COOLANT_WARNING_C,
) {
    companion object {
        val VOLTAGE_NOTICE_OPTIONS_DV = listOf(123, 125, 128)
        val VOLTAGE_WARNING_OPTIONS_DV = listOf(118, 120, 122)
        val COOLANT_NOTICE_OPTIONS_C = listOf(92, 95, 98)
        val COOLANT_WARNING_OPTIONS_C = listOf(100, 105, 110)
        const val DEFAULT_VOLTAGE_NOTICE_DV = 125
        const val DEFAULT_VOLTAGE_WARNING_DV = 122
        const val DEFAULT_COOLANT_NOTICE_C = 95
        const val DEFAULT_COOLANT_WARNING_C = 100
    }
}
