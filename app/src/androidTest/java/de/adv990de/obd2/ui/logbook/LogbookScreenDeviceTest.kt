package de.adv990de.obd2.ui.logbook

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogFilter
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.ui.navigation.Screen
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
            NavHost(navController, startDestination = "logbook") {
                composable("logbook") { LogbookScreen(navController) }
                composable(Screen.LogEntry.route, arguments = listOf(navArgument(LOG_ENTRY_ID_ARG) { type = NavType.LongType; defaultValue = 0L })) { Text(ENTRY_MARKER) }
            }
        }
        composeRule.waitForIdle()
    }

    private fun openedId(): Long? = navController.currentBackStackEntry?.arguments?.getLong(LOG_ENTRY_ID_ARG)

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

        val tops = ids.map { composeRule.onNodeWithTag(logbookRowTestTag(it)).getUnclippedBoundsInRoot().top }
        // neueste zuerst: Tag 3 bei 1300, Tag 3 bei 1200, Tag 2, Tag 1
        assertTrue("$tops", tops[2] < tops[1] && tops[1] < tops[3] && tops[3] < tops[0])
    }

    @Test
    fun aFuelRowShowsKindAmountDateReadingCostAndThePartialFlag() {
        save(entry(LogType.FUEL, day(2), 40_362, 14_370, full = false, cost = 2_743, note = "Shell"))
        launch()

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
        save(
            entry(LogType.TIRE_CHANGE, day(2), 37_000, brand = "Michelin Anakee Adventure 150/70-18"),
            entry(LogType.OIL, day(1), 36_000, 150),
        )
        launch()

        composeRule.onNodeWithText("Michelin Anakee Adventure 150/70-18").assertIsDisplayed()
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
        composeRule.onNodeWithTag(logbookRowTestTag(ids[1])).assertIsDisplayed()
        composeRule.onAllNodesWithTag(logbookRowTestTag(ids[0])).assertCountEquals(0)
        composeRule.onAllNodesWithTag(logbookRowTestTag(ids[2])).assertCountEquals(0)

        composeRule.onNodeWithTag(logbookFilterTestTag(LogFilter.Coolant)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.logbook_empty_filter)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.logbook_empty)).assertCountEquals(0)

        composeRule.onNodeWithTag(logbookFilterTestTag(LogFilter.Maintenance)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(logbookRowTestTag(ids[2])).assertIsDisplayed()

        composeRule.onNodeWithTag(logbookFilterTestTag(LogFilter.All)).performClick()
        composeRule.waitForIdle()
        for (id in ids) composeRule.onNodeWithTag(logbookRowTestTag(id)).assertIsDisplayed()
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
        composeRule.onAllNodes(hasContentDescription(string(R.string.logbook_cd_delete))).onLast().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.logbook_deleted)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.undo)).assertIsDisplayed()
        composeRule.onAllNodes(hasContentDescription(string(R.string.logbook_cd_delete))).assertCountEquals(1)
        assertEquals("noch in der Datenbank, Rueckgaengig ist moeglich", 2, inDatabase().size)
        assertTrue("ids[0] ist verborgen, ids[1] steht noch", ids.size == 2)
    }

    @Test
    fun undo_bringsTheEntryBack_andNothingIsEverDeleted() {
        val ids = save(entry(LogType.FUEL, day(1), 1_000, 10_000, true))
        launch()
        composeRule.onAllNodes(hasContentDescription(string(R.string.logbook_cd_delete))).onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.undo)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(logbookRowTestTag(ids[0])).assertIsDisplayed()
        assertEquals(1, inDatabase().size)
    }

    // Compose verankert eine Liste mit Schluesseln am obersten sichtbaren Eintrag: was darueber eingefuegt wird, laege verdeckt oberhalb (am Geraet gesehen: nach dem Import
    // stand der alte neueste Eintrag oben, die neuen darueber). Die Tests bilden beide Wege nach, auf denen ein neuer neuester Eintrag in eine stehende Liste kommt.
    @Test
    fun aNewestEntryThatArrivesWhileTheListIsShown_standsAtTheTop_notHiddenAboveIt() {
        saveOlderEntries()
        launch()

        val newest = save(entry(LogType.FUEL, day(5), 1_300, 11_000, true))[0]
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(logbookRowTestTag(newest)).assertIsDisplayed()
    }

    @Test
    fun undoOfTheNewestEntry_bringsItBackAtTheTop_notHiddenAboveTheList() {
        val older = saveOlderEntries()
        val newest = save(entry(LogType.FUEL, day(5), 1_300, 11_000, true))[0]
        launch()
        composeRule.onAllNodes(hasContentDescription(string(R.string.logbook_cd_delete))).onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.undo)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(logbookRowTestTag(newest)).assertIsDisplayed()
        val top = composeRule.onNodeWithTag(logbookRowTestTag(newest)).getUnclippedBoundsInRoot().top
        assertTrue("der neueste Eintrag steht wieder ganz oben", top < composeRule.onNodeWithTag(logbookRowTestTag(older.last())).getUnclippedBoundsInRoot().top)
    }

    /** So viele aeltere Eintraege, dass die Liste ueber den Bildschirm hinausgeht (passt alles hinein, scrollt Compose von selbst zurueck und der Fehler zeigt sich nicht). */
    private fun saveOlderEntries(count: Int = 30): List<Long> =
        save(*Array(count) { entry(LogType.FUEL, LocalDate.of(2025, 1, 1).plusDays(it.toLong()), 1_000 + 10 * it, 10_000, true) })

    @Test
    fun afterTheUndoWindow_theEntryIsReallyGone() {
        save(entry(LogType.FUEL, day(1), 1_000, 10_000, true))
        launch()
        composeRule.onAllNodes(hasContentDescription(string(R.string.logbook_cd_delete))).onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) { inDatabase().isEmpty() }

        composeRule.onNodeWithText(string(R.string.logbook_empty)).assertIsDisplayed()
    }

    @Test
    fun theTrashButtonOfARowIsAtLeast56dp() {
        save(entry(LogType.FUEL, day(1), 1_000, 10_000, true))
        launch()

        composeRule.onNodeWithContentDescription(string(R.string.logbook_cd_delete)).assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp)
    }

    // --- Export ---

    @Test
    fun theExportIcon_opensTheDialogWithTheCountAndTwoWays_andCancelClosesIt() {
        save(entry(LogType.FUEL, day(1), 1_000, 10_000, true), entry(LogType.OIL, day(2), 1_100, 150))
        launch()

        composeRule.onNodeWithTag(LOGBOOK_EXPORT_TEST_TAG).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.logbook_export_title), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.resources.getQuantityString(R.plurals.logbook_export_message, 2, 2)).assertIsDisplayed()
        composeRule.onNodeWithTag(LOGBOOK_EXPORT_SAVE_TEST_TAG).assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithTag(LOGBOOK_EXPORT_SHARE_TEST_TAG).assertHeightIsAtLeast(56.dp)

        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(string(R.string.logbook_export_title)).assertCountEquals(0)   // der Dialog ist zu (das Symbol traegt den Text nur als Beschreibung)
    }

    @Test
    fun theExportIcon_isEnabledOnceThereIsAnEntry() {
        save(entry(LogType.CHAIN_KIT_CHANGE, day(1), 1_000))
        launch()

        composeRule.onNodeWithTag(LOGBOOK_EXPORT_TEST_TAG).assertIsEnabled()
    }

    private companion object {
        const val ENTRY_MARKER = "LOGBOOK_ENTRY_PAGE_MARKER"
    }
}
