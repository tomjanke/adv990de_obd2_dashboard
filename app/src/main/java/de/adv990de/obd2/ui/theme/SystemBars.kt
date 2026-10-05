package de.adv990de.obd2.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Ab dieser API zeichnet eine App mit `targetSdk >= 35` immer hinter die Systemleisten (Edge-to-Edge ist erzwungen). */
internal const val EDGE_TO_EDGE_ENFORCED_SDK = 35

/**
 * Ob die App die Symbolfarbe der Statusleiste selbst bestimmen soll. Nur ab Android 15: Dort ist die Leiste durchsichtig
 * und der App-Hintergrund scheint durch, die Symbole muessen also zu ihm passen. Davor hat die Leiste eine eigene,
 * undurchsichtige Farbe aus dem Theme — passende Symbole zu "erzwingen" (dunkle Symbole, weil das App-Schema hell ist)
 * koennte dort dunkle Symbole auf einer schwarzen Leiste ergeben.
 */
internal fun controlsStatusBarIcons(sdkInt: Int): Boolean = sdkInt >= EDGE_TO_EDGE_ENFORCED_SDK

/**
 * Ob die Statusleisten-Symbole DUNKEL sein sollen: auf hellem Hintergrund ja, auf dunklem nein. Massgeblich ist der
 * Hintergrund des Farbschemas, nicht der Systemmodus (das helle System mit einem Cockpit-Schema ergab dunkle Symbole auf
 * fast Schwarz).
 */
internal fun useDarkStatusBarIcons(colorScheme: ColorScheme): Boolean = colorScheme.background.luminance() > 0.5f

/**
 * Setzt die Symbolfarbe der Statusleiste passend zu [colorScheme] (s. [controlsStatusBarIcons] und
 * [useDarkStatusBarIcons]). Die Navigationsleiste fasst das nicht an: ihr Griff passt sich auf Android 10+ selbst an den
 * Inhalt an.
 */
@Composable
internal fun StatusBarIconsEffect(colorScheme: ColorScheme) {
    val view = LocalView.current
    if (view.isInEditMode || !controlsStatusBarIcons(Build.VERSION.SDK_INT)) return
    val darkIcons = useDarkStatusBarIcons(colorScheme)
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkIcons
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
