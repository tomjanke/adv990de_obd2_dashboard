package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Das Vorschau-Band sagt nicht nur "nicht verbunden", sondern waehrend einer Wiederverbindung auch, WELCHER Versuch laeuft
 * ("Verbindungsversuch 2/5"). `DashboardUiState.reconnectAttempt` wurde bis 1.24.11 gesetzt, aber von keiner Oberflaeche
 * gelesen. Die Entscheidung, welcher Text gilt, ist die reine Funktion [previewBannerLabel].
 */
class PreviewBannerLabelTest {

    @Test
    fun `without a running reconnect the band stays the plain preview label`() {
        assertEquals(BannerLabel(R.string.dashboard_preview_banner), previewBannerLabel(isReconnecting = false, attempt = 0, total = 5))
    }

    @Test
    fun `a running attempt shows its number and the total`() {
        for (attempt in 1..5) {
            assertEquals(
                BannerLabel(R.string.dashboard_preview_banner_attempt, listOf(attempt, 5)),
                previewBannerLabel(isReconnecting = true, attempt = attempt, total = 5),
            )
        }
    }

    @Test
    fun `the auto-connect at start has no attempt number - it says it is connecting`() {
        // ConnectionEvent.Attempting(0, ...): "Auto-Connect beim Start", noch kein nummerierter Versuch.
        assertEquals(BannerLabel(R.string.dashboard_preview_banner_connecting), previewBannerLabel(isReconnecting = true, attempt = 0, total = 5))
    }

    @Test
    fun `a leftover attempt number is ignored once the reconnect is over`() {
        // Nach Failed/Aborted/Connected setzt das ViewModel die Nummer zurueck; die Funktion verlaesst sich nicht darauf.
        assertEquals(BannerLabel(R.string.dashboard_preview_banner), previewBannerLabel(isReconnecting = false, attempt = 3, total = 5))
    }

    @Test
    fun `the total follows the coordinator, not a literal five`() {
        assertEquals(listOf(1, RECONNECT_ATTEMPT_COUNT), previewBannerLabel(true, 1, RECONNECT_ATTEMPT_COUNT).args)
    }

    // ── Strings: beide Sprachen, mit beiden Platzhaltern ───────────────────

    private val projectRoot: File = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile } ?: error("settings.gradle.kts nicht gefunden")

    private fun stringValue(folder: String, name: String): String {
        val xml = File(projectRoot, "app/src/main/res/$folder/strings.xml").readText()
        val match = Regex("""<string name="${Regex.escape(name)}"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)
        return requireNotNull(match) { "$name fehlt in $folder" }.groupValues[1]
    }

    @Test
    fun `the attempt label exists in both languages and takes the number and the total`() {
        for (folder in listOf("values", "values-de")) {
            val text = stringValue(folder, "dashboard_preview_banner_attempt")
            assertTrue("$folder: $text", text.contains("%1\$d") && text.contains("%2\$d"))
            assertTrue("$folder: der Text bleibt ein Vorschau-Band", text.isNotBlank())
            stringValue(folder, "dashboard_preview_banner_connecting")
        }
    }
}
