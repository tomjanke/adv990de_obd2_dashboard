package de.adv990de.obd2.ui.transfer

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Der Rahmen der Seite „Daten sichern und wiederherstellen“ (Dateidialoge, Merker fuer den sofortigen Dateidialog) steht in `DataTransferScreen` und braucht Systemdialoge, die ein
 * Geraete-Test nicht bedienen kann. Wie bei [TransferShortcutsSourceTest] haelt dieser Quelltext-Test die Verdrahtung fest, die sonst niemand prueft: ein System ohne Dateidialog darf die
 * App nicht beenden, und eine Drehung darf den Dateidialog des Imports nicht ein zweites Mal oeffnen.
 */
class DataTransferScreenSourceTest {

    private val text = File(projectRoot, "app/src/main/java/de/adv990de/obd2/ui/transfer/DataTransferScreen.kt").readText().replace(Regex("\\s+"), " ")

    @Test
    fun `every file dialog is started inside safely so a system without one only shows a message`() {
        val launches = Regex("""\w+Launcher\.launch\(""").findAll(text).toList()
        assertEquals("drei Starts: Paket, CSV und Import (zweimal: Abkuerzung und Knopf)", 4, launches.size)
        for (launch in launches) {
            val before = text.substring(0, launch.range.first)
            val start = before.lastIndexOf("safely {")
            assertTrue("${launch.value} steht nicht in safely { … }", start >= 0)
            val open = before.substring(start).count { it == '{' } - before.substring(start).count { it == '}' }
            assertTrue("${launch.value} steht ausserhalb des Blocks von safely", open > 0)
        }
    }

    @Test
    fun `safely catches exactly the missing file dialog and says so`() {
        val safely = text.substringAfter("fun safely(").substringBefore("// „Speichern“")
        assertTrue(safely.contains("catch (_: ActivityNotFoundException)"))
        assertTrue(safely.contains("snackbarHostState.showSnackbar(saveUnavailableMessage)"))
    }

    @Test
    fun `the pick flag survives a rotation and is set before the file dialog starts`() {
        assertTrue("der Merker ueberlebt eine Drehung", text.contains("var pickHandled by rememberSaveable { mutableStateOf(false) }"))
        val effect = text.substringAfter("LaunchedEffect(pick) {").substringBefore("ExportFeedbackEffects(")
        assertTrue("der Dialog oeffnet nur mit pick und nur einmal", effect.contains("if (pick && !pickHandled)"))
        val flagAt = effect.indexOf("pickHandled = true")
        val launchAt = effect.indexOf("importLauncher.launch(")
        assertTrue("beide stehen im Effekt", flagAt >= 0 && launchAt >= 0)
        assertTrue("der Merker wird vor dem Start gesetzt (ein Abbruch oder eine Drehung mittendrin oeffnet nichts neu)", flagAt < launchAt)
    }
}
