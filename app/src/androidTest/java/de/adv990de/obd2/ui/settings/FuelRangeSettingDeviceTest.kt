package de.adv990de.obd2.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die Zeile "Reichweite bei vollem Tank" (Gruppe "Fahrzeug") und ihr Eingabedialog: Zusammenfassung, Vorbelegung, Ziffernfilter,
 * Gueltigkeit (50 bis 1000 km, leer = loeschen) und was gespeichert wird.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class FuelRangeSettingDeviceTest {

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
                FuelRangeSettingRow(rangeFullKm = km, onRangeChange = { saved += it; km = it })
            }
        }
        composeRule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun openDialog() {
        composeRule.onNodeWithTag(FUEL_RANGE_ROW_TEST_TAG).performClick()
        composeRule.waitForIdle()
    }

    private fun type(text: String) {
        composeRule.onNodeWithTag(FUEL_RANGE_FIELD_TEST_TAG).performTextClearance()
        if (text.isNotEmpty()) composeRule.onNodeWithTag(FUEL_RANGE_FIELD_TEST_TAG).performTextInput(text)
        composeRule.waitForIdle()
    }

    @Test
    fun withoutARangeTheRowSaysNotSet() {
        showRow(0)

        composeRule.onNodeWithText(str(R.string.settings_fuel_range_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_not_set)).assertIsDisplayed()
    }

    @Test
    fun withARangeTheRowShowsIt() {
        showRow(320)

        composeRule.onNodeWithText(str(R.string.settings_fuel_range_summary, 320)).assertIsDisplayed()
    }

    @Test
    fun theDialogStartsWithTheStoredRange() {
        showRow(320)
        openDialog()

        composeRule.onNodeWithTag(FUEL_RANGE_FIELD_TEST_TAG).assert(hasText("320"))
    }

    @Test
    fun theDialogStartsEmptyWithoutARange() {
        showRow(0)
        openDialog()

        composeRule.onNodeWithTag(FUEL_RANGE_FIELD_TEST_TAG).assert(hasText(""))
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_help)).assertIsDisplayed()
    }

    @Test
    fun aValidRangeIsSavedAndShownInTheRow() {
        showRow(0)
        openDialog()

        type("320")
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(320), saved)
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_summary, 320)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertDoesNotExist()
    }

    @Test
    fun anOutOfRangeValueShowsTheErrorAndCannotBeSaved() {
        showRow(0)
        openDialog()

        for (bad in listOf("10", "49", "1001", "0")) {
            type(bad)
            composeRule.onNodeWithText(str(R.string.settings_fuel_range_error)).assertIsDisplayed()
            composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsNotEnabled()
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun theLimitsThemselvesAreAccepted() {
        showRow(0)
        openDialog()

        for (ok in listOf("50", "1000")) {
            type(ok)
            composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsEnabled()
        }
    }

    @Test
    fun anEmptyFieldClearsTheRange() {
        showRow(320)
        openDialog()

        type("")
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(0), saved)
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_not_set)).assertIsDisplayed()
    }

    @Test
    fun theFieldTakesOnlyDigitsAndAtMostFour() {
        showRow(0)
        openDialog()

        type("3a2.5,x")
        composeRule.onNodeWithTag(FUEL_RANGE_FIELD_TEST_TAG).assert(hasText("325"))

        type("123456")
        composeRule.onNodeWithTag(FUEL_RANGE_FIELD_TEST_TAG).assert(hasText("1234"))
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsNotEnabled() // 1234 > 1000
    }

    @Test
    fun cancellingSavesNothingAndKeepsTheOldRange() {
        showRow(320)
        openDialog()
        type("450")

        composeRule.onNodeWithText(str(R.string.cancel)).performClick()
        composeRule.waitForIdle()

        assertTrue(saved.isEmpty())
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_summary, 320)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertDoesNotExist()
    }
}
