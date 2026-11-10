package de.adv990de.obd2

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.data.ObdRepository
import io.mockk.coJustRun
import io.mockk.coVerify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ObdForegroundService ist ein gestarteter, kein gebundener Service — [ObdForegroundService.onBind]
 * liefert null. Deshalb kommt hier bewusst keine `ServiceTestRule` zum Einsatz: die bindet immer
 * und wartet auf `onServiceConnected`, was mit einem Null-Binder nie eintritt.
 *
 * Der ACTION_START-Pfad braucht zwei Dinge, die die Testumgebung nicht von selbst mitbringt:
 * den Notification-Channel (den nur `Obd2Application` anlegt, waehrend in Hilt-Tests
 * `HiltTestApplication` laeuft) und eine zur Laufzeit erteilte Bluetooth-Berechtigung. Ohne
 * letztere lehnt das System den Start eines Foreground-Service vom Typ `connectedDevice` mit
 * einer SecurityException ab. Beides stellt die Klasse deshalb selbst her.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ObdForegroundServiceDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    /**
     * Ein Foreground-Service vom Typ `connectedDevice` verlangt neben
     * FOREGROUND_SERVICE_CONNECTED_DEVICE mindestens eine der Bluetooth-Berechtigungen —
     * sonst wirft `startForeground()` eine SecurityException. Der Typ `location` braucht den (genauen oder ungefaehren) Standort;
     * der Hintergrund-Standort wird bewusst NICHT erteilt: so laeuft der Typ im echten Betrieb (seit 2026-11-10).
     */
    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /**
     * Ersetzt die @Singleton-Bindung fuer diese Testklasse. Hilt injiziert den Mock direkt in
     * den Service, ohne die echte Bluetooth-Infrastruktur aufzubauen.
     */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository {
        coJustRun { disconnectByUser() }
    }

    @javax.inject.Inject lateinit var serviceController: ObdServiceController

    @javax.inject.Inject lateinit var appPreferences: de.adv990de.obd2.data.AppPreferences

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** Wartet, bis `runningIncludesLocation` den erwarteten Wert hat — der Dienst setzt ihn auf dem Main-Thread nach `startForeground()`. */
    private fun awaitRunningType(expected: Boolean?, what: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (ObdForegroundService.runningIncludesLocation != expected && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(what, expected, ObdForegroundService.runningIncludesLocation)
    }

    // Ab API 26 darf ein Prozess im Hintergrund kein startService() aufrufen. Die Activity
    // holt den Testprozess in den Vordergrund und macht den Aufruf damit zulaessig.
    private lateinit var scenario: ActivityScenario<HiltTestActivity>

    @Before
    fun setup() {
        hiltRule.inject()
        // Den Channel legt sonst Obd2Application an, die unter HiltTestApplication nie laeuft. Dieselbe Funktion wie in
        // der Produktion (B36) statt eines nachgebauten Kanals mit eigener Wichtigkeit und eigenem Namen.
        createObdNotificationChannel(ctx)
        scenario = ActivityScenario.launch(HiltTestActivity::class.java)
    }

    @After
    fun teardown() {
        scenario.close()
    }

    // --- B36: der Kanal, den die Produktion anlegt ---

    @Test
    fun notificationChannel_isCreatedWithTheProductionIdAndLowImportance() {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(ObdForegroundService.CHANNEL_ID)
        assertNull("Ausgangslage: kein Kanal", manager.getNotificationChannel(ObdForegroundService.CHANNEL_ID))

        createObdNotificationChannel(ctx)

        val channel = manager.getNotificationChannel(ObdForegroundService.CHANNEL_ID)
        assertNotNull("der Dienst postet auf diesem Kanal — ohne ihn erscheint die Benachrichtigung nie", channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(ctx.getString(R.string.notif_channel_name), channel.name.toString())
    }

    // --- Test 1 ---

    @Test
    fun actionStop_disconnectsTheRepository_asAUserDisconnect() {
        ctx.startService(ObdForegroundService.stopIntent(ctx))

        // Die Trennung laeuft in einer Coroutine auf Dispatchers.Main — coVerify wartet darauf,
        // statt den Test mit einem festen Thread.sleep zu bezahlen.
        coVerify(timeout = 5_000) { obdRepository.disconnectByUser() }
    }

    // --- Test 2 ---

    @Test
    fun unknownAction_stopsWithoutDisconnecting() {
        ctx.startService(Intent(ctx, ObdForegroundService::class.java))

        // Der else-Zweig ruft nur stopSelf(). Kurz warten, damit ein faelschlicher
        // disconnect()-Aufruf ueberhaupt auflaufen koennte.
        Thread.sleep(500)
        coVerify(exactly = 0) { obdRepository.disconnectByUser() }
    }

    // --- Test 3 ---

    @Test
    fun actionStart_movesServiceToForegroundWithItsNotification() {
        ctx.startService(ObdForegroundService.startIntent(ctx))

        val nm = ctx.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 5_000
        var found = false
        while (!found && System.currentTimeMillis() < deadline) {
            found = nm.activeNotifications.any { it.notification.channelId == ObdForegroundService.CHANNEL_ID }
            if (!found) Thread.sleep(100)
        }

        assertTrue("Foreground-Notification des Service nicht gefunden", found)
        ctx.startService(ObdForegroundService.stopIntent(ctx))
    }

    // --- Test 4 ---

    @Test
    fun actionStart_withLocationType_movesServiceToForegroundWithoutCrashing() {
        ctx.startService(ObdForegroundService.startIntent(ctx, includeLocationType = true))

        val nm = ctx.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 5_000
        var found = false
        while (!found && System.currentTimeMillis() < deadline) {
            found = nm.activeNotifications.any { it.notification.channelId == ObdForegroundService.CHANNEL_ID }
            if (!found) Thread.sleep(100)
        }

        assertTrue("Foreground-Notification (mit location-Typ) nicht gefunden", found)
        ctx.startService(ObdForegroundService.stopIntent(ctx))
    }

    // --- B1: Antippen der Benachrichtigung oeffnet die App ---

    @Test
    fun actionStart_notificationCanBeTappedToOpenTheApp() {
        ctx.startService(ObdForegroundService.startIntent(ctx))

        val nm = ctx.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 5_000
        var notification: android.app.Notification? = null
        while (notification == null && System.currentTimeMillis() < deadline) {
            notification = nm.activeNotifications
                .firstOrNull { it.notification.channelId == ObdForegroundService.CHANNEL_ID }?.notification
            if (notification == null) Thread.sleep(100)
        }

        assertNotNull("Foreground-Notification nicht gefunden", notification)
        assertNotNull("ohne contentIntent tat ein Antippen nichts (B1)", notification!!.contentIntent)
        ctx.startService(ObdForegroundService.stopIntent(ctx))
    }

    @Test
    fun openAppIntent_bringsTheRunningActivityToTheFront_insteadOfStartingASecondOne() {
        val intent = ObdForegroundService.openAppIntent(ctx)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        // NEW_TASK ist fuer eine PendingIntent aus einem Dienst Pflicht; CLEAR_TOP + SINGLE_TOP verhindern eine zweite
        // MainActivity mit leerem ViewModel (sie bekommt onNewIntent).
        val required = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(required, intent.flags and required)
    }

    // --- Dienst-Typ bei laufender Verbindung ---

    @Test
    fun theServiceRemembersWhichTypeItRunsWith_andForgetsItWhenItEnds() {
        awaitRunningType(null, "Ausgangslage: der Dienst laeuft nicht")

        ctx.startService(ObdForegroundService.startIntent(ctx, includeLocationType = false))
        awaitRunningType(false, "gestartet ohne Standort-Typ")

        ctx.startService(ObdForegroundService.stopIntent(ctx))
        awaitRunningType(null, "nach dem Stopp laeuft er nicht mehr")
    }

    @Test
    fun aSecondStartWithTheLocationTypeUpgradesTheRunningService() {
        ctx.startService(ObdForegroundService.startIntent(ctx, includeLocationType = false))
        awaitRunningType(false, "gestartet ohne Standort-Typ")

        ctx.startService(ObdForegroundService.startIntent(ctx, includeLocationType = true))
        awaitRunningType(true, "der zweite Start hat den Typ aktualisiert, ohne den Dienst zu beenden")

        ctx.startService(ObdForegroundService.stopIntent(ctx))
        awaitRunningType(null, "nach dem Stopp laeuft er nicht mehr")
    }

    @Test
    fun refreshServiceType_givesARunningServiceTheLocationTypeOnceGpsSpeedIsSwitchedOn() {
        ctx.startService(ObdForegroundService.startIntent(ctx, includeLocationType = false))
        awaitRunningType(false, "gestartet ohne Standort-Typ")
        kotlinx.coroutines.runBlocking { appPreferences.setUseGpsSpeed(true) }

        kotlinx.coroutines.runBlocking { serviceController.refreshServiceType() }

        awaitRunningType(true, "GPS-Geschwindigkeit an und Hintergrund-Standort erteilt: der laufende Dienst bekommt den Typ")
        ctx.startService(ObdForegroundService.stopIntent(ctx))
        awaitRunningType(null, "nach dem Stopp laeuft er nicht mehr")
    }
}
