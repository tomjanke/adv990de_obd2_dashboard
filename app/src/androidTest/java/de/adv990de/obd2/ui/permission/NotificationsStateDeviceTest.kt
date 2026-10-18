package de.adv990de.obd2.ui.permission

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [rememberNotificationsEnabled] liest den Stand der Benachrichtigungen bei jedem `ON_RESUME` neu: sie lassen sich AUSSERHALB der App ändern (Systemeinstellungen,
 * Berechtigungsdialog), und wer den Wert einmal liest, zeigt danach Veraltetes (wie [rememberLocationPermissionState]). Das System wird hier NICHT verändert (die
 * Berechtigung zur Laufzeit zu entziehen beendet den Testprozess): die Lesefunktion ist die Naht, ein Test setzt sie und schickt Lebenszyklus-Ereignisse.
 */
@RunWith(AndroidJUnit4::class)
class NotificationsStateDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Volatile
    private var systemSaysEnabled = false

    private val owner = FakeOwner()
    private var shown by mutableStateOf(true)

    private fun send(event: Lifecycle.Event) {
        composeRule.runOnUiThread { owner.registry.handleLifecycleEvent(event) }
        composeRule.waitForIdle()
    }

    /** Eine Anzeige "enabled=…" im Lebenszyklus des Test-Besitzers (der ersten Stufe RESUMED), mit der Lesefunktion des Tests. */
    private fun launch() {
        send(Lifecycle.Event.ON_CREATE)
        send(Lifecycle.Event.ON_START)
        send(Lifecycle.Event.ON_RESUME)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (shown) Text("enabled=" + rememberNotificationsEnabled { systemSaysEnabled })
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun theFirstValueIsTheCurrentStateOfTheSystem() {
        systemSaysEnabled = true
        launch()
        composeRule.onNodeWithText("enabled=true").assertExists()
    }

    @Test
    fun aChangeOutsideTheAppIsPickedUpOnTheNextResume_andNotBefore() {
        systemSaysEnabled = false
        launch()
        composeRule.onNodeWithText("enabled=false").assertExists()

        // Der Nutzer erlaubt Benachrichtigungen in den Systemeinstellungen: die App sieht es erst, wenn sie wieder vorn ist.
        systemSaysEnabled = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("enabled=false").assertExists()
        send(Lifecycle.Event.ON_PAUSE)
        composeRule.onNodeWithText("enabled=false").assertExists()

        send(Lifecycle.Event.ON_RESUME)
        composeRule.onNodeWithText("enabled=true").assertExists()
    }

    @Test
    fun theStateIsReadAgainOnEveryResume_notOnlyTheFirst() {
        systemSaysEnabled = false
        launch()
        systemSaysEnabled = true
        send(Lifecycle.Event.ON_PAUSE)
        send(Lifecycle.Event.ON_RESUME)
        composeRule.onNodeWithText("enabled=true").assertExists()

        systemSaysEnabled = false
        send(Lifecycle.Event.ON_PAUSE)
        send(Lifecycle.Event.ON_RESUME)
        composeRule.onNodeWithText("enabled=false").assertExists()
    }

    @Test
    fun theObserverLeavesTheLifecycleWithTheComposition() {
        launch()
        composeRule.runOnUiThread { shown = false }
        composeRule.waitForIdle()

        assertEquals("ein Beobachter bleibt nach dem Verlassen der Komposition im Lebenszyklus hängen", 0, owner.registry.observerCount)
    }

    @Test
    fun withoutASeamTheDefaultReadsTheRealStateOfTheSystem() {
        var shownValue: Boolean? = null
        composeRule.setContent {
            shownValue = rememberNotificationsEnabled()
            Text("probe")
        }
        composeRule.waitForIdle()

        val real = NotificationManagerCompat.from(composeRule.activity).areNotificationsEnabled()
        assertEquals(real, shownValue)
    }
}
