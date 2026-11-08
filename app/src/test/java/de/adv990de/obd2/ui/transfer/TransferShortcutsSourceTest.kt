package de.adv990de.obd2.ui.transfer

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Export- und Import-Symbole in Fahrten, Logbuch und App-Logs sind Abkuerzungen auf die Seite „Daten sichern und wiederherstellen“ (Entscheidung D2): ihr Klick navigiert nur noch,
 * mit der richtigen Art und, beim Import, mit `pick = true`; die Freigabe der Export-Symbole bleibt, die Import-Symbole sind immer aktiv. Die Oberflaeche selbst pruefen die Geraete-Tests
 * (`HistoryScreenTest`, `LogbookScreenDeviceTest`, `AppLogsScreenDeviceTest`); weil sie ohne Telefon nicht laufen, haelt dieser Quelltext-Test dieselbe Verdrahtung auf dem JVM fest
 * — der Compose-Baum der Kopfleiste ist dort nicht lesbar, der Quelltext schon.
 */
class TransferShortcutsSourceTest {

    private fun source(path: String) = File(projectRoot, "app/src/main/java/de/adv990de/obd2/$path").readText().replace(Regex("\\s+"), " ")

    /** Der Aufruf `IconButton(…)`, in dem [marker] steht (das Symbol oder seine Beschreibung): von seinem Anfang bis zum Anfang des naechsten. */
    private fun buttonWith(text: String, marker: String): String {
        val calls = text.split("IconButton(").drop(1)
        val hits = calls.filter { it.contains(marker) }
        assertTrue("genau ein IconButton mit $marker, gefunden ${hits.size}", hits.size == 1)
        return hits.single()
    }

    private val history = source("ui/history/HistoryScreen.kt")
    private val logbook = source("ui/logbook/LogbookScreen.kt")
    private val appLogs = source("ui/settings/AppLogsScreen.kt")

    @Test
    fun `the trip export shortcut opens the page with the trips and is enabled like delete all`() {
        val button = buttonWith(history, "R.string.history_cd_export_all")
        assertTrue(button.contains("Screen.DataTransfer.routeFor(TransferMode.EXPORT, DataPart.TRIPS)"))
        assertTrue(button.contains("enabled = canExportAll"))
    }

    @Test
    fun `the trip import shortcut asks for the file at once and is always enabled`() {
        val button = buttonWith(history, "R.string.history_cd_import")
        assertTrue(button.contains("Screen.DataTransfer.routeFor(TransferMode.IMPORT, pick = true)"))
        assertFalse("der Import-Knopf hat keine Freigabe-Bedingung", button.substringBefore("Icon(").contains("enabled"))
    }

    @Test
    fun `the logbook export shortcut opens the page with the logbook and needs an entry`() {
        val button = buttonWith(logbook, "R.string.logbook_cd_export")
        assertTrue(button.contains("Screen.DataTransfer.routeFor(TransferMode.EXPORT, DataPart.LOGBOOK)"))
        assertTrue(button.contains("enabled = hasEntries"))
    }

    @Test
    fun `the logbook import shortcut asks for the file at once and is always enabled`() {
        val button = buttonWith(logbook, "R.string.logbook_cd_import")
        assertTrue(button.contains("Screen.DataTransfer.routeFor(TransferMode.IMPORT, pick = true)"))
        assertFalse(button.substringBefore("Icon(").contains("enabled"))
    }

    @Test
    fun `the app log export shortcut opens the page with the app log and needs visible lines`() {
        val button = buttonWith(appLogs, "R.string.app_logs_export")
        assertTrue(button.contains("Screen.DataTransfer.routeFor(TransferMode.EXPORT, DataPart.APP_LOG)"))
        assertTrue("sichtbare Zeilen, nicht die der Datenbank: ein Loeschen im Rueckgaengig-Fenster macht den Knopf aus", button.contains("enabled = logs.isNotEmpty()"))
    }

    @Test
    fun `the lists no longer carry file dialogs or export state of their own`() {
        for ((name, text) in listOf("HistoryScreen" to history, "LogbookScreen" to logbook, "AppLogsScreen" to appLogs)) {
            assertFalse("$name startet keinen Dateidialog mehr", text.contains("rememberLauncherForActivityResult"))
            assertFalse("$name hat keinen Export-Dialog mehr", Regex("Export\\w*Dialog\\(|Import\\w*Dialog\\(").containsMatchIn(text))
        }
        assertFalse(logbook.contains("ExportFeedbackEffects("))
        assertFalse(appLogs.contains("ExportFeedbackEffects("))
    }

    @Test
    fun `the trip list still shares one trip from its row and shows the feedback of that`() {
        assertTrue(history.contains("onExport = { vm.export(session.id) }"))
        assertTrue(history.contains("ExportFeedbackEffects( feedback = vm.exportFeedback,"))
        assertTrue("nach dem Umbau gibt es kein „gespeichert“ mehr in der Liste", history.contains("savedMessage = null"))
    }

    @Test
    fun `every way into the transfer page goes through one helper that launches single top`() {
        val settings = source("ui/settings/SettingsSections.kt")
        for ((name, text) in listOf("HistoryScreen" to history, "LogbookScreen" to logbook, "AppLogsScreen" to appLogs, "SettingsSections" to settings)) {
            assertTrue("$name oeffnet die Seite ueber openDataTransfer", text.contains("openDataTransfer(Screen.DataTransfer.routeFor("))
            assertFalse("$name navigiert nicht selbst zur Seite", text.contains("navigate(Screen.DataTransfer"))
        }
        val helper = source("ui/navigation/DataTransferNavigation.kt")
        assertTrue("ein zweiter Tipp legt keinen zweiten Eintrag an", helper.contains("navigate(route) { launchSingleTop = true }"))
    }
}
