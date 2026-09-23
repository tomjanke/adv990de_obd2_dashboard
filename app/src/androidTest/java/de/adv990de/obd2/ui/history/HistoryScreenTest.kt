package de.adv990de.obd2.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.SessionEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [HistoryScreen], covering its two [androidx.compose.runtime.LaunchedEffect]
 * reactions to [HistoryViewModel] state that were previously untested (the ViewModel's own state
 * transitions — consumeShareUri/consumeExportError — are covered by [HistoryViewModelTest]):
 *
 * - `LaunchedEffect(exportErrorMessage)`: shows a Snackbar with the formatted error message.
 * - `LaunchedEffect(shareUri)`: builds a share [android.content.Intent] and, only when no app can
 *   handle it (`ActivityNotFoundException`), shows a Snackbar instead.
 *
 * Strategy:
 * - [TestAppModule] (TestInstallIn) still provides BluetoothAdapter/BluetoothDeviceScanner mocks,
 *   the real DataStore and its own in-memory AppDatabase/AppLogDao for the rest of the Hilt graph.
 * - [SessionRepository] is a concrete `@Inject constructor` class (not an interface), so instead of
 *   a bare relaxed mock (which would make `pagingSource()` return nothing and leave the list with
 *   no export button to click), a *second*, test-local in-memory [AppDatabase] is wired into a real
 *   [SessionRepository] instance, then wrapped with [spyk] via `@BindValue` — real paging/insert
 *   behaviour stays intact, and only `exportToCsv` is stubbed per test.
 *
 * [ActivityNotFoundException] path: deliberately NOT exercised here. `HistoryScreen` always wraps
 * the share intent in `Intent.createChooser(...)`, and on a stock device/emulator image the system
 * resolver/chooser activity that action always resolves to is present regardless of which apps can
 * open "text/csv" — there is no way to make `context.startActivity(chooser)` throw
 * `ActivityNotFoundException` from instrumented test code without swapping out the `Context`/
 * `PackageManager` the composable itself resolves via `LocalContext.current`, which would stop
 * exercising the real code path. Per the task's own fallback, coverage here focuses fully on the
 * export-error Snackbar path, which is both real and reliably triggerable.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    /** Test-local in-memory database, separate from TestAppModule's — only SessionRepository uses it. */
    private val testDb = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @BindValue
    @JvmField
    val sessionRepository: SessionRepository = spyk(SessionRepository(targetContext, testDb))

    /**
     * HistoryScreen never touches ObdRepository, but it is still part of the Hilt component graph
     * (ObdForegroundService's entry point, other HiltViewModels' constructors) — real
     * ObdRepository has hardware-coupled deps (ConnectionFactory/PidParser/DtcParser) unavailable
     * here, so it must be replaced exactly like in DashboardScreenTest/DtcScreenTest/FreezeFrameScreenTest.
     */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { isMovingFlow } returns MutableStateFlow(false)
    }

    @Before
    fun setup() {
        hiltRule.inject()
        // A row (and its export IconButton) only renders once at least one session exists.
        // startTime must be "now", not a fixed past timestamp: HistoryViewModel's init block
        // unconditionally calls sessionRepository.deleteOldSessions() (90-day cutoff), which would
        // otherwise delete this row out from under the test before Compose ever renders it — the
        // original fixed 2023 timestamp was already >90 days old and failed for exactly that reason.
        runBlocking {
            testDb.sessionDao().insert(SessionEntity(startTime = System.currentTimeMillis(), vehicleVin = "TESTVIN123"))
        }
    }

    @After
    fun tearDown() {
        testDb.close()
    }

    private fun launchHistoryScreen() {
        composeRule.setContent {
            val navController = rememberNavController()
            HistoryScreen(navController = navController)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun historyScreen_showsExportErrorSnackbar_whenExportFails() {
        coEvery { sessionRepository.exportToCsv(any()) } throws RuntimeException("boom")

        launchHistoryScreen()

        // Paging3 loads the inserted session asynchronously; wait for the row (and its export
        // button) to actually appear before clicking, rather than relying on waitForIdle() alone.
        val exportDescription = composeRule.activity.getString(R.string.history_cd_export)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(exportDescription)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule
            .onNodeWithContentDescription(exportDescription)
            .performClick()

        val expectedMessage = composeRule.activity.getString(R.string.history_export_error_prefix, "boom")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(expectedMessage).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule
            .onNodeWithText(expectedMessage)
            .assertIsDisplayed()
    }
}
