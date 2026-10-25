package de.adv990de.obd2.logbook

/**
 * Die Filter der Logbuch-Liste, in der Reihenfolge der Chips: Alle, Tanken, Oel, Kuehlfluessigkeit und **Wartung** — Kettenpflege, Service, Reifen- und
 * Kettenkit-Wechsel, also alle Arten ohne Verbrauch. Jede Art gehoert zu mindestens einem Filter ausser "Alle" (`LogFilterTest`): kein Eintrag ist nur dort sichtbar.
 */
enum class LogFilter(private val types: Set<LogType>?) {
    All(null),
    Fuel(setOf(LogType.FUEL)),
    Oil(setOf(LogType.OIL)),
    Coolant(setOf(LogType.COOLANT)),
    Maintenance(
        setOf(LogType.CHAIN_SMALL, LogType.CHAIN_LARGE, LogType.SERVICE_SMALL, LogType.SERVICE_LARGE, LogType.TIRE_CHANGE, LogType.CHAIN_KIT_CHANGE),
    );

    fun matches(type: LogType): Boolean = types == null || type in types
}
