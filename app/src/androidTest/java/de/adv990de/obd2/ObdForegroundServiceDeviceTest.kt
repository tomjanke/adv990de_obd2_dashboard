package de.adv990de.obd2

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.track.RecordingState
import de.adv990de.obd2.track.StartResult
import de.adv990de.obd2.track.TrackRecorder
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

    @javax.inject.Inject lateinit var trackRecorder: TrackRecorder

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
        // Ein Dienst, der nach einem Test weiterlaeuft, haelt die Objekte dieses Tests: der naechste Test trafe ihn mit seinen eigenen, neuen Attrappen nicht mehr.
        // Der Controller (und damit sein Bedarf) gehoert zu diesem Test, der Dienst wird deshalb hart beendet und abgewartet.
        ctx.stopService(Intent(ctx, ObdForegroundService::class.java))
        val deadline = System.currentTimeMillis() + 5_000
        while (ObdForegroundService.runningIncludesLocation != null && System.currentTimeMillis() < deadline) Thread.sleep(50)
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
        kotlinx.coroutines.runBlocking { serviceController.setObd(true) }
        awaitRunningType(false, "gestartet ohne Standort-Typ")
        kotlinx.coroutines.runBlocking { appPreferences.setUseGpsSpeed(true) }

        kotlinx.coroutines.runBlocking { serviceController.refreshServiceType() }

        awaitRunningType(true, "GPS-Geschwindigkeit an und ein Standort erteilt: der laufende Dienst bekommt den Typ")
        kotlinx.coroutines.runBlocking { serviceController.stop() }
        awaitRunningType(null, "nach dem Stopp laeuft er nicht mehr")
    }

    // --- F8: zwei Bedarfe, ein Dienst ---

    /** Der Dienst-Typ, wie ihn das System fuer den laufenden Dienst fuehrt (`dumpsys activity services`), oder `null`, wenn der Dienst nicht als Vordergrunddienst laeuft. */
    private fun systemServiceType(): Int? {
        val out = shell("dumpsys activity services ${ctx.packageName}/de.adv990de.obd2.ObdForegroundService")
        val record = out.substringAfter("ServiceRecord{", "")
        // Die Zeile des Systems: `isForeground=true foregroundId=1 types=0x00000008` — nur ein laufender Vordergrunddienst hat sie.
        val match = Regex("""isForeground=true[^\n]*? types=0x([0-9a-fA-F]+)""").find(record) ?: return null
        return match.groupValues[1].toLong(16).toInt()
    }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun awaitSystemType(expected: Int?, what: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (systemServiceType() != expected && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val lines = shell("dumpsys activity services ${ctx.packageName}/de.adv990de.obd2.ObdForegroundService").lines()
            .filter { Regex("foreground|fgs|types", RegexOption.IGNORE_CASE).containsMatchIn(it) && !it.contains("Allow") }
        assertEquals("$what\n${lines.joinToString("\n")}", expected, systemServiceType())
    }

    private fun serviceNotification(): android.app.Notification? =
        ctx.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.notification.channelId == ObdForegroundService.CHANNEL_ID }?.notification

    private fun awaitNotification(titleRes: Int, what: String): android.app.Notification {
        val deadline = System.currentTimeMillis() + 5_000
        var n = serviceNotification()
        while ((n == null || n.extras.getString(android.app.Notification.EXTRA_TITLE) != ctx.getString(titleRes)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            n = serviceNotification()
        }
        assertEquals(what, ctx.getString(titleRes), n?.extras?.getString(android.app.Notification.EXTRA_TITLE))
        return n!!
    }

    private fun actionTitles(n: android.app.Notification): List<String> = n.actions.orEmpty().map { it.title.toString() }

    @Test
    fun theDemandTravelsInTheStartIntent() {
        val both = ObdForegroundService.startIntent(ctx, ForegroundDemand(obd = true, track = true), includeLocationType = true)
        assertTrue(both.getBooleanExtra(ObdForegroundService.EXTRA_OBD, false))
        assertTrue(both.getBooleanExtra(ObdForegroundService.EXTRA_TRACK, false))
        assertTrue(both.getBooleanExtra(ObdForegroundService.EXTRA_INCLUDE_LOCATION, false))

        val trackOnly = ObdForegroundService.startIntent(ctx, ForegroundDemand(obd = false, track = true))
        assertEquals(false, trackOnly.getBooleanExtra(ObdForegroundService.EXTRA_OBD, true))
        assertTrue(trackOnly.getBooleanExtra(ObdForegroundService.EXTRA_TRACK, false))
        assertEquals(ObdForegroundService.ACTION_START, trackOnly.action)
        assertEquals(ObdForegroundService.ACTION_STOP_TRACK, ObdForegroundService.stopTrackIntent(ctx).action)
    }

    @Test
    fun theTrackAloneRunsWithTheLocationTypeOnly_andOffersToEndTheTrack() {
        kotlinx.coroutines.runBlocking { serviceController.setTrack(true) }

        awaitRunningType(true, "nur der Track: der Typ enthaelt `location`")
        awaitSystemType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, "nur `location`, kein Bluetooth-Typ")
        val n = awaitNotification(R.string.notif_track_title, "die Benachrichtigung des Tracks")
        assertEquals(listOf(ctx.getString(R.string.notif_track_stop)), actionTitles(n))

        kotlinx.coroutines.runBlocking { serviceController.setTrack(false) }
        awaitRunningType(null, "ohne Bedarf laeuft er nicht mehr")
    }

    @Test
    fun bothDemandsShareOneService_andEachOneLeavesTheOtherRunning() {
        kotlinx.coroutines.runBlocking { serviceController.setTrack(true) }
        awaitSystemType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, "erst nur der Track")

        kotlinx.coroutines.runBlocking { serviceController.setObd(true) }
        val both = awaitNotification(R.string.notif_both_title, "beide: eine gemeinsame Benachrichtigung")
        assertEquals(listOf(ctx.getString(R.string.notif_obd_disconnect), ctx.getString(R.string.notif_track_stop)), actionTitles(both))
        awaitSystemType(
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            "beide Typen",
        )

        // Das Trennen des Adapters (auch Wegwischen und „Beenden“ gehen diesen Weg) beendet die Aufzeichnung nicht.
        kotlinx.coroutines.runBlocking { serviceController.endSession() }
        awaitNotification(R.string.notif_track_title, "nach dem Trennen bleibt die Track-Benachrichtigung")
        awaitSystemType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, "der Dienst laeuft mit `location` weiter")

        kotlinx.coroutines.runBlocking { serviceController.setObd(true) }
        awaitNotification(R.string.notif_both_title, "wieder beide")
        kotlinx.coroutines.runBlocking { serviceController.setTrack(false) }
        awaitNotification(R.string.notif_obd_title, "nach dem Track-Ende bleibt die OBD-Benachrichtigung")
        awaitRunningType(false, "und der Dienst laeuft ohne `location` (GPS-Geschwindigkeit ist aus)")

        kotlinx.coroutines.runBlocking { serviceController.endSession() }
        awaitRunningType(null, "erst mit dem letzten Bedarf stoppt er")
    }

    @Test
    fun theStopActionOfTheNotificationDisconnectsButKeepsTheRecording() {
        kotlinx.coroutines.runBlocking {
            serviceController.setObd(true)
            serviceController.setTrack(true)
        }
        awaitNotification(R.string.notif_both_title, "beide laufen")

        ctx.startService(ObdForegroundService.stopIntent(ctx))

        coVerify(timeout = 5_000) { obdRepository.disconnectByUser() }
        awaitNotification(R.string.notif_track_title, "„Trennen“ laesst die Aufzeichnung stehen")
        awaitSystemType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, "der Dienst laeuft weiter")
        kotlinx.coroutines.runBlocking { serviceController.setTrack(false) }
        awaitRunningType(null, "aufgeraeumt")
    }

    @Test
    fun theEndTrackActionStopsTheRealRecorder_andThenTheService() {
        val result = kotlinx.coroutines.runBlocking { trackRecorder.start("Geraetetest") }
        assertEquals(StartResult.Started, result)
        assertTrue(trackRecorder.state.value is RecordingState.Recording)
        awaitNotification(R.string.notif_track_title, "die Aufzeichnung haelt den Dienst")

        ctx.startService(ObdForegroundService.stopTrackIntent(ctx))

        val deadline = System.currentTimeMillis() + 5_000
        while (trackRecorder.state.value != RecordingState.Idle && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("„Track beenden“ beendet die Aufzeichnung", RecordingState.Idle, trackRecorder.state.value)
        awaitRunningType(null, "und mit ihr den Dienst, weil keiner mehr etwas will")
    }
}
