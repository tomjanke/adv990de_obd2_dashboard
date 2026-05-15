package de.adv990de.obd2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Thin 1 dp divider with 8 dp vertical breathing room above/below — the cockpit
 * counterpart to M3's default [HorizontalDivider], which is thicker and uses a more
 * prominent color. Uses [MaterialTheme.colorScheme.outline] by default.
 *
 * For zero-padding flush dividers (inside list items, etc.), use [HorizontalDivider]
 * directly with thickness 1 dp instead — `HairlineDivider` exists specifically for
 * section separators where the 8 dp space is part of the visual rhythm.
 */
@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outline,
) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 8.dp),
        thickness = 1.dp,
        color = color,
    )
}

@Preview(name = "HairlineDivider · between sections", showBackground = false)
@Composable
private fun PreviewHairlineDivider() {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Section one content", color = MaterialTheme.colorScheme.onSurface)
            HairlineDivider()
            Text("Section two content", color = MaterialTheme.colorScheme.onSurface)
            HairlineDivider()
            Text("Section three content", color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
