package de.adv990de.obd2.obd

/**
 * Was der Adapter beim Verbinden auf den Reset (`ATZ`) geantwortet hat und wie lange er dafuer brauchte. Reine Beobachtung: der Befehl wird
 * ohnehin bei jedem Verbinden gesendet, es kommt nichts hinzu, und es geht kein Befehl ans Fahrzeug.
 *
 * Zweck: einschaetzen, ob der Adapter bei `ATZ` wirklich neu startet (eine Antwort mit Versionskennung nach einiger Zeit) oder nur quittiert
 * (sofortiges "OK") — ein Hinweis fuer die Suche nach der Ursache eingefrorener Werte, kein Beweis: ein Nachbau kann eine Kennung vortaeuschen.
 */
data class InitResult(val resetAnswer: String, val resetMillis: Long)

/**
 * Die Zeile fuers App-Log, wenn die Verbindung steht: "Connected to MWT_OBD2 (ATZ: 'ELM327 v1.0', 940 ms)"; ohne [init] (keine Verbindung
 * bekannt) nur "Connected to MWT_OBD2". Eine Stelle fuer alle drei Wege (Auto-Connect, Reconnect-Schleife, manuelles Verbinden).
 */
internal fun connectedLogMessage(deviceName: String, init: InitResult?): String =
    if (init == null) "Connected to $deviceName"
    else "Connected to $deviceName (ATZ: '${init.resetAnswer.compactForLog()}', ${init.resetMillis} ms)"

/** Eine Adapterantwort fuer eine Log-Zeile: Zeilenumbrueche und Mehrfach-Leerzeichen werden eins, hoechstens 40 Zeichen. */
internal fun String.compactForLog(): String = trim().replace(WHITESPACE, " ").take(40)
