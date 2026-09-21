package de.adv990de.obd2

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.data.ObdRepository
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ObdForegroundService ist ein gestarteter, kein gebundener Service — [ObdForegroundService.onBind]
 * liefert null. Deshalb kommt hier bewusst keine `ServiceTestRule` zum Einsatz: die bindet immer
 * und wartet auf `onServiceConnected`, was mit einem Null-Binder nie eintritt.
 *
 * Abgedeckt sind die beiden Pfade ohne `startForeground()`. Der ACTION_START-Pfad bleibt
 * ausgespart: er braucht den Notification-Channel, den nur `Obd2Application` anlegt — in
 * Hilt-Tests laeuft aber `HiltTestApplication` — und ab Android 13 zusaetzlich POST_NOTIFICATIONS.
 * Ein Test dafuer wuerde mehr ueber die Testumgebung aussagen als ueber den Service.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ObdForegroundServiceTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    /**
     * Ersetzt die @Singleton-Bindung fuer diese Testklasse. Hilt injiziert den Mock direkt in
     * den Service, ohne die echte Bluetooth-Infrastruktur aufzubauen.
     */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        coJustRun { disconnect() }
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    // Ab API 26 darf ein Prozess im Hintergrund kein startService() aufrufen. Die Activity
    // holt den Testprozess in den Vordergrund und macht den Aufruf damit zulaessig.
    private lateinit var scenario: ActivityScenario<HiltTestActivity>

    @Before
    fun setup() {
        hiltRule.inject()
        scenario = ActivityScenario.launch(HiltTestActivity::class.java)
    }

    @After
    fun teardown() {
        scenario.close()
    }

    // --- Test 1 ---

    @Test
    fun actionStop_disconnectsTheRepository() {
        ctx.startService(ObdForegroundService.stopIntent(ctx))

        // Die Trennung laeuft in einer Coroutine auf Dispatchers.Main — coVerify wartet darauf,
        // statt den Test mit einem festen Thread.sleep zu bezahlen.
        coVerify(timeout = 5_000) { obdRepository.disconnect() }
    }

    // --- Test 2 ---

    @Test
    fun unknownAction_stopsWithoutDisconnecting() {
        ctx.startService(Intent(ctx, ObdForegroundService::class.java))

        // Der else-Zweig ruft nur stopSelf(). Kurz warten, damit ein faelschlicher
        // disconnect()-Aufruf ueberhaupt auflaufen koennte.
        Thread.sleep(500)
        coVerify(exactly = 0) { obdRepository.disconnect() }
    }
}
