package de.adv990de.obd2.ui.logbook

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.logbook.FluidReport
import de.adv990de.obd2.logbook.FuelReport
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.fluidReport
import de.adv990de.obd2.logbook.fuelReport
import de.adv990de.obd2.ui.components.BarChart
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.components.SectionLabel
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.asLiveValue
import androidx.compose.material3.MaterialTheme
import java.time.LocalDate
import java.util.Locale

/** Test tags. */
internal const val LOGBOOK_STATS_LIST_TEST_TAG = "logbook_stats_list"
internal const val LOGBOOK_STATS_DETAIL_CHART_TEST_TAG = "logbook_stats_detail_chart"
internal const val LOGBOOK_STATS_HINTS_TEST_TAG = "logbook_stats_hints"
internal fun logbookIntervalTestTag(endEntryId: Long) = "logbook_interval_$endEntryId"
internal fun logbookFigureTestTag(figure: String) = "logbook_figure_$figure"

/**
 * Die Detailseite der Auswertung einer Art (Tanken, Oel oder Kuehlfluessigkeit): das grosse Diagramm, die Kennzahlen, die Hinweise zur Rechnung und die Intervalle, das juengste zuerst.
 * Bei einem unbrauchbaren Argument geht die Seite zurueck. Der Inhalt ([LogbookStatsBody]) ist zustandslos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogbookStatsScreen(navController: NavController, vm: LogbookStatsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state == LogbookStatsState.Invalid) navController.popBackStack() }
    val title = vm.type?.let { stringResource(it.statsTitleRes()) }.orEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.heightIn(min = TouchMinHeight)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_desc))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LogbookStatsBody(state, Modifier.padding(padding))
    }
}

/** Der Inhalt der Detailseite fuer einen Zustand: ohne Bericht (laedt oder unbrauchbar) leer, sonst die Liste der Abschnitte. */
@Composable
internal fun LogbookStatsBody(state: LogbookStatsState, modifier: Modifier = Modifier) {
    when (state) {
        LogbookStatsState.Loading, LogbookStatsState.Invalid -> Box(modifier.fillMaxSize())
        is LogbookStatsState.Fuel -> FuelStatsList(state.report, modifier)
        is LogbookStatsState.Fluid -> FluidStatsList(state.type, state.report, modifier)
    }
}

@Composable
private fun FuelStatsList(report: FuelReport, modifier: Modifier) {
    val locale = LocalLocale.current.platformLocale
    val summary = report.summary
    LazyColumn(modifier.fillMaxSize().testTag(LOGBOOK_STATS_LIST_TEST_TAG), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "chart") {
            if (summary == null) {
                EmptyHint(stringResource(R.string.logbook_stats_empty_fuel))
            } else {
                val average = stringResource(R.string.logbook_fmt_l100, formatL100(summary.averageLitersPer100Km, locale))
                val latest = stringResource(R.string.logbook_fmt_l100, formatL100(summary.latest.litersPer100Km, locale))
                BarChart(
                    values = report.intervals.map { it.litersPer100Km.toFloat() },
                    average = summary.averageLitersPer100Km.toFloat(),
                    valueLabel = { formatL100(it.toDouble(), locale) },
                    description = pluralStringResource(R.plurals.logbook_chart_description, summary.intervalCount, summary.intervalCount, average, latest),
                    modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT).padding(16.dp).testTag(LOGBOOK_STATS_DETAIL_CHART_TEST_TAG),
                )
            }
        }
        if (summary != null) {
            item(key = "figures") {
                SectionLabel(stringResource(R.string.logbook_stats_section_figures), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Figure("average", R.string.logbook_stat_average, stringResource(R.string.logbook_fmt_l100, formatL100(summary.averageLitersPer100Km, locale)))
                Figure("latest", R.string.logbook_stat_latest, stringResource(R.string.logbook_fmt_l100, formatL100(summary.latest.litersPer100Km, locale)))
                Figure("best", R.string.logbook_stat_best, stringResource(R.string.logbook_fmt_l100, formatL100(summary.best.litersPer100Km, locale)))
                Figure("worst", R.string.logbook_stat_worst, stringResource(R.string.logbook_fmt_l100, formatL100(summary.worst.litersPer100Km, locale)))
                Figure("intervals", R.string.logbook_stat_interval_count, formatOdometer(summary.intervalCount, locale))
                Figure("total_km", R.string.logbook_stat_total_km, stringResource(R.string.logbook_fmt_km, formatOdometer(summary.intervalKm, locale)))
                Figure("total_liters", R.string.logbook_stat_total_liters, stringResource(R.string.logbook_fmt_liters, formatLitersTotal(summary.totalMl, locale)))
                Figure("avg_distance", R.string.logbook_stat_avg_distance, stringResource(R.string.logbook_fmt_km, formatKmRounded(summary.averageKmPerInterval, locale)))
                summary.averagePriceCentsPerLiter?.let {
                    Figure("avg_price", R.string.logbook_stat_avg_price, stringResource(R.string.logbook_fmt_euro_per_liter, formatPricePerLiter(it, locale)))
                }
                summary.costCentsPer100Km?.let {
                    Figure("cost_100", R.string.logbook_stat_cost_100, stringResource(R.string.logbook_fmt_euro_per_100, formatEurosDouble(it, locale)))
                }
            }
        }
        item(key = "hints") { Hints(R.string.logbook_stats_hint_fuel, report.skipped) }
        if (report.intervals.isNotEmpty()) {
            item(key = "intervals_header") { SectionLabel(stringResource(R.string.logbook_stats_section_intervals), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            items(report.intervals.asReversed(), key = { it.endEntryId }) { interval ->
                IntervalRow(
                    id = interval.endEntryId,
                    headline = stringResource(R.string.logbook_fmt_l100, formatL100(interval.litersPer100Km, locale)),
                    date = interval.date,
                    startKm = interval.startKm,
                    endKm = interval.endKm,
                    amount = stringResource(R.string.logbook_fmt_liters, formatLiters(interval.amountMl.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), locale)),
                    km = interval.km,
                    locale = locale,
                )
            }
        }
    }
}

@Composable
private fun FluidStatsList(type: LogType, report: FluidReport, modifier: Modifier) {
    val locale = LocalLocale.current.platformLocale
    val summary = report.summary
    LazyColumn(modifier.fillMaxSize().testTag(LOGBOOK_STATS_LIST_TEST_TAG), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "chart") {
            if (summary == null) {
                EmptyHint(stringResource(R.string.logbook_stats_empty_fluid))
            } else {
                val average = stringResource(R.string.logbook_fmt_ml_per_1000, formatMlPer1000(summary.averageMlPer1000Km, locale))
                val latest = stringResource(R.string.logbook_fmt_ml_per_1000, formatMlPer1000(summary.latest.mlPer1000Km, locale))
                BarChart(
                    values = report.intervals.map { it.mlPer1000Km.toFloat() },
                    average = summary.averageMlPer1000Km.toFloat(),
                    valueLabel = { formatMlPer1000(it.toDouble(), locale) },
                    description = pluralStringResource(R.plurals.logbook_chart_description, summary.intervalCount, summary.intervalCount, average, latest),
                    modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT).padding(16.dp).testTag(LOGBOOK_STATS_DETAIL_CHART_TEST_TAG),
                )
            }
        }
        if (summary != null) {
            item(key = "figures") {
                SectionLabel(stringResource(R.string.logbook_stats_section_figures), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Figure("average", R.string.logbook_stat_average, stringResource(R.string.logbook_fmt_ml_per_1000, formatMlPer1000(summary.averageMlPer1000Km, locale)))
                Figure("latest", R.string.logbook_stat_latest, stringResource(R.string.logbook_fmt_ml_per_1000, formatMlPer1000(summary.latest.mlPer1000Km, locale)))
                Figure("intervals", R.string.logbook_stat_interval_count, formatOdometer(summary.intervalCount, locale))
                Figure("total_amount", R.string.logbook_stat_total_amount, stringResource(R.string.logbook_fmt_ml, formatMl(summary.totalMl.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), locale)))
                Figure("total_km", R.string.logbook_stat_total_km, stringResource(R.string.logbook_fmt_km, formatOdometer(summary.totalKm, locale)))
            }
        }
        item(key = "hints") { Hints(R.string.logbook_stats_hint_fluid, report.skipped) }
        if (report.intervals.isNotEmpty()) {
            item(key = "intervals_header") { SectionLabel(stringResource(R.string.logbook_stats_section_intervals), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            items(report.intervals.asReversed(), key = { it.endEntryId }) { interval ->
                IntervalRow(
                    id = interval.endEntryId,
                    headline = stringResource(R.string.logbook_fmt_ml_per_1000, formatMlPer1000(interval.mlPer1000Km, locale)),
                    date = interval.date,
                    startKm = interval.startKm,
                    endKm = interval.endKm,
                    amount = stringResource(R.string.logbook_fmt_ml, formatMl(interval.amountMl, locale)),
                    km = interval.km,
                    locale = locale,
                )
            }
        }
    }
}

/** Hoehe des grossen Diagramms; mit den Beschriftungen links und dem Durchschnitt rechts reicht sie im Querformat noch fuer die Kennzahlen darunter. */
private val CHART_HEIGHT = 200.dp

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

/** Eine Kennzahl: Name links, Wert rechts in Plex Mono (`tnum`); der Wert schrumpft statt abgeschnitten zu werden. */
@Composable
private fun Figure(tag: String, @StringRes labelRes: Int, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 6.dp).testTag(logbookFigureTestTag(tag)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        LiveValueText(value, style = MaterialTheme.typography.titleMedium.asLiveValue(), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
    }
}

/** Der Hinweis zur Rechnung und, falls Intervalle wegen gleichen Kilometerstands ausfielen, wie viele. */
@Composable
private fun Hints(@StringRes hintRes: Int, skipped: Int) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag(LOGBOOK_STATS_HINTS_TEST_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(hintRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (skipped > 0) {
            Text(pluralStringResource(R.plurals.logbook_stats_skipped, skipped, skipped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Ein Intervall: der Verbrauch gross, darunter Datum, von-bis-Stand und die Menge, rechts die Strecke. */
@Composable
private fun IntervalRow(id: Long, headline: String, date: LocalDate, startKm: Int, endKm: Int, amount: String, km: Int, locale: Locale) {
    val range = stringResource(R.string.logbook_interval_range, formatOdometer(startKm, locale), formatOdometer(endKm, locale))
    ListItem(
        modifier = Modifier.testTag(logbookIntervalTestTag(id)),
        headlineContent = { LiveValueText(headline, style = MaterialTheme.typography.titleMedium.asLiveValue(), color = MaterialTheme.colorScheme.onSurface) },
        supportingContent = {
            Text(
                stringResource(R.string.logbook_row_join, stringResource(R.string.logbook_row_join, formatLogDate(date, locale), range), amount),
            )
        },
        trailingContent = { Text(stringResource(R.string.logbook_fmt_km, formatOdometer(km, locale)), style = MaterialTheme.typography.bodyMedium) },
    )
    HorizontalDivider()
}

// region — Previews

private fun previewFuel(id: Long, km: Int, ml: Int, day: Int, cost: Int? = null) =
    LogEntry(id, LogType.FUEL, LocalDate.of(2026, 9, day), km, ml, true, cost, null, null)

private fun previewOil(id: Long, km: Int, ml: Int, day: Int) = LogEntry(id, LogType.OIL, LocalDate.of(2026, 9, day), km, ml, null, null, null, null)

private val previewFuelEntries = listOf(
    previewFuel(1, 40_000, 14_000, 1, 2_400), previewFuel(2, 40_200, 15_000, 5, 2_600), previewFuel(3, 40_450, 19_800, 9, 3_400),
    previewFuel(4, 40_640, 14_100, 13, 2_450), previewFuel(5, 40_900, 20_300, 17, 3_500), previewFuel(6, 41_100, 14_500, 21, 2_500),
)

@Composable
private fun FuelStatsPreviewContent() {
    LogbookStatsBody(LogbookStatsState.Fuel(fuelReport(previewFuelEntries)))
}

@Composable
private fun OilStatsPreviewContent() {
    val report = fluidReport(listOf(previewOil(1, 40_000, 100, 1), previewOil(2, 40_500, 50, 8), previewOil(3, 41_500, 150, 20)), LogType.OIL)
    LogbookStatsBody(LogbookStatsState.Fluid(LogType.OIL, report))
}

@Preview(name = "Fuel analysis (light)", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
private fun PreviewLogbookFuelStatsLight() {
    PreviewTheme(dark = false) { FuelStatsPreviewContent() }
}

@Preview(name = "Fuel analysis (dark)", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
private fun PreviewLogbookFuelStatsDark() {
    PreviewTheme(dark = true) { FuelStatsPreviewContent() }
}

@Preview(name = "Oil analysis (light)", showBackground = true, widthDp = 420, heightDp = 640)
@Composable
private fun PreviewLogbookOilStatsLight() {
    PreviewTheme(dark = false) { OilStatsPreviewContent() }
}

@Preview(name = "Oil analysis (dark)", showBackground = true, widthDp = 420, heightDp = 640)
@Composable
private fun PreviewLogbookOilStatsDark() {
    PreviewTheme(dark = true) { OilStatsPreviewContent() }
}

// endregion
