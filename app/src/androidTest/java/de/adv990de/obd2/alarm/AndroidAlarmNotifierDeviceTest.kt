package de.adv990de.obd2.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import de.adv990de.obd2.ObdForegroundService
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.dtcDescriptionRes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die echte Systembenachrichtigung der Alarme ([AndroidAlarmNotifier]) am Gerät: was im Benachrichtigungsfeld wirklich ankommt. Der Zweig "Benachrichtigungen
 * gesperrt" steht nicht hier — die Berechtigung zur Laufzeit zu entziehen (`revokeRuntimePermission`) beendet den Testprozess; die Entscheidung dazu ist
 * `shouldPost` (JVM: `AndroidAlarmNotifierDecisionTest`).
 */
@RunWith(AndroidJUnit4::class)
class AndroidAlarmNotifierDeviceTest {

    // Ab Android 13 verwirft das System jede Benachrichtigung ohne diese Berechtigung. Sie wird erteilt, nie entzogen.
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)

    private lateinit var notifier: AndroidAlarmNotifier

    private val allIds = listOf(2001, 2002, 2003)

    @Before
    fun setUp() {
        // Den Kanal legt sonst Obd2Application an, die unter HiltTestApplication nie läuft — dieselbe Funktion wie in der Produktion.
        createAlarmNotificationChannel(ctx)
        // Direkt über das System geleert, nicht über clear(): das ist selbst Gegenstand eines Tests.
        allIds.forEach { manager.cancel(it) }
        notifier = AndroidAlarmNotifier(ctx)
    }

    @After
    fun tearDown() {
        allIds.forEach { manager.cancel(it) }
    }

    private fun active(id: Int): StatusBarNotification? =
        manager.activeNotifications.firstOrNull { it.id == id && it.packageName == ctx.packageName }

    /** Das System nimmt `notify` asynchron an: warten, bis die Benachrichtigung da ist (oder 5 s vergangen sind). */
    private fun awaitActive(id: Int, what: String, matches: (Notification) -> Boolean = { true }): Notification {
        val deadline = System.currentTimeMillis() + 5_000
        var found = active(id)?.notification
        while ((found == null || !matches(found)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            found = active(id)?.notification
        }
        assertNotNull("$what: keine Benachrichtigung mit der Id $id", found)
        assertTrue("$what: die Benachrichtigung mit der Id $id hat nicht den erwarteten Inhalt", matches(found!!))
        return found
    }

    private fun awaitGone(id: Int, what: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (active(id) != null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertNull("$what: die Benachrichtigung mit der Id $id steht noch", active(id))
    }

    private fun title(notification: Notification): String? = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun text(notification: Notification): String? = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    // --- Posten ---

    @Test
    fun showPostsTheMessageOnTheAlarmChannelUnderTheVoltageId() {
        val alarm = Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.1f)

        notifier.show(alarm)

        val posted = awaitActive(2001, "Spannungsalarm")
        assertEquals("obd2_alarms", posted.channelId)
        assertEquals(alarmMessage(ctx.resources, alarm), title(posted))
    }

    @Test
    fun eachKindHasItsOwnIdAndTheyStandSideBySide() {
        notifier.show(Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.1f))
        notifier.show(Alarm(AlarmKind.COOLANT, AlarmSeverity.NOTICE, value = 96f))
        notifier.show(Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301")))

        awaitActive(2001, "Spannung")
        awaitActive(2002, "Kühlmittel")
        awaitActive(2003, "Fehler")
    }

    @Test
    fun aNewAlarmOfTheSameKindReplacesTheOldOne() {
        notifier.show(Alarm(AlarmKind.VOLTAGE, AlarmSeverity.NOTICE, value = 12.4f))
        awaitActive(2001, "erster Alarm")
        val second = Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 11.9f)

        notifier.show(second)

        // Das Abräumen vor dem Posten (neuer Alarmton bei höherer Stufe) sieht kein Test: activeNotifications zeigt nach Abräumen+Posten und nach einem bloßen Update dasselbe,
        // und auch postTime wird bei beiden neu gesetzt (am Pixel gemessen, beide Varianten). Hier steht nur, dass der zweite Alarm den ersten ersetzt.
        awaitActive(2001, "zweiter Alarm") { title(it) == alarmMessage(ctx.resources, second) }
        assertEquals("genau eine Benachrichtigung der Art", 1, manager.activeNotifications.count { it.id == 2001 && it.packageName == ctx.packageName })
    }

    // --- Entfernen ---

    @Test
    fun clearRemovesTheNotificationOfThatKind() {
        notifier.show(Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.1f))
        awaitActive(2001, "vor clear")

        notifier.clear(AlarmKind.VOLTAGE)

        awaitGone(2001, "nach clear")
    }

    @Test
    fun clearLeavesTheOtherKindsAlone() {
        notifier.show(Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.1f))
        notifier.show(Alarm(AlarmKind.COOLANT, AlarmSeverity.WARNING, value = 101f))
        awaitActive(2001, "Spannung")
        awaitActive(2002, "Kühlmittel")

        notifier.clear(AlarmKind.VOLTAGE)

        awaitGone(2001, "Spannung nach clear")
        assertNotNull("clear(VOLTAGE) darf den Kühlmittel-Alarm nicht mitnehmen", active(2002))
    }

    @Test
    fun clearWithNothingPostedDoesNothing() {
        notifier.clear(AlarmKind.COOLANT)

        assertNull(active(2002))
    }

    // --- Fehler-Alarm: Beschreibung nur bei einem Code ---

    @Test
    fun aFaultAlarmWithOneCodeNamesTheDescription() {
        val alarm = Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301"))

        notifier.show(alarm)

        val posted = awaitActive(2003, "ein Fehlercode")
        assertEquals(alarmMessage(ctx.resources, alarm), title(posted))
        assertEquals(ctx.getString(dtcDescriptionRes("P0301")), text(posted))
    }

    @Test
    fun aFaultAlarmWithSeveralCodesHasNoDescription() {
        val alarm = Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301", "P0420"))

        notifier.show(alarm)

        val posted = awaitActive(2003, "mehrere Fehlercodes")
        assertEquals(alarmMessage(ctx.resources, alarm), title(posted))
        assertNull("bei mehreren Codes gäbe es nur eine Beschreibung für einen von ihnen", text(posted))
    }

    @Test
    fun aVoltageAlarmHasNoSecondLine() {
        notifier.show(Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.1f))

        assertNull(text(awaitActive(2001, "Spannungsalarm")))
    }

    // --- Kanal ---

    @Test
    fun theChannelIsHighImportanceWithVibration() {
        manager.deleteNotificationChannel("obd2_alarms")
        assertNull("Ausgangslage: kein Kanal", manager.getNotificationChannel("obd2_alarms"))

        createAlarmNotificationChannel(ctx)

        val channel = manager.getNotificationChannel("obd2_alarms")
        assertNotNull("der Notifier postet auf diesem Kanal — ohne ihn erscheint nichts", channel)
        assertEquals("ein Alarm muss sich bemerkbar machen (Heads-up)", NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue(channel.shouldVibrate())
        assertEquals(ctx.getString(R.string.notif_alarm_channel_name), channel.name.toString())
    }

    @Test
    fun creatingTheChannelAgainKeepsExactlyOne() {
        createAlarmNotificationChannel(ctx)
        createAlarmNotificationChannel(ctx)

        assertEquals(1, manager.notificationChannels.count { it.id == "obd2_alarms" })
    }

    // --- Verhalten der Benachrichtigung ---

    @Test
    fun theNotificationIsAStatusNotificationNotAnAlarmCategory() {
        notifier.show(Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.1f))

        // CATEGORY_ALARM könnte Nicht stören umgehen — das entscheidet der Nutzer, nicht die App.
        assertEquals(Notification.CATEGORY_STATUS, awaitActive(2001, "Spannungsalarm").category)
    }

    @Test
    fun theNotificationIsHighPriorityAndDisappearsWhenTapped() {
        notifier.show(Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.1f))

        val posted = awaitActive(2001, "Spannungsalarm")

        @Suppress("DEPRECATION") // Notification.priority ist das, was setPriority() schreibt; der Kanal gilt erst ab API 26 allein
        assertEquals(Notification.PRIORITY_HIGH, posted.priority)
        assertTrue("setAutoCancel(true): ein Antippen räumt die Benachrichtigung ab", posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    @SdkSuppress(minSdkVersion = 28) // Icon.getResId() gibt es erst ab API 28; die Bedingung steht an der Annotation (assumeTrue auf SDK_INT verbietet CodeHygieneSourceTest)
    fun theSmallIconIsTheWarningTriangleAndNotTheOneOfTheForegroundService() {
        notifier.show(Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.1f))

        // Entscheidung des Nutzers (2026-10-18): ein Warndreieck. Das Bluetooth-Symbol gehoert dem Vordergrunddienst ("Fahrzeugdaten werden aufgezeichnet") —
        // in der Statusleiste stuenden beide sonst nebeneinander und waeren nicht zu unterscheiden.
        val icon = awaitActive(2001, "Spannungsalarm").smallIcon
        assertEquals(R.drawable.ic_alarm_warning, icon.resId)
        assertNotEquals(R.drawable.ic_bluetooth, icon.resId)
    }

    @Test
    fun tappingTheNotificationOpensTheApp() {
        notifier.show(Alarm(AlarmKind.COOLANT, AlarmSeverity.WARNING, value = 101f))

        val posted = awaitActive(2002, "Kühlmittelalarm")

        // Dieselbe PendingIntent wie die des Dienstes: FLAG_NO_CREATE legt keine an, sondern liefert die bestehende (oder null) — gleich ist sie nur, wenn
        // Intent (Komponente) und Anfragecode übereinstimmen.
        val openApp = PendingIntent.getActivity(ctx, 0, ObdForegroundService.openAppIntent(ctx), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull("es gibt keine PendingIntent für openAppIntent", openApp)
        assertNotNull("ohne contentIntent tat ein Antippen nichts", posted.contentIntent)
        assertEquals(openApp, posted.contentIntent)
    }
}
