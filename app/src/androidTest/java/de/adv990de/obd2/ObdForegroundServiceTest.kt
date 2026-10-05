package de.adv990de.obd2

import android.Manifest
import android.app.NotificationChannel
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
import io.mockk.mockk
import org.junit.After
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
class ObdForegroundServiceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    /**
     * Ein Foreground-Service vom Typ `connectedDevice` verlangt neben
     * FOREGROUND_SERVICE_CONNECTED_DEVICE mindestens eine der Bluetooth-Berechtigungen —
     * sonst wirft `startForeground()` eine SecurityException.
     */
    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    )

    /**
     * Ersetzt die @Singleton-Bindung fuer diese Testklasse. Hilt injiziert den Mock direkt in
     * den Service, ohne die echte Bluetooth-Infrastruktur aufzubauen.
     */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        coJustRun { disconnectByUser() }
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    // Ab API 26 darf ein Prozess im Hintergrund kein startService() aufrufen. Die Activity
    // holt den Testprozess in den Vordergrund und macht den Aufruf damit zulaessig.
    private lateinit var scenario: ActivityScenario<HiltTestActivity>

    @Before
    fun setup() {
        hiltRule.inject()
        // Den Channel legt sonst Obd2Application an, die unter HiltTestApplication nie laeuft.
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                ObdForegroundService.CHANNEL_ID,
                "OBD2 test channel",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        scenario = ActivityScenario.launch(HiltTestActivity::class.java)
    }

    @After
    fun teardown() {
        scenario.close()
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
}
