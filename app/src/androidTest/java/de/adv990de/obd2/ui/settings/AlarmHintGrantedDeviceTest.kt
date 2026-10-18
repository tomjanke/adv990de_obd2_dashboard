package de.adv990de.obd2.ui.settings

import android.Manifest
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die Gegenrichtung zu `AlarmSettingsDeviceTest.theHintOnThePageFollowsTheRealNotificationStateOfTheDevice`: ist `POST_NOTIFICATIONS` erteilt, zeigt die Seite KEINEN Hinweis.
 *
 * Eine eigene Klasse, weil [GrantPermissionRule] für die ganze Klasse gilt — in `AlarmSettingsDeviceTest` bliebe der gesperrte Zustand unerreichbar. Erteilt wird
 * nur (wie bei den Standort-Berechtigungen in `SettingsScreenDeviceTest`); entziehen ließe sich die Berechtigung nicht, ohne den Testprozess zu beenden. Ohne diese Klasse
 * hielte auf einem Gerät mit gesperrten Benachrichtigungen auch eine Seite, die den Hinweis IMMER zeigt, alle Tests.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class AlarmHintGrantedDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun withNotificationsAllowed_theSettingsPageShowsNoHint() {
        composeRule.setContent { SettingsScreen(navController = rememberNavController()) }
        composeRule.waitForIdle()

        // Die Voraussetzung selbst: nach dem Erteilen meldet das System erlaubte Benachrichtigungen.
        assertTrue("Benachrichtigungen sind trotz erteilter Berechtigung nicht erlaubt", NotificationManagerCompat.from(composeRule.activity).areNotificationsEnabled())

        // Alle drei Alarme sind an (Standard), die Gruppe also vorhanden — nur der Hinweis fehlt. `performScrollToNode` wirft, wenn keine Zeile passt.
        val list = composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG)
        assertTrue(runCatching { list.performScrollToNode(hasTestTag(ALARM_FAULTS_SWITCH_TEST_TAG)) }.isSuccess)
        assertFalse(runCatching { list.performScrollToNode(hasTestTag(ALARM_NOTIFICATION_HINT_TEST_TAG)) }.isSuccess)
    }
}
