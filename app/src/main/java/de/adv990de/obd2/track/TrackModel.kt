package de.adv990de.obd2.track

/** Woher ein Track kommt. Die **Namen** stehen als Text in der Datenbank (`tracks.source`) — nie umbenennen. */
enum class TrackSource { RECORDED, IMPORTED }

/** Laenge eines Track-Namens; laenger wird abgeschnitten (GPX-Dateien nennen mitunter ganze Absaetze). */
const val TRACK_NAME_MAX = 100

/** Ein Name ohne Randleerraum, hoechstens [TRACK_NAME_MAX] Zeichen; `null`, wenn nichts uebrig bleibt. */
fun normalizeTrackName(raw: String?): String? = raw?.trim()?.take(TRACK_NAME_MAX)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Ein Track in der Liste. [running] = die Aufzeichnung laeuft gerade (sie ist nicht loeschbar); [distanceM] ist auf ganze Meter gerundet, [ascentM] und [descentM] sind `null`, wenn
 * der Track keine Hoehen hat. [startMs] und [endMs] sind `null` bei einem Import ohne Zeiten — es wird nichts erfunden.
 */
data class TrackSummary(
    val id: Long,
    val name: String,
    val source: TrackSource,
    val startMs: Long?,
    val endMs: Long?,
    val sessionId: Long?,
    val distanceM: Long,
    val ascentM: Int?,
    val descentM: Int?,
    val pointCount: Int,
    val createdMs: Long,
    val running: Boolean,
)
