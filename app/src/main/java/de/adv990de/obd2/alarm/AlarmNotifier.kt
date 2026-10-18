package de.adv990de.obd2.alarm

/**
 * Die Naht zur Systembenachrichtigung der Alarme (Muster `FuelDistanceSink`): der `AlarmController` kennt nur diese Schnittstelle, Hilt liefert [AndroidAlarmNotifier].
 * [show] zeigt einen Alarm und ersetzt dabei den bisherigen derselben Art, [clear] nimmt die Benachrichtigung einer Art zurück.
 */
interface AlarmNotifier {
    fun show(alarm: Alarm)
    fun clear(kind: AlarmKind)

    /** Tut nichts — der Standard für Tests und Aufrufer ohne Benachrichtigung. */
    object None : AlarmNotifier {
        override fun show(alarm: Alarm) = Unit
        override fun clear(kind: AlarmKind) = Unit
    }
}
