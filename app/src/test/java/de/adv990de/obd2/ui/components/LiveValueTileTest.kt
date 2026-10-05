package de.adv990de.obd2.ui.components

import androidx.compose.ui.graphics.Color
import de.adv990de.obd2.ui.theme.StatusColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function coverage for [liveValueTileAccentColor] — the [LiveValueTile] status-side-bar /
 * value-text color mapping, extracted from the Composable for testability (project convention;
 * vgl. `statusDotColor` in `StatusDot.kt`/`StatusDotTest.kt`). Previously this branch (in
 * particular [TileStatus.Warning]/[TileStatus.Critical]) was untested because the mapping lived
 * inline in the Composable.
 *
 * The interactive `combinedClickable`/`onLongPress` wiring is covered separately in the
 * androidTest counterpart of this class
 * (`app/src/androidTest/java/de/adv990de/obd2/ui/components/LiveValueTileTest.kt`), since dispatching a
 * real touch gesture needs an actual Compose UI tree — this file needs none.
 */
class LiveValueTileTest {

    private val onSurfaceVariant = Color(0xFF9DC5D6)
    private val primary = Color(0xFF38BDF8)
    private val darkSurface = Color(0xFF0A1628)
    private val lightSurface = Color(0xFFFFFBFE)

    @Test
    fun `Ok maps to the given theme primary color`() {
        assertEquals(
            primary,
            liveValueTileAccentColor(
                status = TileStatus.Ok,
                isPreview = false,
                onSurfaceVariant = onSurfaceVariant,
                primary = primary,
                surface = darkSurface,
            )
        )
    }

    @Test
    fun `Warning maps to StatusColors Warning`() {
        assertEquals(
            StatusColors.Warning,
            liveValueTileAccentColor(
                status = TileStatus.Warning,
                isPreview = false,
                onSurfaceVariant = onSurfaceVariant,
                primary = primary,
                surface = darkSurface,
            )
        )
    }

    @Test
    fun `Critical maps to StatusColors Critical`() {
        assertEquals(
            StatusColors.Critical,
            liveValueTileAccentColor(
                status = TileStatus.Critical,
                isPreview = false,
                onSurfaceVariant = onSurfaceVariant,
                primary = primary,
                surface = darkSurface,
            )
        )
    }

    @Test
    fun `isPreview overrides any status with a muted onSurfaceVariant`() {
        // Vorschau-Kacheln zeigen bewusst keine Statusfarbe (kein Fahrzeugwert vorhanden) —
        // gilt auch fuer Warning/Critical, deshalb hier gegen den "gefaehrlichsten" Status geprueft.
        assertEquals(
            onSurfaceVariant.copy(alpha = 0.35f),
            liveValueTileAccentColor(
                status = TileStatus.Critical,
                isPreview = true,
                onSurfaceVariant = onSurfaceVariant,
                primary = primary,
                surface = darkSurface,
            )
        )
    }

    @Test
    fun `Warning on a light surface uses the darker readable yellow (B28)`() {
        assertEquals(
            StatusColors.WarningOnLight,
            liveValueTileAccentColor(
                status = TileStatus.Warning,
                isPreview = false,
                onSurfaceVariant = onSurfaceVariant,
                primary = primary,
                surface = lightSurface,
            )
        )
    }

    @Test
    fun `Critical and Ok do not depend on the surface`() {
        for (surface in listOf(darkSurface, lightSurface)) {
            assertEquals(StatusColors.Critical, liveValueTileAccentColor(TileStatus.Critical, false, onSurfaceVariant, primary, surface))
            assertEquals(primary, liveValueTileAccentColor(TileStatus.Ok, false, onSurfaceVariant, primary, surface))
        }
    }
}
