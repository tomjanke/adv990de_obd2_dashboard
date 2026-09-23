package de.adv990de.obd2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme

/**
 * ALL-CAPS section header in the cockpit style: `labelMedium` with `0.12.em` letter-spacing
 * and a slightly bolder weight, in `onSurfaceVariant` by default. Use for grouping
 * dashboard sections, settings categories, and any "ABOUT" / "STATUS" / "VEHICLE" header
 * where the design profile wants a wide-tracked, restrained label.
 *
 * The input string is upper-cased at render time so callers can pass natural-case text
 * (and so screen-reader output stays normal-case where the framework supports it).
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.12.em,
        ),
        color = color,
        textAlign = textAlign,
        modifier = modifier,
    )
}

@Preview(name = "SectionLabel · cockpit headers", showBackground = false)
@Composable
private fun PreviewSectionLabel() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionLabel(text = "Vehicle")
                SectionLabel(text = "Connection")
                SectionLabel(text = "Recording")
                SectionLabel(text = "About", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
