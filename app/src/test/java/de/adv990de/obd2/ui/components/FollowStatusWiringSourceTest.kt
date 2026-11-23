package de.adv990de.obd2.ui.components

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Statuszeile des Nachfahrens muss vom ViewModel bis in die Mitte der Zeile durchgereicht werden (der Rahmen mit Hilt ist im Geraete-Test nur mit Attrappen zu bauen; der Lehre aus 1.49.0
 * folgend liest dieser Test den Quelltext): `BottomNav` sammelt sie **nur im Zweig der Zeile** (das Sammeln startet die Ortung) und reicht sie an `AmbientStatusRow` weiter.
 */
class FollowStatusWiringSourceTest {
    private fun source(path: String) = File(projectRoot, "app/src/main/java/de/adv990de/obd2/$path").readText()

    @Test
    fun `the bottom nav collects the status only in the branch that shows the row`() {
        val s = source("ui/components/BottomNav.kt")
        assertEquals(1, Regex("""followVm\.status\.collectAsStateWithLifecycle\(\)""").findAll(s).count())
        val hiddenBranch = s.substringAfter("if (hidden) {").substringBefore("} else {")
        assertTrue("der Status wird nicht im Zweig der Zeile gesammelt", hiddenBranch.contains("followVm.status.collectAsStateWithLifecycle()"))
    }

    @Test
    fun `the bottom nav hands the status to the row`() {
        val s = source("ui/components/BottomNav.kt")
        assertTrue(Regex("""AmbientStatusRow\([^\n]*followStatus = followStatus\)""").containsMatchIn(s))
    }

    @Test
    fun `the view model takes the status from the position of the session`() {
        val s = source("ui/tracks/FollowStatusViewModel.kt")
        assertTrue(s.contains("session.reading.map { tracker.update(it) }"))
        assertTrue("ein neuer Track beginnt mit einem neuen Tracker", s.contains("val tracker = FollowStatusTracker()"))
    }

    @Test
    fun `the row decides the middle text by its own width`() {
        val s = source("ui/components/AmbientStatusRow.kt")
        assertTrue(s.contains("maxWidth >= FOLLOW_STATUS_MIN_ROW_WIDTH"))
    }

    @Test
    fun `the status never reaches a log`() {
        // Positionen stehen in keinem Log (Datenschutzerklaerung): weder die Statuszeile noch ihre Rechnung ruft das App-Log oder Log auf.
        for (path in listOf("track/FollowStatus.kt", "ui/tracks/FollowStatusViewModel.kt", "ui/components/AmbientStatusRow.kt")) {
            val s = source(path)
            assertTrue("$path loggt", !Regex("""\bLog\.[a-z]|\bappLog\b|AppLogRepository|Timber""").containsMatchIn(s))
        }
    }
}
