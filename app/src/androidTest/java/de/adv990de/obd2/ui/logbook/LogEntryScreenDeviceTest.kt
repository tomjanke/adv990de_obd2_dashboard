package de.adv990de.obd2.ui.logbook

import androidx.compose.material3.Text
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.fuel.FuelRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.logbook.FormField
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.ui.navigation.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import javax.inject.Inject

/**
 * Die Eintragsseite ([LogEntryScreen]) im echten Hilt-Graphen mit der Test-Datenbank, **ueber die echte Route mit ihrem Argument**: welche Felder zu welcher Art
 * gehoeren, Fehler am Feld, die Rueckfrage bei Warnungen, die Frage nach der Tankanzeige, Bearbeiten (Art gesperrt) und der Datumsdialog.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LogEntryScreenDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    // Die Application haengt am ObdRepository; ersetzt wie in den anderen Hilt-Tests (hier unbenutzt).
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Inject lateinit var repository: VehicleLogRepository
    // Lazy: das FuelRepository entsteht erst, NACHDEM die Reichweite geschrieben ist (wie beim App-Start mit gespeicherter Reichweite, wie in den Dashboard-Tests). Entstuende es
    // vorher und die Reichweite kaeme live dazu, haengt der Test an der Verteilung auf einem Hintergrund-Thread und scheiterte jeden zweiten Lauf.
    @Inject lateinit var fuelRepositoryLazy: dagger.Lazy<FuelRepository>
    private val fuelRepository: FuelRepository get() = fuelRepositoryLazy.get()
    @Inject lateinit var appPreferences: AppPreferences

    private lateinit var navController: NavHostController

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @After
    fun unlockOrientation() {
        // Die Activity endet mit dem Test; der Wunsch nach einer Ausrichtung soll das Geraet nicht festhalten, falls ein Test vorher scheitert.
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    private fun requestOrientation(orientation: Int) {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        composeRule.waitForIdle()
    }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun inDatabase(): List<LogEntry> = runBlocking { repository.all() }

    private fun entry(type: LogType, date: LocalDate, km: Int, amountMl: Int? = null, full: Boolean? = null, cost: Int? = null) =
        LogEntry(type = type, date = date, odometerKm = km, amountMl = amountMl, fullTank = full, costCents = cost, note = null, brand = null)

    private fun save(vararg entries: LogEntry): List<Long> = runBlocking { entries.map { repository.save(it) } }

    /** Die Liste (Platzhalter) als Start, die Eintragsseite darueber — so fuehrt `popBackStack` nach dem Speichern sichtbar zurueck. */
    private fun launch(id: Long = 0) {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = "list") {
                composable("list") { Text(LIST_MARKER) }
                composable(Screen.LogEntry.route, arguments = listOf(navArgument(LOG_ENTRY_ID_ARG) { type = NavType.LongType; defaultValue = 0L })) { LogEntryScreen(navController) }
            }
        }
        composeRule.runOnUiThread { navController.navigate(Screen.LogEntry.routeFor(id)) }
        composeRule.waitForIdle()
    }

    /**
     * Prueft, dass der Knoten im Bild ist, **nachdem die Bildschirmtastatur weg ist**. Mit der Tastatur ist die Aussage nicht stabil: sie blendet sich nach "Fertig" bzw. nach dem
     * Tipp auf "Speichern" von selbst aus oder bleibt stehen, je nach Zustand des Geraets (ein voller Lauf nach den Vorschauen zeigte es reproduzierbar, die Klasse allein nie), und die
     * Test-Uhr von Compose kennt die Animation nicht — `waitForIdle()` kehrt davor zurueck. Steht sie noch, endet der sichtbare Teil der Seite (im Querformat 386 von 1008 px) rund
     * 10 px vor dem Rand des Feldes: der Text steht im Bild, der Innenabstand darunter nicht (gemessen: Feld 207..386 statt 207..396), und `assertIsDisplayed` verlangt den ganzen
     * Knoten. Der Test blendet die Tastatur deshalb selbst aus und wartet bis zur Sichtbarkeit (hoechstens 5 s); ein Feld, das nie ins Bild kommt (die Seite scrollte nicht zum
     * Fehler), scheitert weiter — das haelt der Test mit seiner Mutation fest.
     */
    private fun assertDisplayedWithoutKeyboard(node: SemanticsNodeInteraction) {
        composeRule.runOnUiThread {
            val window = composeRule.activity.window
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            try {
                node.assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        node.assertIsDisplayed()
    }

    private fun field(field: FormField) = composeRule.onNodeWithTag(logbookFieldTestTag(field))

    private fun fieldIsThere(field: FormField) = composeRule.onAllNodesWithTag(logbookFieldTestTag(field))

    private fun typeInto(field: FormField, text: String) {
        field(field).performScrollTo().performTextClearance()
        field(field).performTextInput(text)
    }

    private fun fillFuel(odometer: String = "40362", liters: String = "14,37", cost: String = "27,43") {
        typeInto(FormField.ODOMETER, odometer)
        typeInto(FormField.AMOUNT, liters)
        typeInto(FormField.COST, cost)
    }

    private fun tapSave() {
        composeRule.onNodeWithTag(LOGBOOK_SAVE_TEST_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    // --- Felder je Art ---

    @Test
    fun aNewEntryStartsAsARefuel_withTheFuelFieldsAndTheBoxOn() {
        launch()

        composeRule.onNodeWithText(string(R.string.logbook_entry_new_title)).assertIsDisplayed()
        for (f in listOf(FormField.DATE, FormField.ODOMETER, FormField.AMOUNT, FormField.COST, FormField.NOTE)) field(f).assertExists()
        fieldIsThere(FormField.BRAND).assertCountEquals(0)
        composeRule.onNodeWithTag(LOGBOOK_FULL_TANK_TEST_TAG).performScrollTo().assertIsOn()
    }

    @Test
    fun theKindDecidesTheFields() {
        launch()

        composeRule.onNodeWithTag(logbookKindTestTag(LogType.OIL)).performClick()
        composeRule.waitForIdle()
        field(FormField.AMOUNT).assertExists()
        fieldIsThere(FormField.COST).assertCountEquals(0)
        composeRule.onAllNodesWithTag(LOGBOOK_FULL_TANK_TEST_TAG).assertCountEquals(0)
        composeRule.onNodeWithText(string(R.string.logbook_field_amount_ml)).assertExists()

        composeRule.onNodeWithTag(logbookKindTestTag(LogType.TIRE_CHANGE)).performClick()
        composeRule.waitForIdle()
        field(FormField.BRAND).assertExists()
        fieldIsThere(FormField.AMOUNT).assertCountEquals(0)

        composeRule.onNodeWithTag(logbookKindTestTag(LogType.CHAIN_SMALL)).performClick()
        composeRule.waitForIdle()
        fieldIsThere(FormField.BRAND).assertCountEquals(0)
        fieldIsThere(FormField.AMOUNT).assertCountEquals(0)
        fieldIsThere(FormField.COST).assertCountEquals(0)
        field(FormField.ODOMETER).assertExists()
        field(FormField.NOTE).assertExists()
    }

    @Test
    fun allNineKindsAreOffered_eachAtLeast56dp() {
        launch()

        for (type in LogType.entries) composeRule.onNodeWithTag(logbookKindTestTag(type)).assertExists().assertHeightIsAtLeast(56.dp)
        assertEquals(9, LogType.entries.size)
    }

    @Test
    fun theSaveButtonIsAtLeast56dp() {
        launch()

        composeRule.onNodeWithTag(LOGBOOK_SAVE_TEST_TAG).performScrollTo().assertHeightIsAtLeast(56.dp)
    }

    // --- Speichern ---

    @Test
    fun aValidRefuel_isSaved_andTheListComesBack() {
        launch()
        fillFuel()

        tapSave()

        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
        val saved = inDatabase().single()
        assertEquals(LogType.FUEL, saved.type)
        assertEquals(40_362, saved.odometerKm)
        assertEquals(14_370, saved.amountMl)
        assertEquals(2_743, saved.costCents)
        assertEquals(true, saved.fullTank)
        assertEquals(LocalDate.now(), saved.date)
    }

    @Test
    fun theDoneKeyOfTheKeyboard_savesAValidEntry() {
        launch()
        fillFuel()

        field(FormField.NOTE).performScrollTo().performImeAction()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
        assertEquals(1, inDatabase().size)
    }

    @Test
    fun theDoneKeyWithAnInvalidForm_savesNothingAndShowsTheErrors() {
        launch()
        typeInto(FormField.ODOMETER, "abc")

        field(FormField.NOTE).performScrollTo().performImeAction()
        composeRule.waitForIdle()

        assertDisplayedWithoutKeyboard(composeRule.onNodeWithText(string(R.string.logbook_error_odometer_invalid)))
        assertTrue(inDatabase().isEmpty())
    }

    @Test
    fun turningTheFullTankBoxOff_savesAPartialFill() {
        launch()
        fillFuel()

        composeRule.onNodeWithTag(LOGBOOK_FULL_TANK_TEST_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LOGBOOK_FULL_TANK_TEST_TAG).assertIsOff()
        tapSave()

        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()   // eine Teiltankung fragt nicht nach der Tankanzeige
        assertEquals(false, inDatabase().single().fullTank)
    }

    @Test
    fun anEntryWithoutAnAmount_isSavedAsItIs() {
        launch()
        composeRule.onNodeWithTag(logbookKindTestTag(LogType.CHAIN_KIT_CHANGE)).performClick()
        typeInto(FormField.ODOMETER, "36000")

        tapSave()

        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
        assertEquals(LogType.CHAIN_KIT_CHANGE, inDatabase().single().type)
    }

    @Test
    fun anInvalidForm_showsTheErrorsAtTheirFields_andSavesNothing() {
        launch()
        typeInto(FormField.ODOMETER, "abc")

        tapSave()

        assertDisplayedWithoutKeyboard(composeRule.onNodeWithText(string(R.string.logbook_error_odometer_invalid)))
        composeRule.onNodeWithText(string(R.string.logbook_error_missing)).assertExists()   // die Menge fehlt
        composeRule.onAllNodesWithText(LIST_MARKER).assertCountEquals(0)
        assertTrue(inDatabase().isEmpty())
    }

    @Test
    fun aFailedSave_scrollsTheFirstErrorIntoView_inLandscape_whereTheFormDoesNotFitTheWindow() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)   // 448 dp hoch: das Formular passt nicht, egal wie das Geraet gerade steht
        launch()
        typeInto(FormField.ODOMETER, "abc")

        tapSave()   // tippt unten auf "Speichern": der Fehler am Stand steht oben, ausserhalb des kleinen Fensters

        assertDisplayedWithoutKeyboard(composeRule.onNodeWithText(string(R.string.logbook_error_odometer_invalid)))
        assertTrue(inDatabase().isEmpty())
    }

    @Test
    fun theDoneKey_scrollsTheFirstErrorIntoView_inLandscape_whereTheFormDoesNotFitTheWindow() {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        launch()
        typeInto(FormField.ODOMETER, "abc")

        field(FormField.NOTE).performScrollTo().performImeAction()
        composeRule.waitForIdle()

        assertDisplayedWithoutKeyboard(composeRule.onNodeWithText(string(R.string.logbook_error_odometer_invalid)))
    }

    @Test
    fun anEmptyForm_noErrorIsShownBeforeTheFirstTry() {
        launch()

        composeRule.onAllNodesWithText(string(R.string.logbook_error_missing)).assertCountEquals(0)
    }

    @Test
    fun aTireChangeNeedsTheBrand() {
        launch()
        composeRule.onNodeWithTag(logbookKindTestTag(LogType.TIRE_CHANGE)).performClick()
        typeInto(FormField.ODOMETER, "37000")

        tapSave()
        composeRule.onNodeWithText(string(R.string.logbook_error_brand_missing)).performScrollTo().assertIsDisplayed()
        assertTrue(inDatabase().isEmpty())

        typeInto(FormField.BRAND, "Michelin Anakee Adventure 150/70-18")
        tapSave()

        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
        assertEquals("Michelin Anakee Adventure 150/70-18", inDatabase().single().brand)
    }

    @Test
    fun theLastReadingIsShownUnderTheOdometer() {
        save(entry(LogType.FUEL, LocalDate.of(2026, 10, 2), 40_362, 14_370, true))
        launch()
        val locale = composeRule.activity.resources.configuration.locales[0]

        composeRule.onNodeWithText(string(R.string.logbook_last_reading, formatOdometer(40_362, locale), formatLogDate(LocalDate.of(2026, 10, 2), locale))).assertIsDisplayed()
    }

    @Test
    fun thePricePerLiterShowsUnderTheCost() {
        launch()
        fillFuel(liters = "14,37", cost = "27,43")
        val locale = composeRule.activity.resources.configuration.locales[0]

        composeRule.onNodeWithText(string(R.string.logbook_price_per_liter, string(R.string.logbook_fmt_euro_per_liter, formatEuros(191, locale)))).performScrollTo().assertIsDisplayed()
    }

    // --- Warnungen ---

    @Test
    fun aWarning_asksFirst_checkingGoesBack_andConfirmingSaves() {
        save(entry(LogType.FUEL, LocalDate.now().minusDays(10), 41_000, 14_000, true))
        launch()
        fillFuel(odometer = "40362")   // frueheres Datum hat den hoeheren Stand: Widerspruch

        tapSave()
        composeRule.onNodeWithText(string(R.string.logbook_warning_title)).assertIsDisplayed()
        assertEquals("nichts geschrieben, solange gefragt wird", 1, inDatabase().size)

        composeRule.onNodeWithTag(LOGBOOK_WARNING_CHECK_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(string(R.string.logbook_warning_title)).assertCountEquals(0)
        assertEquals(1, inDatabase().size)

        tapSave()
        composeRule.onNodeWithTag(LOGBOOK_WARNING_CONFIRM_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
        assertEquals(2, inDatabase().size)
    }

    // --- Tankanzeige ---

    private fun awaitGauge() {
        runBlocking { appPreferences.setFuelRangeFullKm(300) }
        composeRule.waitUntil(timeoutMillis = 5_000) { fuelRepository.state.value != null }
    }

    @Test
    fun aNewFullTankOfToday_asksAboutTheFuelGauge_yesResetsIt() {
        awaitGauge()
        fuelRepository.addDistance(120f)
        launch()
        fillFuel()

        tapSave()
        composeRule.onNodeWithText(string(R.string.logbook_gauge_title)).assertIsDisplayed()
        assertEquals("die Zeile ist schon gespeichert", 1, inDatabase().size)
        composeRule.onNodeWithTag(LOGBOOK_GAUGE_YES_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
        assertEquals("die Tankanzeige steht wieder auf voll", 0f, fuelRepository.state.value!!.kmSinceFill, 0.001f)
    }

    @Test
    fun theFuelGaugeStays_whenTheAnswerIsNo() {
        awaitGauge()
        fuelRepository.addDistance(120f)
        launch()
        fillFuel()

        tapSave()
        composeRule.onNodeWithTag(LOGBOOK_GAUGE_NO_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
        assertEquals(120f, fuelRepository.state.value!!.kmSinceFill, 0.001f)
    }

    // --- Teilbetankung: die Tankanzeige anheben (F5, Phase C) ---

    /** Eine erste volle Tankung und drei Intervalle zu je 100 km und 7,7 l (erfundene Zahlen): ein bekannter Verbrauch von 7,7 l/100 km. */
    private fun saveSteadyHistory() {
        val today = LocalDate.now()
        save(
            entry(LogType.FUEL, today.minusDays(20), 1_000, 15_000, true),
            entry(LogType.FUEL, today.minusDays(19), 1_100, 7_700, true),
            entry(LogType.FUEL, today.minusDays(18), 1_200, 7_700, true),
            entry(LogType.FUEL, today.minusDays(17), 1_300, 7_700, true),
        )
    }

    private fun savePartFill() {
        launch()
        fillFuel(odometer = "1400", liters = "6,2", cost = "")
        composeRule.onNodeWithTag(LOGBOOK_FULL_TANK_TEST_TAG).performScrollTo().performClick()
        tapSave()
    }

    @Test
    fun aNewPartFillOfToday_asksToRaiseTheFuelGauge_yesRaisesItByTheShownKilometers() {
        awaitGauge()
        fuelRepository.addDistance(120f)
        saveSteadyHistory()

        savePartFill()
        composeRule.onNodeWithText(string(R.string.logbook_gauge_credit_title)).assertIsDisplayed()
        assertEquals("die Zeile ist schon gespeichert", 5, inDatabase().size)
        composeRule.onNodeWithTag(LOGBOOK_GAUGE_CREDIT_YES_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
        // 6,2 l bei 7,7 l/100 km = 81 km: 120 - 81 = 39
        assertEquals(39f, fuelRepository.state.value!!.kmSinceFill, 0.01f)
    }

    @Test
    fun theFuelGaugeStays_whenThePartFillQuestionIsAnsweredNo() {
        awaitGauge()
        fuelRepository.addDistance(120f)
        saveSteadyHistory()

        savePartFill()
        composeRule.onNodeWithTag(LOGBOOK_GAUGE_CREDIT_NO_TEST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
        assertEquals(120f, fuelRepository.state.value!!.kmSinceFill, 0.001f)
    }

    @Test
    fun noPartFillQuestionWithoutAKnownConsumption() {
        awaitGauge()
        fuelRepository.addDistance(120f)

        savePartFill()

        composeRule.onAllNodesWithText(string(R.string.logbook_gauge_credit_title)).assertCountEquals(0)
        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
        assertEquals(120f, fuelRepository.state.value!!.kmSinceFill, 0.001f)
    }

    @Test
    fun noQuestionWithoutAFuelGauge() {
        // keine Reichweite hinterlegt: es gibt keine Tankanzeige, die zurueckzusetzen waere
        launch()
        fillFuel()

        tapSave()

        composeRule.onAllNodesWithText(string(R.string.logbook_gauge_title)).assertCountEquals(0)
        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
    }

    // --- Bearbeiten ---

    @Test
    fun editing_showsTheEntry_locksTheKind_andSavesUnderTheSameId() {
        val ids = save(entry(LogType.FUEL, LocalDate.now().minusDays(3), 40_000, 15_000, false, 1_510))
        launch(ids[0])

        composeRule.onNodeWithText(string(R.string.logbook_entry_edit_title)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(logbookKindTestTag(LogType.OIL)).assertCountEquals(0)   // keine Auswahl der Art mehr
        composeRule.onNodeWithText(string(R.string.logbook_type_fuel)).assertIsDisplayed()
        composeRule.onNodeWithTag(LOGBOOK_FULL_TANK_TEST_TAG).performScrollTo()
        typeInto(FormField.ODOMETER, "40010")

        tapSave()

        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
        val saved = inDatabase().single()
        assertEquals(ids[0], saved.id)
        assertEquals(40_010, saved.odometerKm)
        assertEquals("Menge, Kosten und Teiltankung bleiben", Triple(15_000, 1_510, false), Triple(saved.amountMl, saved.costCents, saved.fullTank))
    }

    @Test
    fun anEntryThatIsGone_leavesThePage() {
        launch(id = 9_999)

        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithText(LIST_MARKER).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithText(LIST_MARKER).assertIsDisplayed()
    }

    // --- Datum ---

    @Test
    fun theDateField_opensAPicker_whichCanBeLeftWithoutChangingTheDate() {
        launch()
        val locale = composeRule.activity.resources.configuration.locales[0]
        val today = formatLogDate(LocalDate.now(), locale)
        composeRule.onNodeWithText(today).assertIsDisplayed()

        field(FormField.DATE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LOGBOOK_DATE_OK_TEST_TAG).assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(LOGBOOK_DATE_OK_TEST_TAG).assertCountEquals(0)
        composeRule.onNodeWithText(today).assertIsDisplayed()
    }

    @Test
    fun confirmingThePicker_keepsTheShownDay() {
        launch()
        val locale = composeRule.activity.resources.configuration.locales[0]

        field(FormField.DATE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LOGBOOK_DATE_OK_TEST_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(formatLogDate(LocalDate.now(), locale)).assertIsDisplayed()
        assertNotNull(field(FormField.DATE))
    }

    private companion object {
        const val LIST_MARKER = "LOGBOOK_LIST_PAGE_MARKER"
    }
}
