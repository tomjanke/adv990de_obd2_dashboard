package de.adv990de.obd2.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences

@Composable
internal fun ThemePickerItem(
    theme: String,
    onThemeChange: (String) -> Unit
) {
    val isColorTheme = theme in listOf(
        AppPreferences.THEME_BLUE, AppPreferences.THEME_AMBER, AppPreferences.THEME_GREEN
    )

    // ── Color scheme row ─────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_color_scheme),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !isColorTheme,
                onClick = { onThemeChange(AppPreferences.THEME_SYSTEM) },
                shape = SegmentedButtonDefaults.itemShape(0, 4),
                icon = {},
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(16.dp).height(3.dp).background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(1.dp)))
                        Text(stringResource(R.string.settings_theme_default), softWrap = false)
                    }
                }
            )
            SegmentedButton(
                selected = theme == AppPreferences.THEME_BLUE,
                onClick = { onThemeChange(AppPreferences.THEME_BLUE) },
                shape = SegmentedButtonDefaults.itemShape(1, 4),
                icon = {},
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(16.dp).height(3.dp).background(Color(0xFF38BDF8), RoundedCornerShape(1.dp)))
                        Text(stringResource(R.string.settings_theme_blue), softWrap = false)
                    }
                }
            )
            SegmentedButton(
                selected = theme == AppPreferences.THEME_AMBER,
                onClick = { onThemeChange(AppPreferences.THEME_AMBER) },
                shape = SegmentedButtonDefaults.itemShape(2, 4),
                icon = {},
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(16.dp).height(3.dp).background(Color(0xFFFB923C), RoundedCornerShape(1.dp)))
                        Text(stringResource(R.string.settings_theme_amber), softWrap = false)
                    }
                }
            )
            SegmentedButton(
                selected = theme == AppPreferences.THEME_GREEN,
                onClick = { onThemeChange(AppPreferences.THEME_GREEN) },
                shape = SegmentedButtonDefaults.itemShape(3, 4),
                icon = {},
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(16.dp).height(3.dp).background(Color(0xFF4ADE80), RoundedCornerShape(1.dp)))
                        Text(stringResource(R.string.settings_theme_green), softWrap = false)
                    }
                }
            )
        }
    }

    // ── Brightness row (only when Default is selected) ───────────────────
    AnimatedVisibility(visible = !isColorTheme) {
        val brightnessOptions = listOf(
            AppPreferences.THEME_SYSTEM,
            AppPreferences.THEME_LIGHT,
            AppPreferences.THEME_DARK
        )
        val brightnessLabels = listOf(
            stringResource(R.string.settings_theme_system),
            stringResource(R.string.settings_theme_light),
            stringResource(R.string.settings_theme_dark)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_brightness),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                brightnessOptions.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = theme == option,
                        onClick = { onThemeChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, brightnessOptions.size),
                        icon = {},
                        label = { Text(brightnessLabels[index]) }
                    )
                }
            }
        }
    }
}
