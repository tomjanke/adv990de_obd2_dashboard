package de.adv990de.obd2.alarm

import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Die Hilt-Bindungen des `AlarmModule`: Nichts verbraucht [AlarmNotifier] und [AlarmLog] vor dem `AlarmController` (nächste Aufgabe) — ohne diesen Test liefe eine falsche
 * oder fehlende Bindung erst dort auf. Die Datenbank ist die des Tests (`TestAppModule`, im Speicher).
 */
@HiltAndroidTest
class AlarmModuleDeviceTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    // Wie in jedem Hilt-Test: das echte ObdRepository bräuchte die Bluetooth-Bindungen aus dem ersetzten `AppModule`.
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    @Inject lateinit var notifier: AlarmNotifier

    @Inject lateinit var alarmLog: AlarmLog

    @Inject lateinit var appLog: AppLogRepository

    @Before fun setUp() = hiltRule.inject()

    @Test
    fun theNotifierIsTheAndroidImplementation() {
        assertTrue("AlarmNotifier ist ${notifier::class.java.name}", notifier is AndroidAlarmNotifier)
    }

    @Test
    fun theAlarmLogWritesToTheAppLog() = runBlocking {
        val message = "AlarmModuleDeviceTest ${System.nanoTime()}"

        alarmLog.log(LogLevel.WARN, message)

        val written = appLog.logs.first().filter { it.message == message }
        assertEquals("eine Zeile mit genau dieser Meldung im App-Log", 1, written.size)
        assertEquals(LogLevel.WARN.name, written.single().level)
    }
}
