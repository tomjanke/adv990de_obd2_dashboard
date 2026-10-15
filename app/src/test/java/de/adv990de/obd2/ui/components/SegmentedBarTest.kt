package de.adv990de.obd2.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Segmentgeometrie, die Drehzahlbalken und Tankanzeige teilen (Nutzerwunsch 2026-10-15: der Abstand der Tank-Segmente entspricht dem der
 * Drehzahl-Segmente). Die Breite eines Segments ist der Rest der Breite nach Abzug aller Luecken, gleichmaessig geteilt — das letzte Segment
 * endet damit genau am rechten Rand.
 */
class SegmentedBarTest {

    @Test
    fun `the gap between segments is 1,5 dp - the value the rpm bar always had`() {
        assertEquals(1.5.dp, SEGMENT_GAP)
    }

    @Test
    fun `segments and gaps add up to exactly the full width`() {
        for ((width, gap, count) in listOf(Triple(941f, 3.375f, 10), Triple(1000f, 3f, 50), Triple(392f, 3.375f, 10))) {
            val segment = segmentWidthPx(width, gap, count)

            assertEquals("$width px, $count Segmente", width, segment * count + gap * (count - 1), 0.001f)
        }
    }

    @Test
    fun `without a gap the segments share the width equally`() {
        assertEquals(100f, segmentWidthPx(1000f, 0f, 10), 0f)
    }

    @Test
    fun `a bar too narrow for its gaps keeps segments of at least one pixel`() {
        assertTrue(segmentWidthPx(5f, 3.375f, 10) >= 1f)
        assertEquals(1f, segmentWidthPx(0f, 3.375f, 10), 0f)
    }

    @Test
    fun `a single segment has no gap at all`() {
        assertEquals(200f, segmentWidthPx(200f, 3.375f, 1), 0f)
    }
}
