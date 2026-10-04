package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [SlotPickerSheet]. Zuvor ohne jede Testabdeckung (weder Unit- noch
 * Instrumentation-Test) — die Label-Formatierung (`convertUnit`-basiert, "--"-Fallback fuer
 * NaN/Infinite) und das Auswahl-Callback laufen als reiner Compose-Code direkt in der Composable
 * und lassen sich nur ueber einen echten Compose-Test verifizieren.
 *
 * Setup gespiegelt von [de.adv990de.obd2.ui.settings.GearRatioFieldUiTest]: `@HiltAndroidTest` ist fuer
 * jede Testklasse in diesem Modul noetig (Hilt validiert den vollen App-Graphen inkl.
 * ObdForegroundServices Entry Point pro Testklasse), auch wenn dieser Test selbst kein Hilt-Objekt
 * braucht — ObdRepository muss trotzdem per @BindValue ersetzt werden, weil sein echter
 * Konstruktor Bluetooth-/Verbindungs-Abhaengigkeiten hat, die im Test nicht verfuegbar sind.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SlotPickerSheetTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { isMovingFlow } returns MutableStateFlow(false)
    }

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private val availablePids = listOf(PidRegistry.RPM, PidRegistry.COOLANT_TEMP, PidRegistry.SPEED)

    @Test
    fun showsFormattedLabel_forAReadingWithARealValue() {
        val readings = listOf(SensorValue(PidRegistry.RPM, 2500f))
        composeRule.setContent {
            SlotPickerSheet(
                currentPidId = PidRegistry.RPM.id,
                availablePids = availablePids,
                readings = readings,
                useImperial = false,
                onSelect = {},
                onDismiss = {},
            )
        }
        composeRule.waitForIdle()

        val rpmLabel = composeRule.activity.getString(R.string.pid_rpm)
        // rpm bleibt bei useImperial=false unveraendert (010C -> "rpm", keine Umrechnung),
        // formatiert ohne Nachkommastelle (kein "V"-PID).
        composeRule.onNodeWithText("$rpmLabel: 2500 (rpm)").assertIsDisplayed()
    }

    @Test
    fun showsDashFallback_forNaNAndInfiniteReadings() {
        val readings = listOf(
            SensorValue(PidRegistry.COOLANT_TEMP, Float.NaN),
            SensorValue(PidRegistry.SPEED, Float.POSITIVE_INFINITY),
        )
        composeRule.setContent {
            SlotPickerSheet(
                currentPidId = PidRegistry.RPM.id,
                availablePids = availablePids,
                readings = readings,
                useImperial = false,
                onSelect = {},
                onDismiss = {},
            )
        }
        composeRule.waitForIdle()

        val coolantLabel = composeRule.activity.getString(R.string.pid_coolant_temp)
        val speedLabel = composeRule.activity.getString(R.string.pid_speed)
        // Der "--"-Zweig zeigt bewusst die rohe pid.unit, nicht die (fuer NaN/Infinite ohnehin
        // nicht berechenbare) umgerechnete Anzeigeeinheit.
        composeRule.onNodeWithText("$coolantLabel: -- (°C)").assertIsDisplayed()
        composeRule.onNodeWithText("$speedLabel: -- (km/h)").assertIsDisplayed()
    }

    @Test
    fun tappingAnEntry_invokesOnSelectWithThatPidId() {
        val readings = listOf(SensorValue(PidRegistry.COOLANT_TEMP, 87f))
        var selected: String? = null
        composeRule.setContent {
            SlotPickerSheet(
                currentPidId = PidRegistry.RPM.id,
                availablePids = availablePids,
                readings = readings,
                useImperial = false,
                onSelect = { selected = it },
                onDismiss = {},
            )
        }
        composeRule.waitForIdle()

        val coolantLabel = composeRule.activity.getString(R.string.pid_coolant_temp)
        composeRule.onNodeWithText("$coolantLabel: 87 (°C)").performClick()

        assertTrue(
            "expected onSelect to be invoked with '${PidRegistry.COOLANT_TEMP.id}', was '$selected'",
            selected == PidRegistry.COOLANT_TEMP.id,
        )
    }
}
