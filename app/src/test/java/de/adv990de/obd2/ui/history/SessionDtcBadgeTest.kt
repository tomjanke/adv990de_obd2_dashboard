package de.adv990de.obd2.ui.history

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.ui.settings.logLevelChipColors
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme
import de.adv990de.obd2.ui.theme.greenColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Was die Zeile einer Fahrt von ihren Fehlercodes zeigt. Zwei Aussagen: WANN es ein Zeichen gibt (nur bei Codes — "nicht erfasst" und
 * "keine gefunden" bleiben ohne) und WELCHE Farbe es hat. Die Farben sind bewusst die der Log-Chips mit derselben Bedeutung (ERROR rot,
 * WARN gelb): deren Kontrast und Farbton sind in `LogLevelColorsTest` fuer alle Schemata belegt, hier wird nur die Zuordnung gehalten.
 */
class SessionDtcBadgeTest {

    private fun dtc(code: String, pending: Boolean = false) = DtcEntry(code, pending)

    private val schemes: Map<String, ColorScheme> = mapOf(
        "blue" to blueColorScheme, "amber" to amberColorScheme, "green" to greenColorScheme,
        "default-light" to lightColorScheme(), "default-dark" to darkColorScheme(),
    )

    @Test
    fun `a trip that was not recorded or found no codes has no badge`() {
        assertNull("nicht erfasst", sessionDtcBadge(null))
        assertNull("gelesen, nichts gefunden", sessionDtcBadge(emptyList()))
    }

    @Test
    fun `the badge counts every code, pending ones too`() {
        assertEquals(SessionDtcBadge(count = 2, hasConfirmed = true), sessionDtcBadge(listOf(dtc("P0301"), dtc("P0420", pending = true))))
        assertEquals(SessionDtcBadge(count = 1, hasConfirmed = true), sessionDtcBadge(listOf(dtc("P0301"))))
    }

    @Test
    fun `a trip with only pending codes has no confirmed fault`() {
        assertEquals(SessionDtcBadge(count = 2, hasConfirmed = false), sessionDtcBadge(listOf(dtc("P0171", true), dtc("P0420", true))))
    }

    @Test
    fun `a confirmed code makes the badge red - the colors of an ERROR log chip, in every scheme`() {
        val badge = SessionDtcBadge(count = 1, hasConfirmed = true)
        for ((name, s) in schemes) {
            val error = logLevelChipColors(LogLevel.ERROR, s)
            assertEquals(name, error.container, sessionDtcChipColors(badge, s).container)
            assertEquals(name, error.content, sessionDtcChipColors(badge, s).content)
        }
    }

    @Test
    fun `only pending codes make the badge yellow - the colors of a WARN log chip, in every scheme`() {
        val badge = SessionDtcBadge(count = 3, hasConfirmed = false)
        for ((name, s) in schemes) {
            val warn = logLevelChipColors(LogLevel.WARN, s)
            assertEquals(name, warn.container, sessionDtcChipColors(badge, s).container)
            assertEquals(name, warn.content, sessionDtcChipColors(badge, s).content)
        }
    }

    @Test
    fun `the two badge colors can be told apart in every scheme`() {
        for ((name, s) in schemes) {
            assertNotEquals(
                name,
                sessionDtcChipColors(SessionDtcBadge(1, hasConfirmed = true), s).container,
                sessionDtcChipColors(SessionDtcBadge(1, hasConfirmed = false), s).container,
            )
        }
    }
}
