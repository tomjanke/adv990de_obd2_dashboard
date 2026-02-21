package de.adv990de.obd2.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Represents a user-visible string from either a resource ID or a dynamic (runtime) value.
 * Use [StringResource] for strings defined in res/values/strings.xml, and [DynamicString]
 * for runtime-generated messages (e.g., exception messages from external APIs).
 */
sealed class UiText {
    /** A string resource reference resolved to a human-readable string in Composable scope. */
    data class StringResource(@StringRes val id: Int) : UiText()
    /** An already-resolved string, e.g. an exception message from the BT stack. */
    data class DynamicString(val value: String) : UiText()

    @Composable
    fun asString(): String = when (this) {
        is StringResource -> stringResource(id)
        is DynamicString  -> value
    }
}
