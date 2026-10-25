package de.adv990de.obd2.ui.logbook

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.fluidReport
import de.adv990de.obd2.logbook.fuelReport
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.theme.AppTypography
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

/**
 * Die Detailseite der Auswertung ([LogbookStatsScreen], Inhalt [LogbookStatsBody]) auf dem Geraet: das Diagramm mit seiner Beschreibung, die Kennzahlen mit Einheit, die Intervalle (juengstes zuerst),
 * die Hinweise, der Leerzustand und der Weg dorthin und zurueck im echten Hilt-Graphen. Die Rechnung selbst pruefen die JVM-Tests; hier steht, was der Nutzer davon liest.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LogbookStatsScreenDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Inject lateinit var repository: VehicleLogRepository

    private lateinit var navController: NavHostController

    @Before fun setup() { hiltRule.inject() }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun plural(id: Int, count: Int, vararg args: Any) = composeRule.activity.resources.getQuantityString(id, count, *args)

    private val locale: Locale get() = composeRule.activity.resources.configuration.locales[0]

    private fun day(d: Int) = LocalDate.of(2026, 9, d)

    private fun fuel(id: Long, km: Int, ml: Int, d: Int, full: Boolean = true, cost: Int? = null) =
        LogEntry(id, LogType.FUEL, day(d), km, ml, full, cost, null, null)

    private fun fluid(type: LogType, id: Long, km: Int, ml: Int, d: Int) = LogEntry(id, type, day(d), km, ml, null, null, null, null)

    private fun showBody(state: LogbookStatsState) {
        composeRule.setContent { MaterialTheme(colorScheme = lightColorScheme(), typography = AppTypography) { Surface { LogbookStatsBody(state) } } }
        composeRule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag(LOGBOOK_STATS_LIST_TEST_TAG).performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    /** Der Wert einer Kennzahl: der Text in ihrer Zeile. */
    private fun figure(name: String, value: String) =
        composeRule.onNode(hasText(value) and hasAnyAncestor(hasTestTag(logbookFigureTestTag(name))), useUnmergedTree = true)

    // 1 000 (10 l, Bezug) → 1 200 (12 l: 6,0) → 1 500 (24 l: 8,0) → 1 700 (14 l: 7,0): Ø 50 l / 700 km = 7,142… l/100 km
    private val fuelEntries = listOf(
        fuel(1, 1_000, 10_000, 1, cost = 1_500), fuel(2, 1_200, 12_000, 3, cost = 1_800), fuel(3, 1_500, 24_000, 6, cost = 3_600), fuel(4, 1_700, 14_000, 9, cost = 2_100),
    )

    // --- Tanken ---

    @Test
    fun theFuelPage_showsTheChartWithItsDescription_andTheKeyFiguresWithUnits() {
        showBody(LogbookStatsState.Fuel(fuelReport(fuelEntries)))

        val average = string(R.string.logbook_fmt_l100, formatL100(50_000 / (10.0 * 700), locale))
        val latest = string(R.string.logbook_fmt_l100, formatL100(7.0, locale))
        composeRule.onNodeWithTag(LOGBOOK_STATS_DETAIL_CHART_TEST_TAG).assertIsDisplayed()
        composeRule.onNode(hasContentDescription(plural(R.plurals.logbook_chart_description, 3, 3, average, latest))).assertExists()

        scrollTo(logbookFigureTestTag("cost_100"))
        figure("average", average).assertExists()
        figure("latest", latest).assertExists()
        figure("best", string(R.string.logbook_fmt_l100, formatL100(6.0, locale))).assertExists()
        figure("worst", string(R.string.logbook_fmt_l100, formatL100(8.0, locale))).assertExists()
        figure("intervals", formatOdometer(3, locale)).assertExists()
        figure("total_km", string(R.string.logbook_fmt_km, formatOdometer(700, locale))).assertExists()
        figure("total_liters", string(R.string.logbook_fmt_liters, formatLitersTotal(60_000, locale))).assertExists()
        figure("avg_distance", string(R.string.logbook_fmt_km, formatKmRounded(700 / 3.0, locale))).assertExists()
        // Preis: (1 500 + 1 800 + 3 600 + 2 100) Cent / 60 l = 150 Cent je Liter; Kosten je 100 km aus den drei Intervallen: 7 500 Cent / 700 km
        figure("avg_price", string(R.string.logbook_fmt_euro_per_liter, formatPricePerLiter(150.0, locale))).assertExists()
        figure("cost_100", string(R.string.logbook_fmt_euro_per_100, formatEurosDouble(7_500 * 100.0 / 700, locale))).assertExists()
    }

    @Test
    fun withoutCosts_thePageHasNoPriceAndNoCostPer100Km() {
        showBody(LogbookStatsState.Fuel(fuelReport(listOf(fuel(1, 1_000, 10_000, 1), fuel(2, 1_200, 12_000, 3)))))

        scrollTo(logbookFigureTestTag("avg_distance"))
        composeRule.onAllNodesWithTag(logbookFigureTestTag("avg_price")).assertCountEquals(0)
        composeRule.onAllNodesWithTag(logbookFigureTestTag("cost_100")).assertCountEquals(0)
    }

    /** Die Ids der gerade zusammengesetzten Intervall-Zeilen in der Reihenfolge der Liste (eine Liste setzt nur zusammen, was sichtbar ist). */
    private fun composedIntervalIds(): List<Long> =
        composeRule.onAllNodes(SemanticsMatcher("TestTag startet mit logbook_interval_") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("logbook_interval_") == true
        }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix("logbook_interval_").toLong() }

    @Test
    fun theIntervalsAreListedNewestFirst_eachWithConsumptionDateRangeAmountAndDistance() {
        showBody(LogbookStatsState.Fuel(fuelReport(fuelEntries)))

        // Das juengste Intervall (Ende 1 700, Id 4) steht zuerst, das aelteste (Ende 1 200, Id 2) zuletzt: zu beiden Enden scrollen, was dort zusammengesetzt ist, steht in dieser Reihenfolge.
        val expected = listOf(4L, 3L, 2L)
        scrollTo(logbookIntervalTestTag(4))
        val top = composedIntervalIds()
        scrollTo(logbookIntervalTestTag(2))
        val bottom = composedIntervalIds()
        assertEquals("oben: $top", expected.filter { it in top }, top)
        assertEquals("unten: $bottom", expected.filter { it in bottom }, bottom)
        assertEquals(expected.toSet(), (top + bottom).toSet())

        val range = string(R.string.logbook_interval_range, formatOdometer(1_500, locale), formatOdometer(1_700, locale))
        val amount = string(R.string.logbook_fmt_liters, formatLiters(14_000, locale))
        scrollTo(logbookIntervalTestTag(4))
        fun inRow(text: String) = composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(logbookIntervalTestTag(4))), useUnmergedTree = true)
        inRow(string(R.string.logbook_fmt_l100, formatL100(7.0, locale))).assertIsDisplayed()
        inRow(string(R.string.logbook_row_join, string(R.string.logbook_row_join, formatLogDate(day(9), locale), range), amount)).assertIsDisplayed()
        inRow(string(R.string.logbook_fmt_km, formatOdometer(200, locale))).assertIsDisplayed()
    }

    @Test
    fun withoutTwoFullFillUps_thePageSaysWhatIsNeeded_andShowsNoChartNoFigures() {
        showBody(LogbookStatsState.Fuel(fuelReport(listOf(fuel(1, 1_000, 10_000, 1)))))

        composeRule.onNodeWithText(string(R.string.logbook_stats_empty_fuel)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(LOGBOOK_STATS_DETAIL_CHART_TEST_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(logbookFigureTestTag("average")).assertCountEquals(0)
    }

    @Test
    fun theFuelHint_andTheNumberOfSkippedIntervals_stand_underTheFigures() {
        showBody(LogbookStatsState.Fuel(fuelReport(listOf(fuel(1, 1_000, 10_000, 1), fuel(2, 1_000, 8_000, 2), fuel(3, 1_200, 12_000, 3)))))

        scrollTo(LOGBOOK_STATS_HINTS_TEST_TAG)
        composeRule.onNodeWithText(string(R.string.logbook_stats_hint_fuel)).assertExists()
        composeRule.onNodeWithText(plural(R.plurals.logbook_stats_skipped, 1, 1)).assertExists()
    }

    // --- Oel und Kuehlfluessigkeit ---

    @Test
    fun theOilPage_showsAverageSumsAndTheIntervals_andTheHintAboutChanges() {
        val entries = listOf(
            fluid(LogType.OIL, 1, 10_000, 100, 1), fluid(LogType.OIL, 2, 10_500, 50, 5), fluid(LogType.OIL, 3, 11_500, 150, 9),
        )
        showBody(LogbookStatsState.Fluid(LogType.OIL, fluidReport(entries, LogType.OIL)))

        val average = string(R.string.logbook_fmt_ml_per_1000, formatMlPer1000(200.0 / 1.5, locale))
        composeRule.onNodeWithTag(LOGBOOK_STATS_DETAIL_CHART_TEST_TAG).assertIsDisplayed()
        figure("average", average).assertExists()
        figure("latest", string(R.string.logbook_fmt_ml_per_1000, formatMlPer1000(150.0, locale))).assertExists()
        figure("intervals", formatOdometer(2, locale)).assertExists()
        figure("total_amount", string(R.string.logbook_fmt_ml, formatMl(200, locale))).assertExists()
        figure("total_km", string(R.string.logbook_fmt_km, formatOdometer(1_500, locale))).assertExists()
        scrollTo(logbookIntervalTestTag(2))
        composeRule.onNodeWithTag(logbookIntervalTestTag(2)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.logbook_stats_hint_fluid)).assertExists()
    }

    @Test
    fun withoutAnIntervalTheFluidPageSaysSo() {
        showBody(LogbookStatsState.Fluid(LogType.COOLANT, fluidReport(listOf(fluid(LogType.COOLANT, 1, 10_000, 200, 1)), LogType.COOLANT)))

        composeRule.onNodeWithText(string(R.string.logbook_stats_empty_fluid)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(LOGBOOK_STATS_DETAIL_CHART_TEST_TAG).assertCountEquals(0)
    }

    // --- Der Weg hin und zurueck, im echten Graphen ---

    private fun launch() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = "home") {
                composable("home") { Text(HOME_MARKER) }
                composable(Screen.LogbookStats.route, arguments = listOf(navArgument(LOG_STATS_TYPE_ARG) { type = NavType.StringType })) { LogbookStatsScreen(navController) }
            }
        }
        composeRule.waitForIdle()
    }

    private fun openStats(type: String) {
        composeRule.runOnUiThread { navController.navigate("logbook_stats/$type") }
        composeRule.waitForIdle()
    }

    @Test
    fun thePageForTheKindShowsItsTitle_theDataOfTheRepository_andTheBackButtonLeavesIt() {
        runBlocking { fuelEntries.forEach { repository.save(it.copy(id = 0)) } }
        launch()

        openStats("FUEL")

        composeRule.onNodeWithText(string(R.string.logbook_stats_title_fuel)).assertIsDisplayed()
        composeRule.onNodeWithTag(LOGBOOK_STATS_DETAIL_CHART_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.nav_back_desc)).assertHeightIsAtLeast(56.dp).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(HOME_MARKER).assertIsDisplayed()
    }

    @Test
    fun oilAndCoolantHaveTheirOwnTitles() {
        launch()

        openStats("OIL")
        composeRule.onNodeWithText(string(R.string.logbook_stats_title_oil)).assertIsDisplayed()
        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()
        openStats("COOLANT")
        composeRule.onNodeWithText(string(R.string.logbook_stats_title_coolant)).assertIsDisplayed()
    }

    @Test
    fun anUnusableKindGoesStraightBack() {
        launch()

        openStats("CHAIN_SMALL")

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(HOME_MARKER).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(HOME_MARKER).assertIsDisplayed()
    }

    @Test
    fun theLoadingStateAndAnInvalidStateShowNothing() {
        showBody(LogbookStatsState.Loading)
        composeRule.onAllNodesWithTag(LOGBOOK_STATS_LIST_TEST_TAG).assertCountEquals(0)
    }

    private companion object {
        const val HOME_MARKER = "LOGBOOK_HOME_MARKER"
    }
}
