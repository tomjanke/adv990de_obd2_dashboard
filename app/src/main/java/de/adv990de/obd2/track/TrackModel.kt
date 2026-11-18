package de.adv990de.obd2.track

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Woher ein Track kommt. Die **Namen** stehen als Text in der Datenbank (`tracks.source`) — nie umbenennen. */
enum class TrackSource { RECORDED, IMPORTED }

/** Laenge eines Track-Namens; laenger wird abgeschnitten (GPX-Dateien nennen mitunter ganze Absaetze). */
const val TRACK_NAME_MAX = 100

/** Ein Name ohne Randleerraum, hoechstens [TRACK_NAME_MAX] Zeichen; `null`, wenn nichts uebrig bleibt. */
fun normalizeTrackName(raw: String?): String? = raw?.trim()?.take(TRACK_NAME_MAX)?.trim()?.takeIf { it.isNotEmpty() }

/** Der Name einer Aufzeichnung ohne Namen: ihr Beginn in der Zeitzone des Geraets, `2026-11-17 10:00` — sprachneutral (feste Ziffern), kein Text, der uebersetzt werden muesste. */
fun defaultTrackName(startMs: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(zone).format(Instant.ofEpochMilli(startMs))

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
