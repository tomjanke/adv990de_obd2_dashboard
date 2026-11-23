package de.adv990de.obd2.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.map.MapInfo
import de.adv990de.obd2.map.MapProblem
import de.adv990de.obd2.map.MapState
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.assertTouchTargetAtLeast56
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Die Zeile „Karte“ der Einstellungen und ihre Dialoge: Zusammenfassung je Zustand, Dateidialog ohne Karte, Angaben, Ersetzen, Entfernen mit Rueckfrage und die Meldung eines gescheiterten Einspielens. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MapSettingDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private val info = MapInfo("Mittelgebirge", null, 9.5, 49.25, 11.0, 51.0, 2, 14, 240L * 1024 * 1024)

    private var picks = 0
    private var removes = 0
    private var dismissed = 0

    private fun show(state: MapState, importState: MapImportUi = MapImportUi.Idle) {
        composeRule.setContent {
            AppTheme(blueColorScheme, AppTypography) {
                MapSettingRow(state, importState, onPick = { picks++ }, onRemove = { removes++ }, onDismissFailure = { dismissed++ })
            }
        }
        composeRule.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    @Test
    fun withoutAMapTheRowSaysSoAndATapOpensTheFilePicker() {
        show(MapState.NoMap)
        composeRule.onNodeWithText(text(R.string.settings_map_none)).assertIsDisplayed()
        composeRule.onNodeWithTag(MAP_ROW_TEST_TAG).performClick()
        assertEquals(1, picks)
    }

    @Test
    fun aBrokenFileOffersTheFilePickerToo() {
        show(MapState.Broken(MapProblem.NOT_PMTILES))
        composeRule.onNodeWithText(text(R.string.settings_map_broken)).assertIsDisplayed()
        composeRule.onNodeWithTag(MAP_ROW_TEST_TAG).performClick()
        assertEquals(1, picks)
    }

    @Test
    fun whileTheFileIsLoadedTheRowSaysSoAndATapDoesNothing() {
        show(MapState.NoMap, MapImportUi.Working)
        composeRule.onNodeWithText(text(R.string.settings_map_working)).assertIsDisplayed()
        composeRule.onNodeWithTag(MAP_ROW_TEST_TAG).performClick()
        assertEquals(0, picks)
        composeRule.onNodeWithTag(MAP_INFO_DIALOG_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun withAMapTheRowNamesItAndATapShowsTheDetails() {
        show(MapState.Ready(info))
        composeRule.onNodeWithTag(MAP_ROW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Mittelgebirge", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(MAP_ROW_TEST_TAG).performClick()
        composeRule.onNodeWithTag(MAP_INFO_DIALOG_TEST_TAG).assertIsDisplayed()
        // Im Querformat reicht die Hoehe nicht fuer alle Zeilen: der Inhalt scrollt, jede Zeile ist erreichbar (die Quellenangabe ist Pflicht).
        composeRule.onNodeWithText(text(R.string.settings_map_coverage, 49.25, 51.0, 9.5, 11.0)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_map_zoom, 2, 14)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.map_attribution)).performScrollTo().assertIsDisplayed()
        assertEquals(0, picks)
    }

    @Test
    fun theDetailButtonsAreBigEnoughForAGlove() {
        show(MapState.Ready(info))
        composeRule.onNodeWithTag(MAP_ROW_TEST_TAG).performClick()
        val density = composeRule.density
        composeRule.onNodeWithTag(MAP_REPLACE_BUTTON_TEST_TAG).assertTouchTargetAtLeast56(density)
        composeRule.onNodeWithTag(MAP_REMOVE_BUTTON_TEST_TAG).assertTouchTargetAtLeast56(density)
    }

    @Test
    fun replacingClosesTheDetailsAndOpensTheFilePicker() {
        show(MapState.Ready(info))
        composeRule.onNodeWithTag(MAP_ROW_TEST_TAG).performClick()
        composeRule.onNodeWithTag(MAP_REPLACE_BUTTON_TEST_TAG).performClick()
        assertEquals(1, picks)
        composeRule.onNodeWithTag(MAP_INFO_DIALOG_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun removingAsksFirstAndOnlyTheConfirmationRemoves() {
        show(MapState.Ready(info))
        composeRule.onNodeWithTag(MAP_ROW_TEST_TAG).performClick()
        composeRule.onNodeWithTag(MAP_REMOVE_BUTTON_TEST_TAG).performClick()
        assertEquals(0, removes)
        composeRule.onNodeWithText(text(R.string.settings_map_remove_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.cancel)).performClick()
        assertEquals(0, removes)
        composeRule.onNodeWithTag(MAP_ROW_TEST_TAG).performClick()
        composeRule.onNodeWithTag(MAP_REMOVE_BUTTON_TEST_TAG).performClick()
        composeRule.onNodeWithText(text(R.string.settings_map_remove)).performClick()
        assertEquals(1, removes)
    }

    @Test
    fun aFailedImportShowsItsReasonAndCloseReportsIt() {
        var current by mutableStateOf(MapProblem.entries.first())
        composeRule.setContent {
            AppTheme(blueColorScheme, AppTypography) { MapSettingRow(MapState.NoMap, MapImportUi.Failed(current), {}, {}, { dismissed++ }) }
        }
        for (problem in MapProblem.entries) {
            current = problem
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(MAP_FAILED_DIALOG_TEST_TAG).assertIsDisplayed()
            composeRule.onNodeWithText(text(mapProblemText(problem))).assertIsDisplayed()
            composeRule.onNodeWithText(text(R.string.close)).performClick()
        }
        assertEquals(MapProblem.entries.size, dismissed)
    }
}
