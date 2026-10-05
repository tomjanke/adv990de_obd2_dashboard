package de.adv990de.obd2

import android.view.WindowManager
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.ui.theme.amberColorScheme
import de.adv990de.obd2.ui.theme.blueColorScheme
import de.adv990de.obd2.ui.theme.greenColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityTest {

    // --- resolveDarkTheme ---

    @Test
    fun `THEME_DARK resolves to dark, regardless of system setting`() {
        assertTrue(resolveDarkTheme(AppPreferences.THEME_DARK, systemDark = false))
        assertTrue(resolveDarkTheme(AppPreferences.THEME_DARK, systemDark = true))
    }

    @Test
    fun `THEME_BLUE resolves to dark, regardless of system setting`() {
        assertTrue(resolveDarkTheme(AppPreferences.THEME_BLUE, systemDark = false))
        assertTrue(resolveDarkTheme(AppPreferences.THEME_BLUE, systemDark = true))
    }

    @Test
    fun `THEME_AMBER resolves to dark, regardless of system setting`() {
        assertTrue(resolveDarkTheme(AppPreferences.THEME_AMBER, systemDark = false))
        assertTrue(resolveDarkTheme(AppPreferences.THEME_AMBER, systemDark = true))
    }

    @Test
    fun `THEME_GREEN resolves to dark, regardless of system setting`() {
        assertTrue(resolveDarkTheme(AppPreferences.THEME_GREEN, systemDark = false))
        assertTrue(resolveDarkTheme(AppPreferences.THEME_GREEN, systemDark = true))
    }

    @Test
    fun `THEME_LIGHT resolves to light, regardless of system setting`() {
        assertFalse(resolveDarkTheme(AppPreferences.THEME_LIGHT, systemDark = false))
        assertFalse(resolveDarkTheme(AppPreferences.THEME_LIGHT, systemDark = true))
    }

    @Test
    fun `THEME_SYSTEM follows the system setting`() {
        assertFalse(resolveDarkTheme(AppPreferences.THEME_SYSTEM, systemDark = false))
        assertTrue(resolveDarkTheme(AppPreferences.THEME_SYSTEM, systemDark = true))
    }

    @Test
    fun `an unrecognized theme string follows the system setting`() {
        assertFalse(resolveDarkTheme("does-not-exist", systemDark = false))
        assertTrue(resolveDarkTheme("does-not-exist", systemDark = true))
    }

    // --- resolveColorScheme ---
    //
    // Note: androidx.compose.material3.ColorScheme does not override equals()/hashCode(), so two
    // separately constructed instances (e.g. two darkColorScheme() calls) are never assertEquals-
    // equal even with identical color values. blueColorScheme/amberColorScheme/greenColorScheme
    // are singleton top-level vals, so those cases are asserted with assertSame (reference
    // identity, which is exactly what resolveColorScheme must return). The M3-default branch
    // constructs a fresh ColorScheme each call, so it is asserted by comparing representative
    // Color properties instead (Color has real structural equality).

    @Test
    fun `THEME_BLUE resolves to the blue color scheme`() {
        assertSame(blueColorScheme, resolveColorScheme(AppPreferences.THEME_BLUE, darkTheme = true))
    }

    @Test
    fun `THEME_AMBER resolves to the amber color scheme`() {
        assertSame(amberColorScheme, resolveColorScheme(AppPreferences.THEME_AMBER, darkTheme = true))
    }

    @Test
    fun `THEME_GREEN resolves to the green color scheme`() {
        assertSame(greenColorScheme, resolveColorScheme(AppPreferences.THEME_GREEN, darkTheme = true))
    }

    @Test
    fun `THEME_DARK resolves to the default M3 dark color scheme`() {
        assertColorSchemeEquals(darkColorScheme(), resolveColorScheme(AppPreferences.THEME_DARK, darkTheme = true))
    }

    @Test
    fun `THEME_LIGHT resolves to the default M3 light color scheme`() {
        // Documented as-is: this branch resolves to Material 3's default lightColorScheme(),
        // which is at odds with the project's dark-only design rule (see CLAUDE.md). That is a
        // separate design question for the project owner — this test only pins today's behavior.
        assertColorSchemeEquals(lightColorScheme(), resolveColorScheme(AppPreferences.THEME_LIGHT, darkTheme = false))
    }

    @Test
    fun `THEME_SYSTEM resolves to the default M3 dark color scheme when darkTheme is true`() {
        assertColorSchemeEquals(darkColorScheme(), resolveColorScheme(AppPreferences.THEME_SYSTEM, darkTheme = true))
    }

    @Test
    fun `THEME_SYSTEM resolves to the default M3 light color scheme when darkTheme is false`() {
        assertColorSchemeEquals(lightColorScheme(), resolveColorScheme(AppPreferences.THEME_SYSTEM, darkTheme = false))
    }

    // --- brightnessOverrideFor ---

    @Test
    fun `resumed means full brightness`() {
        assertEquals(1f, brightnessOverrideFor(resumed = true), 0.0f)
    }

    @Test
    fun `not resumed defers to the system brightness setting`() {
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            brightnessOverrideFor(resumed = false),
            0.0f,
        )
    }

    private fun assertColorSchemeEquals(
        expected: androidx.compose.material3.ColorScheme,
        actual: androidx.compose.material3.ColorScheme
    ) {
        assertEquals(expected.primary, actual.primary)
        assertEquals(expected.background, actual.background)
        assertEquals(expected.surface, actual.surface)
        assertEquals(expected.onSurface, actual.onSurface)
    }

    // --- shouldEndSessionOnDestroy (B1) ---

    @Test
    fun `a real destroy ends the session - the ViewModel and with it the recording are gone`() {
        assertTrue(shouldEndSessionOnDestroy(isChangingConfigurations = false))
    }

    @Test
    fun `a recreation for a configuration change keeps the session`() {
        assertFalse(shouldEndSessionOnDestroy(isChangingConfigurations = true))
    }
}
