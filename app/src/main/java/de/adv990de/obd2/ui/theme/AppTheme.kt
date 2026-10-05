package de.adv990de.obd2.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Mindestgroesse jedes Touch-Ziels (Design-Prinzip 4: Handschuhe auf dem Motorrad). */
val MIN_TOUCH_TARGET: Dp = 56.dp

/**
 * Das Theme der App: [MaterialTheme] mit unseren Farben und unserer Typografie — und der Regel "jedes Touch-Ziel
 * mindestens [MIN_TOUCH_TARGET]".
 *
 * Dafuer gibt es in Compose zwei getrennte Werte, und beide stehen auf 56 dp:
 *  - `LocalViewConfiguration.minimumTouchTargetSize` (Standard 48 dp) ist die TREFFERFLAECHE: Ein Element, dessen
 *    Layout kleiner ist, wird beim Treffer-Test bis zu dieser Groesse vergroessert. Das ist die Flaeche, die der
 *    Finger sieht (`SemanticsNode.touchBoundsInRoot`).
 *  - `LocalMinimumInteractiveComponentSize` (Material, Standard 48 dp) reserviert den PLATZ im Layout (ein IconButton
 *    ist 40 dp gross, belegt aber 48 dp), damit sich Trefferflaechen benachbarter Elemente nicht ueberlappen.
 * Nur den zweiten Wert anzuheben aendert die Trefferflaeche nicht (gemessen: weiter 48 dp).
 *
 * Die Regel gilt damit fuer jede Material-Komponente — auch kuenftige — statt an jeder Stelle einzeln nachgezogen zu
 * werden (QS C: Exit-Dialog, Bluetooth-Knopf, Verbinden/Trennen lagen darunter). Wer ein Touch-Ziel ohne
 * Material-Komponente baut (`clickable`), setzt `heightIn(min = MIN_TOUCH_TARGET)` selbst.
 */
@Composable
fun AppTheme(
    colorScheme: ColorScheme,
    typography: Typography = AppTypography,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
        ProvideMinTouchTarget(content)
    }
}

/**
 * Setzt die Regel "Touch-Ziel mindestens [MIN_TOUCH_TARGET]" (s. [AppTheme]) fuer [content].
 *
 * Fenster-basierte Komponenten (`AlertDialog`, `ModalBottomSheet`, Menues) bekommen ein eigenes Compose-Root mit der
 * `ViewConfiguration` des Systems — dort gilt der Wert aus [AppTheme] NICHT (gemessen: 48 dp im Exit-Dialog). Wer eine
 * solche Komponente benutzt, ruft diese Funktion INNERHALB ihrer Inhalts-Slots auf. Der Platz-Wert von Material
 * (`LocalMinimumInteractiveComponentSize`) wird dagegen vererbt.
 */
@Composable
fun ProvideMinTouchTarget(content: @Composable () -> Unit) {
    val baseConfiguration = LocalViewConfiguration.current
    val configuration = remember(baseConfiguration) { withMinTouchTarget(baseConfiguration) }
    CompositionLocalProvider(
        LocalViewConfiguration provides configuration,
        LocalMinimumInteractiveComponentSize provides MIN_TOUCH_TARGET,
        content = content,
    )
}

/** [base] mit der Trefferflaeche [MIN_TOUCH_TARGET]; alles andere (Timeouts, Slop) bleibt das des Systems. */
internal fun withMinTouchTarget(base: ViewConfiguration): ViewConfiguration =
    object : ViewConfiguration by base {
        override val minimumTouchTargetSize: DpSize = DpSize(MIN_TOUCH_TARGET, MIN_TOUCH_TARGET)
    }
