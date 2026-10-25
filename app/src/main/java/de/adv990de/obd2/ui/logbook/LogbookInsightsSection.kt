package de.adv990de.obd2.ui.logbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.adv990de.obd2.R
import de.adv990de.obd2.logbook.EntryConsumption
import de.adv990de.obd2.logbook.FluidReport
import de.adv990de.obd2.logbook.FuelReport
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogFilter
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.LogbookInsights
import de.adv990de.obd2.logbook.DueState
import de.adv990de.obd2.logbook.MaintenanceDue
import de.adv990de.obd2.logbook.MaintenanceRow
import de.adv990de.obd2.logbook.maintenanceRows
import de.adv990de.obd2.logbook.logbookInsights
import de.adv990de.obd2.ui.components.BarChart
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.components.SectionLabel
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.asLiveValue
import java.time.LocalDate
import java.util.Locale

/** Ab dieser Breite stehen die Zellen der Uebersicht zu dritt und die Karten nebeneinander (Querformat), sonst zu zweit bzw. untereinander. */
private val WIDE_BREAKPOINT: Dp = 600.dp

/** Test tags. */
internal const val LOGBOOK_OVERVIEW_TEST_TAG = "logbook_overview"
internal const val LOGBOOK_STATS_TEST_TAG = "logbook_stats"
internal fun logbookOverviewItemTestTag(type: LogType) = "logbook_overview_${type.name}"
internal fun logbookStatsCardTestTag(type: LogType) = "logbook_stats_card_${type.name}"
internal fun logbookStatsChartTestTag(type: LogType) = "logbook_stats_chart_${type.name}"

/** Welche Abschnitte der Auswertung der Filter zeigt: die Uebersicht der Wartung und/oder die Karten der Verbrauchsarten. */
internal data class InsightSections(val overview: Boolean, val cards: List<LogType>)

/**
 * Zu jedem Filter der Liste gehoert, was dazu ausgewertet wird: **Alle** zeigt die Uebersicht und alle drei Karten, Tanken, Oel und Kuehlfluessigkeit nur die eigene Karte, Wartung
 * die Uebersicht. So liegt unter einem Filter nur, was zu seinen Eintraegen passt, und die Liste beginnt dort nicht erst nach einem Bildschirm voll Karten.
 */
internal fun insightSectionsFor(filter: LogFilter): InsightSections = when (filter) {
    LogFilter.All -> InsightSections(overview = true, cards = listOf(LogType.FUEL, LogType.OIL, LogType.COOLANT))
    LogFilter.Fuel -> InsightSections(overview = false, cards = listOf(LogType.FUEL))
    LogFilter.Oil -> InsightSections(overview = false, cards = listOf(LogType.OIL))
    LogFilter.Coolant -> InsightSections(overview = false, cards = listOf(LogType.COOLANT))
    LogFilter.Maintenance -> InsightSections(overview = true, cards = emptyList())
}

/**
 * Die Uebersicht der Wartung: je Kettenpflege klein und gross, Service klein und gross, Reifenwechsel und Kettenkit-Wechsel eine Zelle mit "vor N km", dem Datum und dem Stand des
 * letzten Eintrags (beim Reifenwechsel mit Fabrikat), dem mittleren Abstand und — **mit dem Intervall des Nutzers** — "fällig in N km" (gelb im letzten Zehntel, rot ab der Faelligkeit;
 * die Farbe traegt nichts allein, der Text sagt es). Ohne Intervall steht "Intervall festlegen": ein Tipp auf die Zelle oeffnet den Dialog ([onEditInterval]).
 *
 * Gerechnet wird mit dem **geschaetzten Stand** ([MaintenanceState.estimatedKm]: hoechster Logbuch-Stand plus GPS-Kilometer); hat die Schaetzung einen GPS-Anteil, steht "ca." an den Zahlen und
 * die Zeile darunter nennt die Rechnung. Zu dritt im Querformat, zu zweit im Hochformat.
 */
@Composable
internal fun LogbookOverview(state: MaintenanceState, onEditInterval: (LogType) -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    Column(modifier.fillMaxWidth().testTag(LOGBOOK_OVERVIEW_TEST_TAG)) {
        SectionLabel(stringResource(R.string.logbook_section_overview), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val columns = if (maxWidth >= WIDE_BREAKPOINT) 3 else 2
            // Die Zellen teilen sich eine Haarlinie (Hintergrund der Reihe, 1 dp Abstand), wie das Kachelraster des Dashboards.
            Column(Modifier.background(MaterialTheme.colorScheme.outlineVariant), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                state.rows.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        row.forEach { OverviewCell(it, approximate = state.gpsShareKm > 0, locale = locale, onEdit = { onEditInterval(it.item.type) }, modifier = Modifier.weight(1f).fillMaxHeight()) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) }
                    }
                }
            }
        }
        val logged = state.loggedKm
        val estimated = state.estimatedKm
        if (logged != null && estimated != null) {
            Text(
                if (state.gpsShareKm > 0) {
                    stringResource(R.string.logbook_overview_basis_approx, formatOdometer(estimated, locale), formatOdometer(logged, locale), formatOdometer(state.gpsShareKm, locale))
                } else {
                    stringResource(R.string.logbook_overview_basis, formatOdometer(logged, locale))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Eine Zahl mit "ca.", wenn der Stand geschaetzt ist ([approximate]); die Vorlagen (`vor %1$s km`, `faellig in %1$s km`) setzen die Einheit dazu. */
@Composable
private fun approxNumber(number: String, approximate: Boolean): String = if (approximate) stringResource(R.string.logbook_fmt_approx, number) else number

@Composable
private fun OverviewCell(row: MaintenanceRow, approximate: Boolean, locale: Locale, onEdit: () -> Unit, modifier: Modifier) {
    val item = row.item
    val last = item.last
    val since = row.kmSince
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClickLabel = stringResource(R.string.logbook_cd_set_interval), onClick = onEdit)
            .padding(12.dp)
            .testTag(logbookOverviewItemTestTag(item.type)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(item.type.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val detail = MaterialTheme.typography.bodySmall
        val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
        if (last == null || since == null) {
            Text(stringResource(R.string.logbook_overview_never), style = MaterialTheme.typography.bodyMedium, color = detailColor)
        } else {
            LiveValueText(
                stringResource(R.string.logbook_overview_since, approxNumber(formatOdometer(since, locale), approximate)),
                style = MaterialTheme.typography.titleMedium.asLiveValue(),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = true,
                maxLines = 2,
            )
            Text(stringResource(R.string.logbook_overview_last, formatLogDate(last.date, locale), formatOdometer(last.odometerKm, locale)), style = detail, color = detailColor)
            last.brand?.let { Text(it, style = detail, color = detailColor, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            item.averageSpacingKm?.let { Text(stringResource(R.string.logbook_overview_spacing, formatKmRounded(it, locale)), style = detail, color = detailColor) }
        }
        DueLine(row, approximate, locale)
    }
}

/** Die letzte Zeile einer Zelle: "Intervall festlegen" (nichts festgelegt), "alle N km" (Intervall ohne Eintrag) oder "alle N km · fällig in N km" in der Farbe der Stufe. */
@Composable
private fun DueLine(row: MaintenanceRow, approximate: Boolean, locale: Locale) {
    val style = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val every = row.intervalKm?.let { stringResource(R.string.logbook_interval_every, formatOdometer(it, locale)) }
    when (val due = row.due) {
        MaintenanceDue.NotSet -> Text(stringResource(R.string.logbook_interval_set), style = style, color = MaterialTheme.colorScheme.primary)
        MaintenanceDue.NoBaseline -> Text(every.orEmpty(), style = style, color = muted)
        is MaintenanceDue.Planned -> {
            val remaining = due.remainingKm
            val text = when {
                remaining > 0 -> stringResource(R.string.logbook_due_in, approxNumber(formatOdometer(remaining, locale), approximate))
                remaining == 0 -> stringResource(R.string.logbook_due_now)
                else -> stringResource(R.string.logbook_due_overdue, approxNumber(formatOdometer(-remaining, locale), approximate))
            }
            val surface = MaterialTheme.colorScheme.surface
            Text(
                stringResource(R.string.logbook_row_join, every.orEmpty(), text),
                style = style,
                color = when (due.state) {
                    DueState.Ok -> muted
                    DueState.Soon -> StatusColors.warningOn(surface)
                    DueState.Overdue -> StatusColors.criticalOn(surface)
                },
            )
        }
    }
}

/** Die Karten der Auswertung ([types] in dieser Reihenfolge): im Querformat nebeneinander, im Hochformat untereinander; ein Tipp auf eine Karte oeffnet ihre Detailseite ([onOpen]). */
@Composable
internal fun LogbookStatsCards(types: List<LogType>, insights: LogbookInsights, onOpen: (LogType) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag(LOGBOOK_STATS_TEST_TAG)) {
        SectionLabel(stringResource(R.string.logbook_section_stats), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            if (maxWidth >= WIDE_BREAKPOINT && types.size > 1) {
                // Ohne IntrinsicSize: die Kopfzeile der Karte schrumpft per TextAutoSize, und dessen Eigenmasse verkleinerte die Reihe so, dass der Inhalt abgeschnitten wurde (am Geraet gesehen).
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    types.forEach { LogbookStatsCard(it, insights, { onOpen(it) }, Modifier.weight(1f)) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    types.forEach { LogbookStatsCard(it, insights, { onOpen(it) }, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

/** Was eine Karte zeigt, einmal aus dem Bericht der Art gerechnet. */
private class CardContent(
    val headline: String?,
    val subtitle: String?,
    val values: List<Float>,
    val average: Float?,
    val valueLabel: (Float) -> String,
    val description: String,
    val emptyRes: Int,
)

@Composable
private fun cardContent(type: LogType, insights: LogbookInsights, locale: Locale): CardContent = when (type) {
    LogType.FUEL -> fuelCardContent(insights.fuel, locale)
    else -> fluidCardContent(if (type == LogType.OIL) insights.oil else insights.coolant, locale)
}

@Composable
private fun fuelCardContent(report: FuelReport, locale: Locale): CardContent {
    val summary = report.summary
    val average = summary?.let { stringResource(R.string.logbook_fmt_l100, formatL100(it.averageLitersPer100Km, locale)) }
    val latest = summary?.let { stringResource(R.string.logbook_fmt_l100, formatL100(it.latest.litersPer100Km, locale)) }
    return CardContent(
        headline = average?.let { stringResource(R.string.logbook_fmt_average, it) },
        subtitle = summary?.let { statsSubtitle(it.intervalCount, latest!!) },
        values = report.intervals.map { it.litersPer100Km.toFloat() },
        average = summary?.averageLitersPer100Km?.toFloat(),
        valueLabel = { formatL100(it.toDouble(), locale) },
        description = if (summary == null) "" else pluralStringResource(R.plurals.logbook_chart_description, summary.intervalCount, summary.intervalCount, average!!, latest!!),
        emptyRes = R.string.logbook_stats_empty_fuel,
    )
}

@Composable
private fun fluidCardContent(report: FluidReport, locale: Locale): CardContent {
    val summary = report.summary
    val average = summary?.let { stringResource(R.string.logbook_fmt_ml_per_1000, formatMlPer1000(it.averageMlPer1000Km, locale)) }
    val latest = summary?.let { stringResource(R.string.logbook_fmt_ml_per_1000, formatMlPer1000(it.latest.mlPer1000Km, locale)) }
    return CardContent(
        headline = average?.let { stringResource(R.string.logbook_fmt_average, it) },
        subtitle = summary?.let { statsSubtitle(it.intervalCount, latest!!) },
        values = report.intervals.map { it.mlPer1000Km.toFloat() },
        average = summary?.averageMlPer1000Km?.toFloat(),
        valueLabel = { formatMlPer1000(it.toDouble(), locale) },
        description = if (summary == null) "" else pluralStringResource(R.plurals.logbook_chart_description, summary.intervalCount, summary.intervalCount, average!!, latest!!),
        emptyRes = R.string.logbook_stats_empty_fluid,
    )
}

@Composable
private fun statsSubtitle(count: Int, latest: String): String = stringResource(
    R.string.logbook_row_join,
    pluralStringResource(R.plurals.logbook_stats_intervals, count, count),
    stringResource(R.string.logbook_stats_latest, latest),
)

/**
 * Eine Karte: Name der Art, der Durchschnitt gross, ein Mini-Diagramm der Intervalle (ohne Beschriftung, mit gestrichelter Linie des Durchschnitts), darunter die Zahl der
 * Intervalle und der letzte Wert. Ohne Auswertung steht stattdessen der Grund. Die ganze Karte ist ein Tipp-Ziel ([onOpen]).
 */
@Composable
internal fun LogbookStatsCard(type: LogType, insights: LogbookInsights, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val content = cardContent(type, insights, locale)
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .clickable(onClickLabel = stringResource(R.string.logbook_cd_open_stats), onClick = onOpen)
            .padding(12.dp)
            .testTag(logbookStatsCardTestTag(type)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(
                when (type) {
                    LogType.FUEL -> R.string.logbook_filter_fuel
                    LogType.OIL -> R.string.logbook_filter_oil
                    else -> R.string.logbook_filter_coolant
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (content.headline == null) {
            Text(stringResource(content.emptyRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LiveValueText(content.headline, style = MaterialTheme.typography.headlineSmall.asLiveValue(), minFontSize = 14.sp)
            BarChart(
                values = content.values,
                average = content.average,
                valueLabel = content.valueLabel,
                description = content.description,
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp).testTag(logbookStatsChartTestTag(type)),
                showLabels = false,
            )
            content.subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Der Verbrauch an einer Listenzeile, als Text mit Einheit. */
@Composable
internal fun consumptionText(consumption: EntryConsumption, locale: Locale): String = when (consumption) {
    is EntryConsumption.Fuel -> stringResource(R.string.logbook_fmt_l100, formatL100(consumption.litersPer100Km, locale))
    is EntryConsumption.Fluid -> stringResource(R.string.logbook_fmt_ml_per_1000, formatMlPer1000(consumption.mlPer1000Km, locale))
}

// region — Previews

private fun previewEntry(id: Long, type: LogType, km: Int, day: Int, ml: Int? = null, full: Boolean? = null, brand: String? = null) =
    LogEntry(id, type, LocalDate.of(2026, 9, day), km, ml, full, null, null, brand)

private val previewInsights: LogbookInsights = logbookInsights(
    listOf(
        previewEntry(1, LogType.FUEL, 40_000, 1, 14_000, true),
        previewEntry(2, LogType.FUEL, 40_200, 5, 15_000, true),
        previewEntry(3, LogType.FUEL, 40_450, 9, 19_800, true),
        previewEntry(4, LogType.FUEL, 40_640, 13, 14_100, true),
        previewEntry(5, LogType.OIL, 40_000, 1, 100),
        previewEntry(6, LogType.OIL, 40_500, 8, 50),
        previewEntry(7, LogType.CHAIN_SMALL, 40_100, 3),
        previewEntry(8, LogType.CHAIN_SMALL, 39_000, 1),
        previewEntry(9, LogType.SERVICE_LARGE, 30_000, 1),
        previewEntry(10, LogType.TIRE_CHANGE, 37_000, 2, brand = "Michelin Anakee Adventure 150/70-18"),
    ),
)

private val previewMaintenance = MaintenanceState(
    rows = maintenanceRows(
        previewInsights.maintenance,
        mapOf(LogType.CHAIN_SMALL to 500, LogType.CHAIN_LARGE to 2_000, LogType.SERVICE_LARGE to 11_000),
        estimatedKm = 40_912,
    ),
    loggedKm = 40_640,
    estimatedKm = 40_912,
    gpsShareKm = 272,
)

@Composable
private fun OverviewPreviewContent() {
    LogbookOverview(previewMaintenance, onEditInterval = {})
}

@Composable
private fun StatsCardsPreviewContent() {
    LogbookStatsCards(insightSectionsFor(LogFilter.All).cards, previewInsights, onOpen = {})
}

@Preview(name = "Logbook overview (light)", showBackground = true, widthDp = 420)
@Composable
private fun PreviewLogbookInsightsLight() {
    PreviewTheme(dark = false) { OverviewPreviewContent() }
}

@Preview(name = "Logbook overview (dark)", showBackground = true, widthDp = 420)
@Composable
private fun PreviewLogbookInsightsDark() {
    PreviewTheme(dark = true) { OverviewPreviewContent() }
}

@Preview(name = "Logbook stats cards (light)", showBackground = true, widthDp = 420)
@Composable
private fun PreviewLogbookStatsCardsLight() {
    PreviewTheme(dark = false) { StatsCardsPreviewContent() }
}

@Preview(name = "Logbook stats cards (dark)", showBackground = true, widthDp = 420)
@Composable
private fun PreviewLogbookStatsCardsDark() {
    PreviewTheme(dark = true) { StatsCardsPreviewContent() }
}

// endregion
