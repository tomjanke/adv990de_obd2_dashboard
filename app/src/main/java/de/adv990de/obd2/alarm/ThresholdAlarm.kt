package de.adv990de.obd2.alarm

/** Eine Größe (Spannung oder Kühlmittel) mit einer Warn- und mindestens einer Hinweisregel; die höchste aktive Stufe zählt. */
internal class ThresholdAlarm(
    private val warning: SustainedRule,
    private val notices: List<SustainedRule>,
) {
    /** Die letzte Probe, für die Anzeige; `null` nach [reset]. */
    var lastValue: Float? = null
        private set

    fun sample(nowMs: Long, value: Float) {
        // NaN und Unendlich sind keine Probe (wie in `SustainedRule`): sie ersetzen auch nicht die letzte Zahl der Anzeige.
        if (!value.isFinite()) return
        lastValue = value
        warning.sample(nowMs, value)
        notices.forEach { it.sample(nowMs, value) }
    }

    fun severity(): AlarmSeverity? = when {
        warning.isActive -> AlarmSeverity.WARNING
        notices.any { it.isActive } -> AlarmSeverity.NOTICE
        else -> null
    }

    fun reset() {
        lastValue = null
        warning.reset()
        notices.forEach { it.reset() }
    }
}

/** Spannung: Warnung unter der Warnschwelle ≥ 10 s, Hinweis unter der Hinweisschwelle ≥ 20 s, Hinweis „Entladung“ unter 13,0 V ≥ 180 s (fest). Rückkehr-Abstand 0,2 V. */
internal fun voltageAlarm(settings: AlarmSettings) = ThresholdAlarm(
    warning = SustainedRule(Breach.BELOW, settings.voltageWarningDeciV / 10f, 10_000, 0.2f),
    notices = listOf(
        SustainedRule(Breach.BELOW, settings.voltageNoticeDeciV / 10f, 20_000, 0.2f),
        SustainedRule(Breach.BELOW, 13.0f, 180_000, 0.2f),
    ),
)

/** Kühlmittel: Warnung ab der Warnschwelle ≥ 5 s, Hinweis ab der Hinweisschwelle ≥ 10 s. Rückkehr-Abstand 3 °C. */
internal fun coolantAlarm(settings: AlarmSettings) = ThresholdAlarm(
    warning = SustainedRule(Breach.ABOVE, settings.coolantWarningC.toFloat(), 5_000, 3f),
    notices = listOf(SustainedRule(Breach.ABOVE, settings.coolantNoticeC.toFloat(), 10_000, 3f)),
)
