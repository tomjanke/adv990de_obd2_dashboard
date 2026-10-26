package de.adv990de.obd2.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assert
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die Zeile "Reserve" (Gruppe "Fahrzeug") und ihr Eingabedialog: Zusammenfassung, Vorbelegung, Ziffernfilter, Gueltigkeit (5 bis 500 km, leer = aus) und was gespeichert wird.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class FuelReserveSettingDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /** Pflichtbaustein jeder @HiltAndroidTest-Klasse hier, s. [SectionLabelTest]. */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private val saved = mutableListOf<Int>()

    /** Die Zeile mit einem Wert, den der Test haelt (wie das ViewModel es tut): ein Speichern aendert die Zusammenfassung. */
    private fun showRow(initialKm: Int) {
        composeRule.setContent {
            MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
                var km by remember { mutableIntStateOf(initialKm) }
                FuelReserveSettingRow(reserveKm = km, onReserveChange = { saved += it; km = it })
            }
        }
        composeRule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun openDialog() {
        composeRule.onNodeWithTag(FUEL_RESERVE_ROW_TEST_TAG).performClick()
        composeRule.waitForIdle()
    }

    private fun type(text: String) {
        composeRule.onNodeWithTag(FUEL_RESERVE_FIELD_TEST_TAG).performTextClearance()
        if (text.isNotEmpty()) composeRule.onNodeWithTag(FUEL_RESERVE_FIELD_TEST_TAG).performTextInput(text)
        composeRule.waitForIdle()
    }

    @Test
    fun withoutAReserveTheRowSaysOff() {
        showRow(0)

        composeRule.onNodeWithText(str(R.string.settings_fuel_reserve_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.settings_fuel_reserve_off)).assertIsDisplayed()
    }

    @Test
    fun withAReserveTheRowShowsIt() {
        showRow(40)

        composeRule.onNodeWithText(str(R.string.settings_fuel_reserve_summary, 40)).assertIsDisplayed()
    }

    @Test
    fun theRowIsAtLeast64dpHigh() {
        showRow(40)

        composeRule.onNodeWithTag(FUEL_RESERVE_ROW_TEST_TAG).assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun theDialogStartsWithTheStoredReserve() {
        showRow(40)
        openDialog()
        composeRule.onNodeWithTag(FUEL_RESERVE_FIELD_TEST_TAG).assert(hasText("40"))
    }

    @Test
    fun theDialogStartsEmptyWithoutAReserveAndShowsTheHelp() {
        showRow(0)
        openDialog()

        composeRule.onNodeWithTag(FUEL_RESERVE_FIELD_TEST_TAG).assert(hasText(""))
        composeRule.onNodeWithText(str(R.string.settings_fuel_reserve_help)).assertIsDisplayed()
    }

    @Test
    fun aValidReserveIsSavedAndShownInTheRow() {
        showRow(0)
        openDialog()

        type("40")
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(40), saved)
        composeRule.onNodeWithText(str(R.string.settings_fuel_reserve_summary, 40)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertDoesNotExist()
    }

    @Test
    fun anOutOfRangeValueShowsTheErrorAndCannotBeSaved() {
        showRow(0)
        openDialog()

        for (bad in listOf("1", "4", "501", "0")) {
            type(bad)
            composeRule.onNodeWithText(str(R.string.settings_fuel_reserve_error)).assertIsDisplayed()
            composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsNotEnabled()
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun theLimitsThemselvesAreAccepted() {
        showRow(0)
        openDialog()

        for (ok in listOf("5", "500")) {
            type(ok)
            composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsEnabled()
        }
    }

    @Test
    fun anEmptyFieldSwitchesTheReserveOff() {
        showRow(40)
        openDialog()

        type("")
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(0), saved)
        composeRule.onNodeWithText(str(R.string.settings_fuel_reserve_off)).assertIsDisplayed()
    }

    @Test
    fun theFieldTakesOnlyDigitsAndAtMostThree() {
        showRow(0)
        openDialog()

        type("4a0.5,x")
        composeRule.onNodeWithTag(FUEL_RESERVE_FIELD_TEST_TAG).assert(hasText("405"))

        type("123456")
        composeRule.onNodeWithTag(FUEL_RESERVE_FIELD_TEST_TAG).assert(hasText("123"))
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsEnabled()
    }

    @Test
    fun theKeyboardDoneKeySavesAValidReserveButNotAnInvalidOne() {
        showRow(0)
        openDialog()

        type("4")
        composeRule.onNodeWithTag(FUEL_RESERVE_FIELD_TEST_TAG).performImeAction()
        composeRule.waitForIdle()
        assertTrue(saved.isEmpty())
        composeRule.onNodeWithText(str(R.string.settings_fuel_reserve_error)).assertIsDisplayed()

        type("40")
        composeRule.onNodeWithTag(FUEL_RESERVE_FIELD_TEST_TAG).performImeAction()
        composeRule.waitForIdle()
        assertEquals(listOf(40), saved)
    }

    @Test
    fun cancellingSavesNothingAndKeepsTheOldReserve() {
        showRow(40)
        openDialog()
        type("90")

        composeRule.onNodeWithText(str(R.string.cancel)).performClick()
        composeRule.waitForIdle()

        assertTrue(saved.isEmpty())
        composeRule.onNodeWithText(str(R.string.settings_fuel_reserve_summary, 40)).assertIsDisplayed()
    }
}
