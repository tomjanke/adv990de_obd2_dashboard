package de.adv990de.obd2.ui.settings

import de.adv990de.obd2.data.AppPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsComponentsTest {

    @Test
    fun `blue amber and green classify as color themes`() {
        assertTrue(isColorTheme(AppPreferences.THEME_BLUE))
        assertTrue(isColorTheme(AppPreferences.THEME_AMBER))
        assertTrue(isColorTheme(AppPreferences.THEME_GREEN))
    }

    @Test
    fun `system light and dark do not classify as color themes`() {
        assertFalse(isColorTheme(AppPreferences.THEME_SYSTEM))
        assertFalse(isColorTheme(AppPreferences.THEME_LIGHT))
        assertFalse(isColorTheme(AppPreferences.THEME_DARK))
    }

    @Test
    fun `an unknown value does not classify as a color theme`() {
        // Defensive: a corrupted or pre-migration DataStore value should fall back to the
        // brightness picker, not silently masquerade as a color scheme.
        assertFalse(isColorTheme("unknown"))
        assertFalse(isColorTheme(""))
    }
}
