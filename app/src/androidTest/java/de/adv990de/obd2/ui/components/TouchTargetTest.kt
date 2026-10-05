package de.adv990de.obd2.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.ui.settings.DeviceRow
import de.adv990de.obd2.ui.theme.AppTheme
import de.adv990de.obd2.ui.theme.MIN_TOUCH_TARGET
import de.adv990de.obd2.ui.theme.blueColorScheme
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QS C (UID-11/SCR-17): jedes Touch-Ziel ist mindestens 56 dp gross (Design-Prinzip 4, Handschuhe). Gemessen waren
 * der Exit-Dialog mit 40 dp, der Bluetooth-Knopf mit 48 dp und Verbinden/Trennen mit 40 dp. [AppTheme] hebt dafuer
 * `LocalMinimumInteractiveComponentSize` fuer alle Material-Komponenten an; hier steht, dass das fuer die Bausteine
 * und fuer die gemeldeten Stellen auch wirklich ankommt.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TouchTargetTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    // Hilt validiert den App-Graphen je Testklasse (s. DeviceRowTest) — die Komponenten hier nutzen ihn nicht.
    @BindValue @JvmField val obdRepository: ObdRepository = mockk(relaxed = true)

    @Before fun setup() { hiltRule.inject() }

    private fun setAppContent(content: @Composable () -> Unit) = composeRule.setContent {
        AppTheme(colorScheme = blueColorScheme) { Surface { content() } }
    }

    /**
     * Misst die TOUCH-Flaeche, nicht die sichtbare: Material vergroessert sie ueber `minimumInteractiveComponentSize`
     * hinaus, `assertHeightIsAtLeast` sieht davon nichts (ein IconButton meldet dort 40 dp, sein Touch-Ziel ist 48 dp).
     * Fuer den Finger zaehlt die Touch-Flaeche.
     */
    private fun SemanticsNodeInteraction.assertTouchTarget() {
        val bounds = fetchSemanticsNode().touchBoundsInRoot
        val density = Density(composeRule.activity)
        val widthDp = with(density) { bounds.width.toDp() }
        val heightDp = with(density) { bounds.height.toDp() }
        val min = MIN_TOUCH_TARGET.value - 0.5f   // Rundung auf Pixel
        if (widthDp.value < min || heightDp.value < min) {
            throw AssertionError("Touch-Ziel ${widthDp.value} x ${heightDp.value} dp, erwartet mindestens $MIN_TOUCH_TARGET")
        }
    }

    @Test
    fun materialComponents_underTheAppTheme_haveAtLeast56dp() {
        setAppContent {
            Column {
                IconButton(onClick = {}, modifier = Modifier.testTag("icon")) { Icon(Icons.Default.Add, contentDescription = null) }
                Button(onClick = {}, modifier = Modifier.testTag("button")) { Text("A") }
                FilledTonalButton(onClick = {}, modifier = Modifier.testTag("tonal")) { Text("B") }
                OutlinedButton(onClick = {}, modifier = Modifier.testTag("outlined")) { Text("C") }
                TextButton(onClick = {}, modifier = Modifier.testTag("text")) { Text("D") }
                Checkbox(checked = true, onCheckedChange = {}, modifier = Modifier.testTag("checkbox"))
                Switch(checked = true, onCheckedChange = {}, modifier = Modifier.testTag("switch"))
            }
        }

        for (tag in listOf("icon", "button", "tonal", "outlined", "text", "checkbox", "switch")) {
            try {
                composeRule.onNodeWithTag(tag).assertTouchTarget()
            } catch (e: AssertionError) {
                throw AssertionError("'$tag' unterschreitet 56 dp: ${e.message}", e)
            }
        }
    }

    @Test
    fun exitDialog_bothButtons_haveAtLeast56dp() {
        setAppContent { ExitConfirmationDialog(onConfirm = {}, onDismiss = {}) }
        val confirm = composeRule.activity.getString(R.string.exit_app_confirm)
        val cancel = composeRule.activity.getString(R.string.cancel)

        composeRule.onNodeWithText(confirm).assertTouchTarget()
        composeRule.onNodeWithText(cancel).assertTouchTarget()
    }

    @Test
    fun deviceRow_connectAndDisconnectButtons_haveAtLeast56dp() {
        val device = ScannedDevice(device = null, type = BtType.SIMULATOR, name = "OBD Sim")
        setAppContent {
            Column {
                DeviceRow(device = device, onClick = {}, isConnected = false)
                DeviceRow(device = device, onClick = {}, isConnected = true)
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_connect)).assertTouchTarget()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_disconnect)).assertTouchTarget()
    }
}
