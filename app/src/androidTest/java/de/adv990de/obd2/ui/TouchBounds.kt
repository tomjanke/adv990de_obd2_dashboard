package de.adv990de.obd2.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.Density
import de.adv990de.obd2.ui.theme.MIN_TOUCH_TARGET

/**
 * Prueft die **Touch-Flaeche** eines Knotens (nicht die sichtbare): Material vergroessert sie ueber die sichtbare Groesse hinaus (ein `TextButton` ist sichtbar 40 dp hoch, sein Touch-Ziel 56 dp),
 * `assertHeightIsAtLeast` sieht davon nichts. Fuer den Finger zaehlt die Touch-Flaeche; die Regel ist [MIN_TOUCH_TARGET] (56 dp, s. `TouchTargetTest`).
 */
internal fun SemanticsNodeInteraction.assertTouchTargetAtLeast56(density: Density): SemanticsNodeInteraction {
    val bounds = fetchSemanticsNode().touchBoundsInRoot
    val widthDp = with(density) { bounds.width.toDp() }
    val heightDp = with(density) { bounds.height.toDp() }
    val min = MIN_TOUCH_TARGET.value - 0.5f // Rundung auf Bildpunkte
    if (widthDp.value < min || heightDp.value < min) {
        throw AssertionError("Touch-Ziel ${widthDp.value} x ${heightDp.value} dp, erwartet mindestens $MIN_TOUCH_TARGET")
    }
    return this
}
