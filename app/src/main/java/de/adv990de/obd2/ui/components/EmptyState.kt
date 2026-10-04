package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme

/**
 * What a list screen shows when it has nothing to list: a centered [icon] (48 dp, the empty-state
 * size in CLAUDE.md "Iconography") above one line of [text], both in the secondary text color.
 * The icon is decorative — the text carries the meaning. Fills the space it is given.
 */
@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// region — Previews

@Preview(name = "EmptyState · light", showBackground = true, heightDp = 240)
@Composable
private fun PreviewEmptyStateLight() {
    MaterialTheme(typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            EmptyState(icon = Icons.AutoMirrored.Filled.List, text = "Keine Logs vorhanden")
        }
    }
}

@Preview(name = "EmptyState · dark (blue)", showBackground = true, heightDp = 240)
@Composable
private fun PreviewEmptyStateDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            EmptyState(icon = Icons.AutoMirrored.Filled.List, text = "Keine Logs vorhanden")
        }
    }
}

// endregion
