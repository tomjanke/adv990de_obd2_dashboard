package de.adv990de.obd2.ui.history

import androidx.compose.material3.ColorScheme
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.ui.components.StatusChipColors
import de.adv990de.obd2.ui.components.errorChipColors
import de.adv990de.obd2.ui.components.warningChipColors

/** Was die Zeile einer Fahrt von ihren Fehlercodes zeigt: wie viele, und ob ein bestaetigter (Modus 03) dabei ist. */
internal data class SessionDtcBadge(val count: Int, val hasConfirmed: Boolean)

/**
 * Das Zeichen in der Fahrtenliste — nur bei Codes. "Nicht erfasst" (`null`) und "gelesen, keine gefunden" (leer) bekommen keines: das
 * Detail der Fahrt unterscheidet die beiden, die Liste soll nicht mit Hinweisen ueberladen sein, die nichts Auffaelliges sagen.
 */
internal fun sessionDtcBadge(dtcs: List<DtcEntry>?): SessionDtcBadge? =
    dtcs?.takeIf { it.isNotEmpty() }?.let { SessionDtcBadge(count = it.size, hasConfirmed = it.any { dtc -> !dtc.isPending }) }

/**
 * Rot, sobald ein bestaetigter Code dabei ist, gelb, wenn nur ausstehende (Modus 07) dabei sind — dieselben Farben wie die Chips der
 * App-Logs mit derselben Bedeutung (ERROR, WARN), in jedem Farbschema.
 */
internal fun sessionDtcChipColors(badge: SessionDtcBadge, scheme: ColorScheme): StatusChipColors =
    if (badge.hasConfirmed) errorChipColors(scheme) else warningChipColors(scheme)
