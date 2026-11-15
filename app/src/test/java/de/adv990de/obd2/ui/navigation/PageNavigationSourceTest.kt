package de.adv990de.obd2.ui.navigation

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Der Doppeltipp beim Navigieren (QS 2026-11-14): eine Seite bleibt waehrend der Ueberblendung (700 ms) beruehrbar. Jede Unterseite wird deshalb ueber [openSubPage] bzw.
 * `openDataTransfer` geoeffnet (`launchSingleTop`) und jede Seite ueber `closePage` / `rememberClosePage` geschlossen (nur solange sie oben liegt). Das Verhalten selbst pruefen
 * die Geraete-Tests (`SettingsScreenDeviceTest`, `LicensesScreenTest`); dieser Quelltext-Test haelt fest, dass keine Seite an den Helfern vorbei navigiert — eine neue Seite mit
 * einem nackten `navigate(…)` oder `popBackStack()` fiele sonst erst am Geraet auf.
 */
class PageNavigationSourceTest {

    private val uiRoot = File(projectRoot, "app/src/main/java/de/adv990de/obd2/ui")

    private fun sources(): List<File> = uiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun relative(file: File) = file.relativeTo(uiRoot).path

    /** Quelltext ohne Kommentare: ein `popBackStack()` in einer Erklaerung ist kein Aufruf. */
    private fun code(file: File): String = file.readText()
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `no page pops the back stack on its own`() {
        val bare = sources().filter { Regex("""\bpopBackStack\(\s*\)""").containsMatchIn(code(it)) }.map(::relative)
        assertEquals("ein nacktes popBackStack() geht bei einem zweiten Tipp zwei Ebenen zurueck: rememberClosePage nehmen", listOf("navigation/PageNavigation.kt"), bare)
    }

    @Test
    fun `only the helpers and the tab switches call navigate`() {
        val callers = sources().filter { Regex("""\.navigate\(|\bnavigate\(route\)""").containsMatchIn(code(it)) }.map(::relative).sorted()
        assertEquals(
            "eine Unterseite wird ueber openSubPage (oder openDataTransfer) geoeffnet; nur die Reiter (BottomNav, das Zahnrad des Dashboards) navigieren selbst",
            listOf("components/BottomNav.kt", "dashboard/DashboardScreen.kt", "navigation/DataTransferNavigation.kt", "navigation/PageNavigation.kt"),
            callers,
        )
    }

    @Test
    fun `opening a sub page launches single top and closing checks the page on top`() {
        val helper = code(File(uiRoot, "navigation/PageNavigation.kt")).replace(Regex("\\s+"), " ")
        assertTrue("ein zweiter Tipp legt keinen zweiten Eintrag an", helper.contains("navigate(route) { launchSingleTop = true }"))
        assertTrue("geschlossen wird nur die Seite, die oben liegt", helper.contains("if (currentBackStackEntry?.id != entry.id) return false"))
    }

    @Test
    fun `the pages that leave on their own use the guarded close too`() {
        for (path in listOf("logbook/LogEntryScreen.kt", "logbook/LogbookStatsScreen.kt")) {
            val text = code(File(uiRoot, path))
            assertTrue("$path merkt sich das Schliessen der eigenen Seite", text.contains("val closePage = rememberClosePage(navController)"))
            assertTrue("$path verlaesst die Seite ueber closePage()", Regex("""LaunchedEffect\(\w+\) \{ if \([^)]*\) closePage\(\) \}""").containsMatchIn(text))
        }
    }
}
