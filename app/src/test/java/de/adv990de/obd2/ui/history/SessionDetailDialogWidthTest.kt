package de.adv990de.obd2.ui.history

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Die Mindestbreite des Dialoginhalts ([detailContentMinWidth]): im Querformat (Fenster rund 1000 dp) die festen 400 dp, in einem schmalen
 * Fenster der verfuegbare Platz abzueglich der Raender (je 24 dp Fensterrand und 24 dp Dialogpolster) — nie negativ, nie breiter als das Fenster.
 */
class SessionDetailDialogWidthTest {

    @Test
    fun `a wide window gets the fixed minimum width`() {
        // Pixel 8 Pro im Querformat: 997 dp breit.
        assertEquals(400.dp, detailContentMinWidth(997.dp))
    }

    @Test
    fun `the minimum width is reached exactly where the window leaves room for it`() {
        assertEquals(400.dp, detailContentMinWidth(496.dp))
        assertEquals(399.dp, detailContentMinWidth(495.dp))
    }

    @Test
    fun `a narrow window gets the available width minus the margins`() {
        // Ein 360-dp-Telefon im Hochformat: 360 - 96 = 264 dp.
        assertEquals(264.dp, detailContentMinWidth(360.dp))
    }

    @Test
    fun `a window narrower than the margins never yields a negative width`() {
        assertEquals(0.dp, detailContentMinWidth(96.dp))
        assertEquals(0.dp, detailContentMinWidth(40.dp))
        assertEquals(0.dp, detailContentMinWidth(0.dp))
    }
}
