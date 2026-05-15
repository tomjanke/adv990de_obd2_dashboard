package de.adv990de.obd2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Cockpit-style primary action button. Enforces the design-profile minima:
 *  - height ≥ 56 dp (glove-friendly tap target)
 *  - width  ≥ 160 dp (or full-width via `Modifier.fillMaxWidth()`)
 *  - rectangular shape (sharp corners, no rounded button look)
 *
 * Uses M3 [Button] with the active theme's primary container as background. For destructive
 * or secondary semantics, override [colors] (e.g. `ButtonDefaults.outlinedButtonColors()`)
 * or build a sibling component if it becomes a recurring need.
 */
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.ButtonColors = ButtonDefaults.buttonColors(),
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shape = RectangleShape,
        modifier = modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 160.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Preview(name = "ActionButton · default + outlined", showBackground = false)
@Composable
private fun PreviewActionButton() {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionButton(label = "Connect", onClick = {})
            ActionButton(
                label = "Disconnect",
                onClick = {},
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            )
            ActionButton(label = "Disabled", onClick = {}, enabled = false)
        }
    }
}
