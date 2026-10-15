package de.adv990de.obd2.ui.components

import java.util.Locale

/**
 * Die Ambient-Statuszeile hat drei Plaetze: Datum links, Oel-Timer in der Mitte (Nutzerwunsch 2026-10-15), Aussentemperatur rechts. Die Mitte
 * steht exakt mittig — beide Seiten sind gleich breit: `(availableWidth - centreWidth) / 2`, davon geht der Mindestabstand [minGap] zur Mitte ab.
 *
 * Passt das volle Datum ("So. 27.09. · 14:32", in der Titelgroesse der Zeile rund 265-280 dp) nicht in seine Seite, zeigt die Zeile nur die
 * Uhrzeit ([formatDashboardTime]). Gemessen an der Praxis: im Hochformat eines Pixel 8 Pro (~380 dp nutzbar) bleiben je Seite etwa 120 dp, das
 * volle Datum passt dort nie neben den Timer; im Querformat bleibt es unveraendert. Alle Groessen in derselben Einheit (Pixel).
 *
 * Reine Funktion; die Breiten misst der Aufrufer mit dem echten Textstil (`TextMeasurer`), damit Systemschrift und Sprache mitzaehlen.
 */
internal fun ambientUsesCompactHeader(availableWidth: Int, fullHeaderWidth: Int, centreWidth: Int, minGap: Int): Boolean =
    fullHeaderWidth > (availableWidth - centreWidth) / 2 - minGap

/** Der Text des Oel-Timers in der Mitte der Zeile: Beschriftung, Leerzeichen, Restzeit ("Oel: 6:12" bzw. "Öl: 6:12"). */
internal fun ambientOilText(label: String, remainingMillis: Long, locale: Locale): String =
    "$label ${formatOilTimer(remainingMillis, locale)}"
