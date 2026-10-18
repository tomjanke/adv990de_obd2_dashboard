package de.adv990de.obd2.alarm

internal enum class Breach { BELOW, ABOVE }

/**
 * Ein Dauerkriterium: eine Probenfolge `(nowMs, value)` löst erst aus, wenn die Schwelle [durationMs] lang ununterbrochen verletzt ist. Ein einzelner Ausreißer setzt den Lauf nicht
 * zurück — erst eine Erholung von [RECOVERY_MS] beendet ihn; ein aktiver Alarm erlischt zusätzlich nur, wenn der Wert um [clearMargin] jenseits der Schwelle liegt (Hysterese).
 * Mehr als [GAP_MS] zwischen zwei Proben setzt die Regel zurück (Kontinuität nicht belegt). Reine Logik, die Zeit kommt als Parameter.
 */
internal class SustainedRule(
    private val breach: Breach,
    private val threshold: Float,
    private val durationMs: Long,
    private val clearMargin: Float,
) {
    var isActive: Boolean = false
        private set

    private var breachSince: Long? = null
    private var recoverySince: Long? = null
    private var lastSampleAt: Long? = null

    fun sample(nowMs: Long, value: Float) {
        val last = lastSampleAt
        if (last != null && nowMs - last > GAP_MS) reset()
        lastSampleAt = nowMs
        if (violates(value)) {
            recoverySince = null
            val since = breachSince ?: nowMs.also { breachSince = it }
            if (!isActive && nowMs - since >= durationMs) isActive = true
            return
        }
        if (breachSince == null && !isActive) return
        val recovering = recoverySince ?: nowMs.also { recoverySince = it }
        if (nowMs - recovering >= RECOVERY_MS && (!isActive || beyondMargin(value))) {
            breachSince = null
            recoverySince = null
            isActive = false
        }
    }

    fun reset() {
        isActive = false
        breachSince = null
        recoverySince = null
        lastSampleAt = null
    }

    private fun violates(value: Float) = when (breach) {
        Breach.BELOW -> value < threshold
        Breach.ABOVE -> value >= threshold
    }

    private fun beyondMargin(value: Float) = when (breach) {
        Breach.BELOW -> value >= threshold + clearMargin
        Breach.ABOVE -> value < threshold - clearMargin
    }

    internal companion object {
        const val RECOVERY_MS = 5_000L
        const val GAP_MS = 30_000L
    }
}
