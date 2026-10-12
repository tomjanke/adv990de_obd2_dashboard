package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regeln, die sich nur am Quelltext pruefen lassen, weil das Framework sie nicht erzwingt (QS C: Touch-Ziele).
 *
 *  - Die App laeuft in [de.adv990de.obd2.ui.theme.AppTheme], nicht in einem nackten `MaterialTheme`: Nur dort steht die
 *    56-dp-Regel fuer Touch-Ziele.
 *  - Fenster-Komponenten (`AlertDialog`, `ModalBottomSheet`, Menues, `Popup`) haben ein eigenes Compose-Root mit der
 *    `ViewConfiguration` des Systems — die Regel aus dem Theme gilt darin NICHT (gemessen: 48 dp im Exit-Dialog). Jede
 *    Datei, die so eine Komponente nutzt, ruft deshalb `ProvideMinTouchTarget` in deren Slots auf.
 */
class UiRulesSourceTest {

    private val mainSources: List<File> = File(projectRoot, "app/src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    private val windowComponents = Regex("""(?<![A-Za-z])(AlertDialog|BasicAlertDialog|ModalBottomSheet|DropdownMenu|ExposedDropdownMenu|Popup|Dialog)\(""")

    @Test
    fun `MainActivity wraps the app in AppTheme, not in a bare MaterialTheme`() {
        val source = File(projectRoot, "app/src/main/java/de/adv990de/obd2/MainActivity.kt").readText()

        assertTrue("MainActivity setzt AppTheme(...)", source.contains("AppTheme("))
        assertTrue("kein nacktes MaterialTheme( in der Activity", !Regex("""(?<![A-Za-z])MaterialTheme\(""").containsMatchIn(source))
    }

    @Test
    fun `every file that opens a window component sets the touch target rule inside it`() {
        val offenders = mainSources
            .filter { f ->
                val text = f.readText()
                // Vorschauen (@Preview) rufen die Komponenten ihrer eigenen Datei auf; massgeblich ist der Produktivcode.
                windowComponents.containsMatchIn(text.substringBefore("@Preview")) && !text.contains("ProvideMinTouchTarget")
            }
            .map { it.relativeTo(projectRoot).path }

        assertTrue(
            "Diese Dateien oeffnen ein Fenster (Dialog/Sheet/Menue), ohne ProvideMinTouchTarget in den Slots — dort gilt " +
                "die 56-dp-Regel sonst nicht: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the guard itself finds the known window components`() {
        val files = mainSources.filter { windowComponents.containsMatchIn(it.readText().substringBefore("@Preview")) }.map { it.name }

        assertTrue("BottomNav (AlertDialog) gefunden: $files", "BottomNav.kt" in files)
        assertTrue("ConfirmDeleteDialog gefunden: $files", "ConfirmDeleteDialog.kt" in files)
        assertTrue("DashboardComponents (ModalBottomSheet) gefunden: $files", "DashboardComponents.kt" in files)
    }

    @Test
    fun `MainActivity seeds the theme before the first composition - no flash of the default theme (UID-12)`() {
        val source = File(projectRoot, "app/src/main/java/de/adv990de/obd2/MainActivity.kt").readText()

        assertTrue("seedBlocking vor setContent", source.indexOf("seedBlocking(") in 0 until source.indexOf("setContent {"))
        assertTrue("der Startwert geht in collectAsState", source.contains("collectAsState(initial = initialTheme)"))
    }

    @Test
    fun `the dashboard seeds its slot assignment instead of starting from empty or default tiles`() {
        val source = File(projectRoot, "app/src/main/java/de/adv990de/obd2/ui/dashboard/DashboardViewModel.kt").readText()

        assertTrue(source.contains("seedBlocking(AppPreferences.DEFAULT_SLOT_PIDS"))
        assertTrue(source.contains("placeholdersFor(initialSlotPids)"))
        assertTrue(source.contains("SharingStarted.Eagerly, initialSlotPids"))
    }
}

