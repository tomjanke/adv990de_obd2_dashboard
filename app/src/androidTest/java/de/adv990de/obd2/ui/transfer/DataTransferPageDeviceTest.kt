package de.adv990de.obd2.ui.transfer

import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.theme.PreviewTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/**
 * Die Seite „Daten sichern und wiederherstellen“ als durchgehender Weg: Seite (ueber ein echtes `NavHost` mit der Route der Abkuerzung „Importieren“) → echtes ViewModel → echter
 * Importer → echte (In-Memory-)Datenbank. Der Dateidialog des Systems ist ein `ActivityResultRegistry`, das eine echte `file:`-Uri liefert (oder bei `null` den Abbruch).
 * Ersetzt den durchgehenden Weg der frueheren Importtests im Verlauf, die mit dem Dialog dort entfielen.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DataTransferPageDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Inject lateinit var db: AppDatabase

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = ArrayList<File>()
    private val today = LocalDate.now()
    private val now = System.currentTimeMillis()
    private val start = now - 3L * 24 * 3_600_000

    /** Die Uri, die der „Dateidialog“ als Naechstes liefert (`null` = der Nutzer bricht ab). */
    private var chosen: Uri? = null
    private var launches = 0

    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches++
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, chosen as O)
        }
    }

    @Before
    fun setup() { hiltRule.inject() }

    @After
    fun teardown() { files.forEach { it.delete() } }

    private fun file(name: String, text: String): Uri = Uri.fromFile(File(ctx.cacheDir, "page_$name").also { it.writeText(text); files += it })

    private fun tripsCsv(startMs: Long = start) = "timestamp,pid,value,name,source,session\n" +
        "$startMs,010C,1612.0,RPM,ECU,1\n${startMs + 1_000},0105,79.0,Coolant,ECU,1\n${startMs + 7_200_000},010C,900.0,RPM,ECU,2\n"

    private val logbookCsv = "date,type,odometer_km,amount_ml,full_tank,cost_eur,note,brand\n${today.minusDays(3)},OIL,2000,300,,,,\n"

    private fun packageFileOf(name: String, vararg entries: Pair<String, String>): Uri {
        val f = File(ctx.cacheDir, "page_$name").also { files += it }
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(f)).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            z.write("""{"format":1,"app_id":"${de.adv990de.obd2.transfer.PackageFormat.APP_ID}","app_version":"1.0","created_ms":$now,"parts":{}}""".toByteArray())
            z.closeEntry()
            for ((n, t) in entries) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        return Uri.fromFile(f)
    }

    private fun openPage(pick: Boolean) {
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }) {
                PreviewTheme(dark = false) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = Screen.DataTransfer.routeFor(TransferMode.IMPORT, pick = pick)) {
                        composable(
                            Screen.DataTransfer.route,
                            arguments = listOf(
                                navArgument(TRANSFER_MODE_ARG) { type = NavType.StringType; defaultValue = "export" },
                                navArgument(TRANSFER_PART_ARG) { type = NavType.StringType; defaultValue = "" },
                                navArgument(TRANSFER_PICK_ARG) { type = NavType.BoolType; defaultValue = false },
                            ),
                        ) { entry -> DataTransferScreen(nav, pick = entry.arguments?.getBoolean(TRANSFER_PICK_ARG) ?: false) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun countOf(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private fun plural(id: Int, n: Int) = composeRule.activity.resources.getQuantityString(id, n, n)

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun waitForText(text: String, substring: Boolean = false) = composeRule.waitUntil(timeoutMillis = 10_000) {
        composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun partToggle(part: DataPart) = composeRule.onNode(isToggleable() and hasAnyAncestor(hasTestTag(transferImportPartTestTag(part))))

    private fun confirm() = composeRule.onNodeWithTag(TRANSFER_CONFIRM_TEST_TAG).performScrollTo().performClick()

    @Test
    fun aChosenFileShowsThePreviewAndConfirmingWritesTheTripsIntoTheDatabase() {
        chosen = file("trips.csv", tripsCsv())
        openPage(pick = true)

        waitForText(plural(R.plurals.trip_import_new, 2))
        composeRule.onNodeWithText(plural(R.plurals.trip_import_readings, 3)).assertIsDisplayed()
        assertEquals("the shortcut opened the dialog once", 1, launches)
        assertEquals("reading alone writes nothing", 0, countOf("sessions"))
        partToggle(DataPart.TRIPS).assertIsOn()

        confirm()

        waitForText(plural(R.plurals.trip_import_done, 2), substring = true)
        assertEquals(2, countOf("sessions"))
        assertEquals(3, countOf("sensor_readings"))
        composeRule.onNodeWithTag(TRANSFER_CLOSE_TEST_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(TRANSFER_PICK_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun theSameFileAgainSaysNothingIsNewAndWritesNothing() {
        chosen = file("trips-again.csv", tripsCsv())
        openPage(pick = true)
        waitForText(plural(R.plurals.trip_import_new, 2))
        confirm()
        waitForText(plural(R.plurals.trip_import_done, 2), substring = true)
        composeRule.onNodeWithTag(TRANSFER_CLOSE_TEST_TAG).performScrollTo().performClick()

        composeRule.onNodeWithTag(TRANSFER_PICK_TEST_TAG).performScrollTo().performClick()

        waitForText(string(R.string.trip_import_nothing_new))
        composeRule.onNodeWithText(plural(R.plurals.trip_import_duplicates, 2)).assertIsDisplayed()
        partToggle(DataPart.TRIPS).assertIsOff()
        composeRule.onNodeWithTag(TRANSFER_CONFIRM_TEST_TAG).performScrollTo().assertIsNotEnabled()
        assertEquals(2, countOf("sessions"))
    }

    @Test
    fun aFileWithoutAnExportOfThisAppSaysSoAndWritesNothing() {
        chosen = file("garbage.bin", "this is not a csv export of the app, just some text without a header line")
        openPage(pick = true)

        waitForText(string(R.string.transfer_error_unrecognized))
        assertEquals(0, countOf("sessions"))
        composeRule.onNodeWithTag(TRANSFER_CLOSE_TEST_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(TRANSFER_PICK_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun aTripOlderThanNinetyDaysIsReportedAsSkippedInThePreview() {
        chosen = file("old.csv", tripsCsv(now - 100L * 24 * 3_600_000))
        openPage(pick = true)

        waitForText(plural(R.plurals.trip_import_expired, 2))
        composeRule.onNodeWithText(string(R.string.trip_import_nothing_new)).assertIsDisplayed()
        composeRule.onNodeWithTag(TRANSFER_CONFIRM_TEST_TAG).performScrollTo().assertIsNotEnabled()
        assertEquals(0, countOf("sessions"))
    }

    @Test
    fun aPackageWithTripsAndLogbookOffersBothPartsAndDeselectingOneKeepsItOut() {
        chosen = packageFileOf("both.zip", "trips.csv" to tripsCsv(), "logbook.csv" to logbookCsv)
        openPage(pick = true)

        waitForText(plural(R.plurals.logbook_import_new, 1))
        composeRule.onNodeWithText(plural(R.plurals.trip_import_new, 2)).assertIsDisplayed()
        partToggle(DataPart.LOGBOOK).assertIsOn()
        partToggle(DataPart.TRIPS).assertIsOn()

        partToggle(DataPart.TRIPS).performScrollTo().performClick()
        partToggle(DataPart.TRIPS).assertIsOff()
        composeRule.onNodeWithTag(TRANSFER_CONFIRM_TEST_TAG).performScrollTo().assertIsEnabled()
        confirm()

        waitForText(plural(R.plurals.logbook_import_done, 1), substring = true)
        assertEquals("the logbook was written", 1, countOf("vehicle_log"))
        assertEquals("the deselected trips were not", 0, countOf("sessions"))
    }

    @Test
    fun cancellingTheFileDialogLeavesThePageWaitingForAChoice() {
        chosen = null
        openPage(pick = true)

        composeRule.onNodeWithTag(TRANSFER_PICK_TEST_TAG).assertIsDisplayed()
        assertEquals(1, launches)
        assertEquals(0, countOf("sessions"))
    }
}
