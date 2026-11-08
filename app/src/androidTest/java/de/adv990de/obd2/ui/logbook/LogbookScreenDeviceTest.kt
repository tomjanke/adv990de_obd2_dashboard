package de.adv990de.obd2.ui.logbook

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Density
import de.adv990de.obd2.ui.assertTouchTargetAtLeast56
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
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
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogFilter
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.ui.SHORTCUT_TARGET_TEST_TAG
import de.adv990de.obd2.ui.ShortcutNavHost
import de.adv990de.obd2.ui.assertTransferTarget
import de.adv990de.obd2.ui.navigation.Screen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

/**
 * Die Logbuch-Liste ([LogbookScreen]) im echten Hilt-Graphen mit der Test-Datenbank: Leerzustand, Reihenfolge, Filter, die Wege zur Eintragsseite, Loeschen mit
 * Rueckgaengig und der Export-Dialog. Die Eintraege kommen ueber das echte [VehicleLogRepository]; die Importvorschau steht in [LogbookDialogsDeviceTest].
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LogbookScreenDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    // Die Application haengt am ObdRepository; ersetzt wie in den anderen Hilt-Tests (hier unbenutzt).
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Inject
    lateinit var repository: VehicleLogRepository

    @Inject
    lateinit var appPreferences: AppPreferences

    private lateinit var navController: NavHostController

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private val locale: Locale get() = composeRule.activity.resources.configuration.locales[0]

    private fun day(d: Int) = LocalDate.of(2026, 10, d)

    private fun entry(type: LogType, date: LocalDate, km: Int, amountMl: Int? = null, full: Boolean? = null, cost: Int? = null, note: String? = null, brand: String? = null) =
        LogEntry(type = type, date = date, odometerKm = km, amountMl = amountMl, fullTank = full, costCents = cost, note = note, brand = brand)

    /** Legt die Eintraege an und liefert ihre Ids in der Reihenfolge der Eingabe. */
    private fun save(vararg entries: LogEntry): List<Long> = runBlocking { entries.map { repository.save(it) } }

    private fun inDatabase(): List<LogEntry> = runBlocking { repository.all() }

    /** Die Liste in einem NavHost mit der Eintragsseite als Platzhalter, damit sich die Wege (Knopf, Zeile) pruefen lassen. */
    private fun launch() {
        composeRule.setContent {
            navController = rememberNavController()
            ShortcutNavHost(
                navController,
                extraDestinations = {
                    composable(Screen.LogEntry.route, arguments = listOf(navArgument(LOG_ENTRY_ID_ARG) { type = NavType.LongType; defaultValue = 0L })) { Text(ENTRY_MARKER) }
                    composable(Screen.LogbookStats.route, arguments = listOf(navArgument(LOG_STATS_TYPE_ARG) { type = NavType.StringType })) { Text(STATS_MARKER) }
                },
            ) { LogbookScreen(it) }
        }
        composeRule.waitForIdle()
    }

    private fun openedId(): Long? = navController.currentBackStackEntry?.arguments?.getLong(LOG_ENTRY_ID_ARG)

    private fun openedStatsType(): String? = navController.currentBackStackEntry?.arguments?.getString(LOG_STATS_TYPE_ARG)

    /** Die Auswertung steht ueber den Zeilen (Karten, Uebersicht): eine Zeile ist erst da, wenn die Liste zu ihr gescrollt ist. */
    private fun scrollToRow(id: Long) {
        composeRule.onNodeWithTag(LOGBOOK_LIST_TEST_TAG).performScrollToNode(hasTestTag(logbookRowTestTag(id)))
        composeRule.waitForIdle()
    }

    /** Die Ids der gerade zusammengesetzten Zeilen in der Reihenfolge der Liste (eine Liste setzt nur zusammen, was sichtbar ist). */
    private fun composedRowIds(): List<Long> =
        composeRule.onAllNodes(hasTestTagPrefix("logbook_row_")).fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.TestTag].removePrefix("logbook_row_").toLong()
        }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("TestTag startet mit $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    // --- Leerzustand ---

    @Test
    fun anEmptyLogbook_saysSo_disablesTheExport_andOffersTheEntryButtonAndTheImport() {
        launch()

        composeRule.onNodeWithText(string(R.string.logbook_empty)).assertIsDisplayed()
        composeRule.onNodeWithTag(LOGBOOK_EXPORT_TEST_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(LOGBOOK_IMPORT_TEST_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(LOGBOOK_ADD_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun theButtonsAreAtLeast56dp() {
        launch()

        composeRule.onNodeWithTag(LOGBOOK_EXPORT_TEST_TAG).assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp)
        composeRule.onNodeWithTag(LOGBOOK_IMPORT_TEST_TAG).assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp)
        composeRule.onNodeWithTag(LOGBOOK_ADD_TEST_TAG).assertHeightIsAtLeast(56.dp)
        for (filter in LogFilter.entries) composeRule.onNodeWithTag(logbookFilterTestTag(filter)).assertHeightIsAtLeast(56.dp)
    }

    // --- Liste ---

    @Test
    fun theListIsNewestFirst_byDay_thenReading() {
        val ids = save(
            entry(LogType.FUEL, day(1), 1_000, 10_000, true),
            entry(LogType.OIL, day(3), 1_200, 150),
            entry(LogType.FUEL, day(3), 1_300, 11_000, true),
            entry(LogType.CHAIN_SMALL, day(2), 1_100),
        )
        launch()
        // neueste zuerst: Tag 3 bei 1300, Tag 3 bei 1200, Tag 2, Tag 1
        val expected = listOf(ids[2], ids[1], ids[3], ids[0])

        // Zu den obersten Zeilen und zu den untersten scrollen: was dabei zusammengesetzt ist, steht in dieser Reihenfolge, und beide Enden zusammen sind die ganze Liste.
        scrollToRow(expected.first())
        val top = composedRowIds()
        scrollToRow(expected.last())
        val bottom = composedRowIds()
        assertEquals("oben: $top", expected.filter { it in top }, top)
        assertEquals("unten: $bottom", expected.filter { it in bottom }, bottom)
        assertEquals("jede Zeile war einmal da", expected.toSet(), (top + bottom).toSet())
    }

    @Test
    fun aFuelRowShowsKindAmountDateReadingCostAndThePartialFlag() {
        val id = save(entry(LogType.FUEL, day(2), 40_362, 14_370, full = false, cost = 2_743, note = "Shell"))[0]
        launch()
        scrollToRow(id)

        val headline = string(R.string.logbook_row_join, string(R.string.logbook_type_fuel), string(R.string.logbook_fmt_liters, formatLiters(14_370, locale)))
        composeRule.onNodeWithText(headline).assertIsDisplayed()
        val dateAndStand = string(R.string.logbook_row_join, formatLogDate(day(2), locale), string(R.string.logbook_fmt_km, formatOdometer(40_362, locale)))
        composeRule.onNodeWithText(dateAndStand).assertIsDisplayed()
        val extras = string(
            R.string.logbook_row_join,
            string(R.string.logbook_row_join, string(R.string.logbook_fmt_euro, formatEuros(2_743, locale)), string(R.string.logbook_row_partial)),
            "Shell",
        )
        composeRule.onNodeWithText(extras).assertIsDisplayed()
    }

    @Test
    fun aTireChangeRowShowsTheBrand_andAnOilRowItsMilliliters() {
        val ids = save(
            entry(LogType.TIRE_CHANGE, day(2), 37_000, brand = "Michelin Anakee Adventure 150/70-18"),
            entry(LogType.OIL, day(1), 36_000, 150),
        )
        launch()

        scrollToRow(ids[0])
        // Das Fabrikat steht auch in der Uebersicht (Reifenwechsel): die Zeile ist die zweite Stelle mit diesem Text.
        composeRule.onAllNodesWithText("Michelin Anakee Adventure 150/70-18").onLast().assertIsDisplayed()
        scrollToRow(ids[1])
        composeRule.onNodeWithText(string(R.string.logbook_row_join, string(R.string.logbook_type_oil), string(R.string.logbook_fmt_ml, formatMl(150, locale)))).assertIsDisplayed()
    }

    // --- Filter ---

    @Test
    fun theFiltersNarrowTheList_andAnEmptyFilterSaysSoInsteadOfShowingTheEmptyLogbook() {
        val ids = save(
            entry(LogType.FUEL, day(1), 1_000, 10_000, true),
            entry(LogType.OIL, day(2), 1_100, 150),
            entry(LogType.CHAIN_SMALL, day(3), 1_200),
        )
        launch()
        composeRule.onNodeWithTag(logbookFilterTestTag(LogFilter.All)).assertIsSelected()

        composeRule.onNodeWithTag(logbookFilterTestTag(LogFilter.Oil)).performClick()
        composeRule.waitForIdle()
        scrollToRow(ids[1])
        composeRule.onNodeWithTag(logbookRowTestTag(ids[1])).assertIsDisplayed()
        composeRule.onAllNodesWithTag(logbookRowTestTag(ids[0])).assertCountEquals(0)
        composeRule.onAllNodesWithTag(logbookRowTestTag(ids[2])).assertCountEquals(0)

        composeRule.onNodeWithTag(logbookFilterTestTag(LogFilter.Coolant)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.logbook_empty_filter)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.logbook_empty)).assertCountEquals(0)

        composeRule.onNodeWithTag(logbookFilterTestTag(LogFilter.Maintenance)).performClick()
        composeRule.waitForIdle()
        scrollToRow(ids[2])
        composeRule.onNodeWithTag(logbookRowTestTag(ids[2])).assertIsDisplayed()

        composeRule.onNodeWithTag(logbookFilterTestTag(LogFilter.All)).performClick()
        composeRule.waitForIdle()
        for (id in ids) {
            scrollToRow(id)
            composeRule.onNodeWithTag(logbookRowTestTag(id)).assertIsDisplayed()
        }
    }

    // --- Wege zur Eintragsseite ---

    @Test
    fun theEntryButtonOpensTheEntryPageForANewEntry() {
        launch()

        composeRule.onNodeWithTag(LOGBOOK_ADD_TEST_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(ENTRY_MARKER).assertIsDisplayed()
        assertEquals("Id 0 heisst neu", 0L, openedId())
    }

    @Test
    fun aTapOnARowOpensThatEntry() {
        val ids = save(entry(LogType.FUEL, day(1), 1_000, 10_000, true), entry(LogType.OIL, day(2), 1_100, 150))
        launch()

        scrollToRow(ids[1])
        composeRule.onNodeWithTag(logbookRowTestTag(ids[1])).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(ENTRY_MARKER).assertIsDisplayed()
        assertEquals(ids[1], openedId())
    }

    // --- Loeschen mit Rueckgaengig ---

    @Test
    fun deleting_hidesTheRowAtOnce_offersUndo_butKeepsItInTheDatabaseInsideTheWindow() {
        val ids = save(entry(LogType.FUEL, day(1), 1_000, 10_000, true), entry(LogType.OIL, day(2), 1_100, 150))
        launch()

        // Die Liste ist neueste zuerst: der Papierkorb der untersten Zeile gehoert zum aeltesten Eintrag (ids[0]).
        scrollToRow(ids[0])
        composeRule.onAllNodes(hasContentDescription(string(R.string.logbook_cd_delete))).onLast().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.logbook_deleted)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.undo)).assertIsDisplayed()
        scrollToRow(ids[1])
        composeRule.onAllNodes(hasContentDescription(string(R.string.logbook_cd_delete))).assertCountEquals(1)
        composeRule.onAllNodesWithTag(logbookRowTestTag(ids[0])).assertCountEquals(0)
        assertEquals("noch in der Datenbank, Rueckgaengig ist moeglich", 2, inDatabase().size)
        assertTrue("ids[0] ist verborgen, ids[1] steht noch", ids.size == 2)
    }

    @Test
    fun undo_bringsTheEntryBack_andNothingIsEverDeleted() {
        val ids = save(entry(LogType.FUEL, day(1), 1_000, 10_000, true))
        launch()
        scrollToRow(ids[0])
        composeRule.onAllNodes(hasContentDescription(string(R.string.logbook_cd_delete))).onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.undo)).performClick()
        composeRule.waitForIdle()

        scrollToRow(ids[0])
        composeRule.onNodeWithTag(logbookRowTestTag(ids[0])).assertIsDisplayed()
        assertEquals(1, inDatabase().size)
    }

    // Compose verankert eine Liste mit Schluesseln am obersten sichtbaren Element: was darueber eingefuegt wird, laege verdeckt oberhalb (am Geraet gesehen: nach dem Import
    // stand der alte neueste Eintrag oben, die neuen darueber). Seit der Auswertung stehen ueber den Eintraegen immer die Marke, die Abschnitte und die Ueberschrift — diese sind
    // der Anker. Die Tests bilden beide Wege nach, auf denen ein neuer neuester Eintrag in eine stehende Liste kommt, und verlangen, dass die Liste oben bleibt.
    @Test
    fun aNewestEntryThatArrivesWhileTheListIsShown_standsAtTheTop_notHiddenAboveIt() {
        saveOlderEntries()
        launch()
        composeRule.onNodeWithTag(LOGBOOK_OVERVIEW_TEST_TAG).assertIsDisplayed()

        val newest = save(entry(LogType.FUEL, day(5), 1_300, 11_000, true))[0]
        composeRule.waitForIdle()

        // Die Liste steht noch ganz oben (die Uebersicht ist zu sehen, nichts wurde ueber den Rand geschoben), und der neue Eintrag ist die erste Zeile.
        composeRule.onNodeWithTag(LOGBOOK_OVERVIEW_TEST_TAG).assertIsDisplayed()
        scrollToRow(newest)
        assertEquals(newest, composedRowIds().first())
    }

    @Test
    fun undoOfTheNewestEntry_bringsItBackAtTheTop_notHiddenAboveTheList() {
        saveOlderEntries()
        val newest = save(entry(LogType.FUEL, day(5), 1_300, 11_000, true))[0]
        launch()
        scrollToRow(newest)
        composeRule.onAllNodes(hasContentDescription(string(R.string.logbook_cd_delete))).onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.undo)).performClick()
        composeRule.waitForIdle()

        scrollToRow(newest)
        assertEquals("der neueste Eintrag steht wieder ganz oben in der Liste", newest, composedRowIds().first())
    }

    /** So viele aeltere Eintraege, dass die Liste ueber den Bildschirm hinausgeht (passt alles hinein, scrollt Compose von selbst zurueck und der Fehler zeigt sich nicht). */
    private fun saveOlderEntries(count: Int = 30): List<Long> =
        save(*Array(count) { entry(LogType.FUEL, LocalDate.of(2025, 1, 1).plusDays(it.toLong()), 1_000 + 10 * it, 10_000, true) })

    @Test
    fun afterTheUndoWindow_theEntryIsReallyGone() {
        val id = save(entry(LogType.FUEL, day(1), 1_000, 10_000, true))[0]
        launch()
        scrollToRow(id)
        composeRule.onAllNodes(hasContentDescription(string(R.string.logbook_cd_delete))).onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) { inDatabase().isEmpty() }

        composeRule.onNodeWithText(string(R.string.logbook_empty)).assertIsDisplayed()
    }

    @Test
    fun theTrashButtonOfARowIsAtLeast56dp() {
        val id = save(entry(LogType.FUEL, day(1), 1_000, 10_000, true))[0]
        launch()
        scrollToRow(id)

        composeRule.onNodeWithContentDescription(string(R.string.logbook_cd_delete)).assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp)
    }

    // --- Auswertung: Uebersicht und Karten ---

    /** Scrollt die Liste, bis ein Knoten mit diesem Test-Tag zusammengesetzt ist (Uebersicht, Karten und Zeilen liegen untereinander; im Querformat passt nicht alles auf den Schirm). */
    private fun scrollToTag(tag: String) {
        composeRule.onNodeWithTag(LOGBOOK_LIST_TEST_TAG).performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    private fun fuelSeries() = save(
        entry(LogType.FUEL, day(1), 1_000, 10_000, true),
        entry(LogType.FUEL, day(2), 1_200, 12_000, true),
        entry(LogType.FUEL, day(3), 1_500, 24_000, true),
    )

    @Test
    fun withoutAnyEntry_thereIsNoOverviewAndNoCard() {
        launch()

        composeRule.onAllNodesWithTag(LOGBOOK_OVERVIEW_TEST_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(LOGBOOK_STATS_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun forAll_theOverviewAndTheThreeCardsStandAboveTheEntries() {
        val ids = fuelSeries()
        launch()

        composeRule.onNodeWithTag(LOGBOOK_OVERVIEW_TEST_TAG).assertIsDisplayed()
        for (type in listOf(LogType.FUEL, LogType.OIL, LogType.COOLANT)) {
            scrollToTag(logbookStatsCardTestTag(type))
            composeRule.onNodeWithTag(logbookStatsCardTestTag(type)).assertIsDisplayed()
        }
        scrollToRow(ids[2])
        composeRule.onNodeWithTag(logbookRowTestTag(ids[2])).assertIsDisplayed()
    }

    @Test
    fun theFilterDecidesWhichSectionsStandAboveTheEntries() {
        fuelSeries()
        save(entry(LogType.OIL, day(4), 1_600, 100), entry(LogType.CHAIN_SMALL, day(5), 1_650))
        launch()

        fun cardCount(type: LogType) = composeRule.onAllNodesWithTag(logbookStatsCardTestTag(type)).fetchSemanticsNodes().size
        fun overviewCount() = composeRule.onAllNodesWithTag(LOGBOOK_OVERVIEW_TEST_TAG).fetchSemanticsNodes().size

        composeRule.onNodeWithTag(logbookFilterTestTag(LogFilter.Fuel)).performClick()
        composeRule.waitForIdle()
        assertEquals("Tanken zeigt nur die eigene Karte", listOf(1, 0, 0, 0), listOf(cardCount(LogType.FUEL), cardCount(LogType.OIL), cardCount(LogType.COOLANT), overviewCount()))

        composeRule.onNodeWithTag(logbookFilterTestTag(LogFilter.Oil)).performClick()
        composeRule.waitForIdle()
        assertEquals("Oel zeigt nur die eigene Karte", listOf(0, 1, 0, 0), listOf(cardCount(LogType.FUEL), cardCount(LogType.OIL), cardCount(LogType.COOLANT), overviewCount()))

        composeRule.onNodeWithTag(logbookFilterTestTag(LogFilter.Coolant)).performClick()
        composeRule.waitForIdle()
        assertEquals("Kuehlfluessigkeit zeigt nur die eigene Karte", listOf(0, 0, 1, 0), listOf(cardCount(LogType.FUEL), cardCount(LogType.OIL), cardCount(LogType.COOLANT), overviewCount()))

        composeRule.onNodeWithTag(logbookFilterTestTag(LogFilter.Maintenance)).performClick()
        composeRule.waitForIdle()
        assertEquals("Wartung zeigt nur die Uebersicht", listOf(0, 0, 0, 1), listOf(cardCount(LogType.FUEL), cardCount(LogType.OIL), cardCount(LogType.COOLANT), overviewCount()))
    }

    @Test
    fun aCardOpensTheAnalysisPageOfItsKind() {
        fuelSeries()
        save(entry(LogType.OIL, day(4), 1_600, 100))
        launch()

        scrollToTag(logbookStatsCardTestTag(LogType.FUEL))
        composeRule.onNodeWithTag(logbookStatsCardTestTag(LogType.FUEL)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(STATS_MARKER).assertIsDisplayed()
        assertEquals("FUEL", openedStatsType())

        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()
        scrollToTag(logbookStatsCardTestTag(LogType.OIL))
        composeRule.onNodeWithTag(logbookStatsCardTestTag(LogType.OIL)).performClick()
        composeRule.waitForIdle()
        assertEquals("OIL", openedStatsType())
    }

    @Test
    fun theFuelCardShowsTheAverageTheNumberOfIntervalsAndTheLatestValue() {
        fuelSeries()
        launch()
        scrollToTag(logbookStatsCardTestTag(LogType.FUEL))

        // (12 + 24) l / 500 km = 7,2 l/100 km; der letzte Wert 8,0
        val average = string(R.string.logbook_fmt_average, string(R.string.logbook_fmt_l100, formatL100(7.2, locale)))
        val latest = string(R.string.logbook_stats_latest, string(R.string.logbook_fmt_l100, formatL100(8.0, locale)))
        val subtitle = string(R.string.logbook_row_join, composeRule.activity.resources.getQuantityString(R.plurals.logbook_stats_intervals, 2, 2), latest)
        composeRule.onNodeWithText(average, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(subtitle, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aCardWithoutAnalysisSaysWhatIsMissing() {
        save(entry(LogType.FUEL, day(1), 1_000, 10_000, true), entry(LogType.OIL, day(2), 1_100, 100))
        launch()

        scrollToTag(logbookStatsCardTestTag(LogType.FUEL))
        composeRule.onNodeWithText(string(R.string.logbook_stats_empty_fuel), useUnmergedTree = true).assertIsDisplayed()
        scrollToTag(logbookStatsCardTestTag(LogType.COOLANT))
        composeRule.onAllNodesWithText(string(R.string.logbook_stats_empty_fluid), useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun theCardsAreAtLeast56dpHigh() {
        fuelSeries()
        launch()

        for (type in listOf(LogType.FUEL, LogType.OIL, LogType.COOLANT)) {
            scrollToTag(logbookStatsCardTestTag(type))
            composeRule.onNodeWithTag(logbookStatsCardTestTag(type)).assertHeightIsAtLeast(56.dp)
        }
    }

    @Test
    fun theOverviewShowsTheKilometresSinceTheLastEntryTheDateAndWhatIsMissing() {
        save(
            entry(LogType.CHAIN_SMALL, day(2), 40_000),
            entry(LogType.CHAIN_SMALL, day(4), 40_600),
            entry(LogType.TIRE_CHANGE, day(3), 37_000, brand = "Michelin Anakee"),
            entry(LogType.FUEL, day(5), 41_250, 10_000, true),
        )
        launch()

        val chain = logbookOverviewItemTestTag(LogType.CHAIN_SMALL)
        composeRule.onNodeWithTag(chain).assertIsDisplayed()
        // der letzte Eintrag steht bei 40 600, der hoechste Stand im Logbuch bei 41 250: vor 650 km
        composeRule.onNodeWithText(string(R.string.logbook_overview_since, formatOdometer(650, locale)), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.logbook_overview_last, formatLogDate(day(4), locale), formatOdometer(40_600, locale)), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.logbook_overview_spacing, formatKmRounded(600.0, locale)), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasText("Michelin Anakee") and hasAnyAncestor(hasTestTag(logbookOverviewItemTestTag(LogType.TIRE_CHANGE))), useUnmergedTree = true).assertIsDisplayed()
        // Service gross hat keinen Eintrag
        composeRule.onNodeWithTag(logbookOverviewItemTestTag(LogType.SERVICE_LARGE)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.logbook_overview_never), useUnmergedTree = true).assertCountEquals(4)
        composeRule.onNodeWithText(string(R.string.logbook_overview_basis, formatOdometer(41_250, locale))).assertIsDisplayed()
    }

    @Test
    fun aRowShowsTheConsumptionOfTheIntervalItCloses_andOnlyThatRow() {
        val ids = save(
            entry(LogType.FUEL, day(1), 1_000, 10_000, true),
            entry(LogType.FUEL, day(2), 1_200, 12_000, true),
            entry(LogType.OIL, day(3), 1_300, 100),
            entry(LogType.OIL, day(4), 2_300, 150),
        )
        launch()
        val fuel = string(R.string.logbook_fmt_l100, formatL100(6.0, locale))
        val oil = string(R.string.logbook_fmt_ml_per_1000, formatMlPer1000(150.0, locale))

        scrollToRow(ids[1])
        composeRule.onAllNodesWithText(fuel).assertCountEquals(1)
        scrollToRow(ids[3])
        composeRule.onAllNodesWithText(oil).assertCountEquals(1)
        // Die ersten Eintraege ihrer Art schliessen kein Intervall: es gibt genau je einen Wert.
        scrollToRow(ids[0])
        composeRule.onAllNodesWithText(fuel).assertCountEquals(1)
    }

    // --- Erinnerungen: Intervalle, Faelligkeit, GPS-Anteil ---

    private fun setInterval(type: LogType, km: Int) = runBlocking { appPreferences.setMaintenanceInterval(type, km) }

    private fun cell(type: LogType) = composeRule.onNodeWithTag(logbookOverviewItemTestTag(type))

    private fun dueText(everyKm: Int, due: String) = string(R.string.logbook_row_join, string(R.string.logbook_interval_every, formatOdometer(everyKm, locale)), due)

    @Test
    fun withoutAnInterval_aCellOffersToSetOne_andThereIsNoPresetValue() {
        save(entry(LogType.CHAIN_SMALL, day(2), 40_000))
        launch()

        composeRule.onNodeWithTag(LOGBOOK_OVERVIEW_TEST_TAG).assertIsDisplayed()
        // sechs Zellen, jede bietet "Intervall festlegen" an: kein Intervall ist vorbelegt
        composeRule.onAllNodesWithText(string(R.string.logbook_interval_set), useUnmergedTree = true).assertCountEquals(6)
    }

    @Test
    fun withAnInterval_theCellShowsHowFarAwayTheMaintenanceIs() {
        save(entry(LogType.CHAIN_SMALL, day(2), 40_000), entry(LogType.FUEL, day(3), 40_300, 10_000, true))
        setInterval(LogType.CHAIN_SMALL, 1_000)
        launch()

        // 40 000 + 1 000 - 40 300 = 700 km Rest
        composeRule.onNodeWithText(dueText(1_000, string(R.string.logbook_due_in, formatOdometer(700, locale))), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.logbook_interval_set), useUnmergedTree = true).assertCountEquals(5)
    }

    @Test
    fun theStageIsInTheText_soonOverdueAndDueNow() {
        save(
            entry(LogType.CHAIN_SMALL, day(2), 40_000),
            entry(LogType.CHAIN_LARGE, day(2), 40_000),
            entry(LogType.SERVICE_SMALL, day(2), 40_000),
            entry(LogType.FUEL, day(3), 40_950, 10_000, true),
        )
        setInterval(LogType.CHAIN_SMALL, 1_000)   // Rest 50: im letzten Zehntel
        setInterval(LogType.CHAIN_LARGE, 950)     // Rest 0: jetzt faellig
        setInterval(LogType.SERVICE_SMALL, 500)   // Rest -450: ueberfaellig
        launch()

        composeRule.onNodeWithText(dueText(1_000, string(R.string.logbook_due_in, formatOdometer(50, locale))), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(dueText(950, string(R.string.logbook_due_now)), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(dueText(500, string(R.string.logbook_due_overdue, formatOdometer(450, locale))), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aKindWithAnIntervalButNoEntry_showsTheIntervalAndSaysThereIsNoEntry() {
        save(entry(LogType.FUEL, day(3), 40_300, 10_000, true))
        setInterval(LogType.TIRE_CHANGE, 15_000)
        launch()

        composeRule.onNodeWithText(string(R.string.logbook_interval_every, formatOdometer(15_000, locale)), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.logbook_overview_never), useUnmergedTree = true).assertCountEquals(6)
    }

    @Test
    fun gpsKilometresMakeTheNumbersApproximate_andTheLineBelowNamesTheCalculation() {
        save(entry(LogType.CHAIN_SMALL, day(2), 40_000), entry(LogType.FUEL, day(3), 40_300, 10_000, true))
        setInterval(LogType.CHAIN_SMALL, 1_000)
        runBlocking { appPreferences.setGpsOdometer(anchorKm = 40_300, gpsKm = 112.4f) }
        launch()

        // geschaetzt 40 412: seit dem letzten Eintrag 412 km, Rest 588 km, beides mit "ca."
        val since = string(R.string.logbook_overview_since, string(R.string.logbook_fmt_approx, formatOdometer(412, locale)))
        val due = string(R.string.logbook_due_in, string(R.string.logbook_fmt_approx, formatOdometer(588, locale)))
        composeRule.onNodeWithText(since, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(dueText(1_000, due), useUnmergedTree = true).assertIsDisplayed()
        val basis = string(R.string.logbook_overview_basis_approx, formatOdometer(40_412, locale), formatOdometer(40_300, locale), formatOdometer(112, locale))
        composeRule.onNodeWithText(basis).assertExists()
    }

    @Test
    fun withoutGpsKilometres_theNumbersAreExact_noApprox() {
        save(entry(LogType.CHAIN_SMALL, day(2), 40_000), entry(LogType.FUEL, day(3), 40_300, 10_000, true))
        setInterval(LogType.CHAIN_SMALL, 1_000)
        launch()

        composeRule.onNodeWithText(string(R.string.logbook_overview_since, formatOdometer(300, locale)), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.logbook_overview_basis, formatOdometer(40_300, locale))).assertExists()
    }

    @Test
    fun aGpsCounterOfAnotherReadingIsNotUsed() {
        save(entry(LogType.CHAIN_SMALL, day(2), 40_000), entry(LogType.FUEL, day(3), 40_300, 10_000, true))
        setInterval(LogType.CHAIN_SMALL, 1_000)
        runBlocking { appPreferences.setGpsOdometer(anchorKm = 39_000, gpsKm = 500f) }
        launch()

        composeRule.onNodeWithText(string(R.string.logbook_overview_since, formatOdometer(300, locale)), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun tappingACell_opensTheDialog_andSavingWritesTheIntervalAndTheCellFollows() {
        save(entry(LogType.CHAIN_SMALL, day(2), 40_000), entry(LogType.FUEL, day(3), 40_300, 10_000, true))
        launch()

        cell(LogType.CHAIN_SMALL).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.logbook_interval_title, string(R.string.logbook_type_chain_small))).assertIsDisplayed()
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performTextInput("1000")
        composeRule.onNodeWithTag(INTERVAL_SAVE_TEST_TAG).assertTouchTargetAtLeast56(Density(composeRule.activity)).performClick()
        composeRule.waitForIdle()

        assertEquals(mapOf(LogType.CHAIN_SMALL to 1_000), runBlocking { appPreferences.maintenanceIntervals.first() })
        composeRule.onNodeWithText(dueText(1_000, string(R.string.logbook_due_in, formatOdometer(700, locale))), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theDialogShowsTheStoredValue_andAnEmptyFieldClearsTheInterval() {
        save(entry(LogType.CHAIN_SMALL, day(2), 40_000))
        setInterval(LogType.CHAIN_SMALL, 1_000)
        launch()

        cell(LogType.CHAIN_SMALL).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).assertTextContains("1000")
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performTextClearance()
        composeRule.onNodeWithTag(INTERVAL_SAVE_TEST_TAG).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(emptyMap<LogType, Int>(), runBlocking { appPreferences.maintenanceIntervals.first() })
    }

    @Test
    fun anInvalidIntervalDisablesSaving_andSaysWhy() {
        save(entry(LogType.CHAIN_SMALL, day(2), 40_000))
        launch()

        cell(LogType.CHAIN_SMALL).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performTextInput("49")

        composeRule.onNodeWithTag(INTERVAL_SAVE_TEST_TAG).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.logbook_interval_error)).assertIsDisplayed()
    }

    @Test
    fun cancellingTheDialogWritesNothing() {
        save(entry(LogType.CHAIN_SMALL, day(2), 40_000))
        launch()

        cell(LogType.CHAIN_SMALL).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(INTERVAL_FIELD_TEST_TAG).performTextInput("1000")
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        composeRule.waitForIdle()

        assertEquals(emptyMap<LogType, Int>(), runBlocking { appPreferences.maintenanceIntervals.first() })
    }

    @Test
    fun theOverviewCellsAreAtLeast56dpHigh() {
        save(entry(LogType.CHAIN_SMALL, day(2), 40_000))
        launch()

        for (type in de.adv990de.obd2.logbook.MAINTENANCE_TYPES) cell(type).assertHeightIsAtLeast(56.dp)
    }

    // --- Abkuerzungen auf die Seite „Daten sichern und wiederherstellen“ ---
    // Export und Import selbst laufen dort (Tests: DataTransferScreenDeviceTest, DataTransferViewModelTest, TransferExporterTest, TransferImporter*Test); das Logbuch verspricht nur Route und Argumente.

    private fun assertOnTransferPage(mode: String, part: String, pick: Boolean) {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SHORTCUT_TARGET_TEST_TAG).assertIsDisplayed()
        composeRule.runOnUiThread { assertTransferTarget(navController, mode, part, pick) }
    }

    @Test
    fun theExportShortcut_opensTheTransferPage_exportingTheLogbookAsCsv() {
        save(entry(LogType.FUEL, day(1), 1_000, 10_000, true), entry(LogType.OIL, day(2), 1_100, 150))
        launch()

        composeRule.onNodeWithTag(LOGBOOK_EXPORT_TEST_TAG).assertIsEnabled().performClick()

        assertOnTransferPage(mode = "export", part = "LOGBOOK", pick = false)
    }

    @Test
    fun theExportShortcut_isEnabledOnceThereIsAnEntry() {
        save(entry(LogType.CHAIN_KIT_CHANGE, day(1), 1_000))
        launch()

        composeRule.onNodeWithTag(LOGBOOK_EXPORT_TEST_TAG).assertIsEnabled()
    }

    @Test
    fun aDisabledExportShortcut_goesNowhere() {
        launch()

        composeRule.onNodeWithTag(LOGBOOK_EXPORT_TEST_TAG).assertIsNotEnabled().performClick()

        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(SHORTCUT_TARGET_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun theImportShortcut_opensTheTransferPage_inImportMode_andAsksForTheFileAtOnce_evenWithAnEmptyLogbook() {
        launch()

        composeRule.onNodeWithTag(LOGBOOK_IMPORT_TEST_TAG).assertIsEnabled().performClick()

        assertOnTransferPage(mode = "import", part = "", pick = true)
    }

    private companion object {
        const val ENTRY_MARKER = "LOGBOOK_ENTRY_PAGE_MARKER"
        const val STATS_MARKER = "LOGBOOK_STATS_PAGE_MARKER"
    }
}
