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
import java.util.Locale
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
 * Die Zeile "Tankinhalt" (Gruppe "Fahrzeug") und ihr Eingabedialog: Zusammenfassung in Litern, Vorbelegung, Zeichenfilter, Gueltigkeit (3,0 bis 60,0 l mit hoechstens einer
 * Nachkommastelle, leer = loeschen) und was gespeichert wird (Zehntelliter).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class FuelCapacitySettingDeviceTest {

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
    private fun showRow(initialDl: Int) {
        composeRule.setContent {
            MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
                var dl by remember { mutableIntStateOf(initialDl) }
                FuelCapacitySettingRow(capacityDl = dl, onCapacityChange = { saved += it; dl = it })
            }
        }
        composeRule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    /** Der Liter-Text, wie die App ihn in der Sprache des Geraets zeigt. */
    private fun liters(dl: Int) = formatTankCapacity(dl, composeRule.activity.resources.configuration.locales[0] ?: Locale.getDefault())

    private fun openDialog() {
        composeRule.onNodeWithTag(FUEL_CAPACITY_ROW_TEST_TAG).performClick()
        composeRule.waitForIdle()
    }

    private fun type(text: String) {
        composeRule.onNodeWithTag(FUEL_CAPACITY_FIELD_TEST_TAG).performTextClearance()
        if (text.isNotEmpty()) composeRule.onNodeWithTag(FUEL_CAPACITY_FIELD_TEST_TAG).performTextInput(text)
        composeRule.waitForIdle()
    }

    @Test
    fun withoutATankContentTheRowSaysNotSet() {
        showRow(0)

        composeRule.onNodeWithText(str(R.string.settings_fuel_capacity_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.settings_fuel_capacity_not_set)).assertIsDisplayed()
    }

    @Test
    fun withATankContentTheRowShowsItInLiters() {
        showRow(205)

        composeRule.onNodeWithText(str(R.string.settings_fuel_capacity_summary, liters(205))).assertIsDisplayed()
    }

    @Test
    fun theRowIsAtLeast64dpHigh() {
        showRow(200)

        composeRule.onNodeWithTag(FUEL_CAPACITY_ROW_TEST_TAG).assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun theDialogStartsWithTheStoredContentOrEmpty() {
        showRow(205)
        openDialog()
        composeRule.onNodeWithTag(FUEL_CAPACITY_FIELD_TEST_TAG).assert(hasText(liters(205)))
    }

    @Test
    fun theDialogStartsEmptyWithoutAContentAndShowsTheHelp() {
        showRow(0)
        openDialog()

        composeRule.onNodeWithTag(FUEL_CAPACITY_FIELD_TEST_TAG).assert(hasText(""))
        composeRule.onNodeWithText(str(R.string.settings_fuel_capacity_help)).assertIsDisplayed()
    }

    @Test
    fun aValidContentIsSavedAsTenthsAndShownInTheRow() {
        showRow(0)
        openDialog()

        type("20,5")
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(205), saved)
        composeRule.onNodeWithText(str(R.string.settings_fuel_capacity_summary, liters(205))).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertDoesNotExist()
    }

    @Test
    fun aPointWorksLikeAComma() {
        showRow(0)
        openDialog()

        type("20.5")
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(205), saved)
    }

    @Test
    fun anOutOfRangeValueShowsTheErrorAndCannotBeSaved() {
        showRow(0)
        openDialog()

        for (bad in listOf("1", "2,9", "60,1", "100", "0", "20,55")) {
            type(bad)
            composeRule.onNodeWithText(str(R.string.settings_fuel_capacity_error)).assertIsDisplayed()
            composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsNotEnabled()
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun theLimitsThemselvesAreAccepted() {
        showRow(0)
        openDialog()

        for (ok in listOf("3", "3,0", "60", "60,0")) {
            type(ok)
            composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsEnabled()
        }
    }

    @Test
    fun anEmptyFieldClearsTheTankContent() {
        showRow(200)
        openDialog()

        type("")
        composeRule.onNodeWithText(str(R.string.settings_fuel_range_save)).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(0), saved)
        composeRule.onNodeWithText(str(R.string.settings_fuel_capacity_not_set)).assertIsDisplayed()
    }

    @Test
    fun theFieldTakesOnlyDigitsPointAndCommaAndAtMostFiveCharacters() {
        showRow(0)
        openDialog()

        type("2a0x,5")
        composeRule.onNodeWithTag(FUEL_CAPACITY_FIELD_TEST_TAG).assert(hasText("20,5"))

        type("1234567")
        composeRule.onNodeWithTag(FUEL_CAPACITY_FIELD_TEST_TAG).assert(hasText("12345"))
    }

    @Test
    fun theKeyboardDoneKeySavesAValidContentButNotAnInvalidOne() {
        showRow(0)
        openDialog()

        type("2")
        composeRule.onNodeWithTag(FUEL_CAPACITY_FIELD_TEST_TAG).performImeAction()
        composeRule.waitForIdle()
        assertTrue(saved.isEmpty())
        composeRule.onNodeWithText(str(R.string.settings_fuel_capacity_error)).assertIsDisplayed()

        type("20")
        composeRule.onNodeWithTag(FUEL_CAPACITY_FIELD_TEST_TAG).performImeAction()
        composeRule.waitForIdle()
        assertEquals(listOf(200), saved)
    }

    @Test
    fun cancellingSavesNothingAndKeepsTheOldContent() {
        showRow(200)
        openDialog()
        type("30")

        composeRule.onNodeWithText(str(R.string.cancel)).performClick()
        composeRule.waitForIdle()

        assertTrue(saved.isEmpty())
        composeRule.onNodeWithText(str(R.string.settings_fuel_capacity_summary, liters(200))).assertIsDisplayed()
    }
}
