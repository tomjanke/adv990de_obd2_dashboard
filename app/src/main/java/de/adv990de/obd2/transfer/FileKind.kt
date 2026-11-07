package de.adv990de.obd2.transfer

/** Was eine gewählte Datei ist (D7): ein Paket oder eine der drei CSV-Arten, die diese App schreibt. */
enum class FileKind { PACKAGE, TRIPS_CSV, LOGBOOK_CSV, APP_LOG_CSV, UNKNOWN }

private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/**
 * Erkennt die Art an den ersten Bytes [head] (höchstens [length]): ZIP-Kopf `50 4B 03 04` = Paket; sonst Text, dessen erste Zeile die Pflichtspalten einer der drei CSV-Arten
 * trägt. Ein NUL-Byte, eine leere Datei oder eine leere erste Zeile ist [FileKind.UNKNOWN].
 */
fun detectFileKind(head: ByteArray, length: Int = head.size): FileKind {
    val n = minOf(length, head.size)
    if (n == 0) return FileKind.UNKNOWN
    if (n >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { head[it] == ZIP_MAGIC[it] }) return FileKind.PACKAGE
    if ((0 until n).any { head[it] == 0.toByte() }) return FileKind.UNKNOWN
    val start = if (n >= BOM.size && BOM.indices.all { head[it] == BOM[it] }) BOM.size else 0
    val text = String(head, start, n - start, Charsets.UTF_8)
    val firstLine = text.takeWhile { it != '\n' && it != '\r' }
    if (firstLine.isBlank()) return FileKind.UNKNOWN
    val columns = firstLine.split(',').map { it.trim().trim('"').trim() }.toSet()
    return when {
        columns.containsAll(listOf("timestamp", "pid", "value")) -> FileKind.TRIPS_CSV
        columns.containsAll(listOf("date", "type", "odometer_km")) -> FileKind.LOGBOOK_CSV
        columns.containsAll(listOf("timestamp", "level", "message")) -> FileKind.APP_LOG_CSV
        else -> FileKind.UNKNOWN
    }
}
