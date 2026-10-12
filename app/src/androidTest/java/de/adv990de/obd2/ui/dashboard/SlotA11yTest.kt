package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QS C (UID-13/SCR-18): Die Kachel per Long-Press neu zu belegen ist die EINZIGE Geste dafuer — ohne Beschriftung der
 * Aktion sagt TalkBack nur "Doppeltippen und halten", nicht wozu. Gefuellte Kachel und leerer Slot tragen dieselbe
 * Beschriftung.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SlotA11yTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Before fun setup() { hiltRule.inject() }

    private val hasLongClick = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

    @Test
    fun aFilledSlot_namesItsLongPressAction() {
        composeRule.setContent {
            SensorCardSlot(
                reading = SensorValue(pid = PidRegistry.COOLANT_TEMP, value = 87f),
                sparklines = emptyMap(), useImperial = false, onLongPress = {},
            )
        }

        val label = composeRule.onNode(hasLongClick).fetchSemanticsNode().config[SemanticsActions.OnLongClick].label
        assertEquals(composeRule.activity.getString(R.string.dashboard_slot_change_action), label)
    }

    @Test
    fun anEmptySlot_namesItsLongPressAction() {
        composeRule.setContent {
            SensorCardSlot(reading = null, sparklines = emptyMap(), useImperial = false, onLongPress = {})
        }

        val label = composeRule.onNode(hasLongClick).fetchSemanticsNode().config[SemanticsActions.OnLongClick].label
        assertEquals(composeRule.activity.getString(R.string.dashboard_slot_change_action), label)
    }
}
