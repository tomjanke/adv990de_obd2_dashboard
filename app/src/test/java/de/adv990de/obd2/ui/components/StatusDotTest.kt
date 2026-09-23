package de.adv990de.obd2.ui.components

import androidx.compose.ui.graphics.Color
import de.adv990de.obd2.ui.theme.StatusColors
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusDotTest {

    private val tertiary = Color(0xFF123456)
    private val primary = Color(0xFF654321)

    // --- default colors (no override) ---

    @Test
    fun `Ok defaults to the theme tertiary color`() {
        assertEquals(tertiary, statusDotColor(StatusDotState.Ok, color = null, tertiary = tertiary, primary = primary))
    }

    @Test
    fun `Connecting defaults to the theme primary color`() {
        assertEquals(primary, statusDotColor(StatusDotState.Connecting, color = null, tertiary = tertiary, primary = primary))
    }

    @Test
    fun `Warning defaults to StatusColors Warning`() {
        assertEquals(
            StatusColors.Warning,
            statusDotColor(StatusDotState.Warning, color = null, tertiary = tertiary, primary = primary)
        )
    }

    @Test
    fun `Critical defaults to StatusColors Critical`() {
        assertEquals(
            StatusColors.Critical,
            statusDotColor(StatusDotState.Critical, color = null, tertiary = tertiary, primary = primary)
        )
    }

    // --- override wins, regardless of state ---

    @Test
    fun `a given color overrides the Ok default`() {
        assertEquals(
            Color.Magenta,
            statusDotColor(StatusDotState.Ok, color = Color.Magenta, tertiary = tertiary, primary = primary)
        )
    }

    @Test
    fun `a given color overrides the Connecting default`() {
        // The dashboard connection dot is exactly this case: connectionDotColor() always passes
        // a non-null color, so Connecting must never fall back to theme primary in that caller.
        assertEquals(
            Color.Magenta,
            statusDotColor(StatusDotState.Connecting, color = Color.Magenta, tertiary = tertiary, primary = primary)
        )
    }
}
