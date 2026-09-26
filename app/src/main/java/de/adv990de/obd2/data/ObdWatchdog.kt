package de.adv990de.obd2.data

/**
 * Reine Entscheidungsfunktion fuer den OBD-Watchdog: friert `ObdRepository.livePids()` laenger als
 * [timeoutMs] ein (kein einziger nicht-leerer Lesezyklus), soll ein erzwungener Reconnect
 * ausgeloest werden.
 *
 * Root Cause (User-Bericht 2026-09-26, s. Memory `obd-watchdog-bugfix-2026-09-26.md`):
 * `Timeout`/`NoData`/`UnknownResponse`-Fehler wurden pro PID kommentarlos uebersprungen, ohne
 * jeden Eskalations-Zaehler — im Gegensatz zu `BusInit`-Fehlern, die einen 5er-Streak-Zaehler
 * haben. Damit blieb `isConnectedFlow` faelschlich `true`, waehrend die Poll-Schleife bei einem
 * stillen BLE-Stack-Haenger leer weiterlief, ohne dass die App das je selbst bemerkt haette.
 *
 * [elapsedSinceLastSuccessMs] ist eine akkumulierte Dauer, keine Systemzeit-Differenz — s.
 * `livePids()`, das sie deterministisch aus seinem eigenen `pollIntervalMs` aufsummiert, statt die
 * echte Uhr zu lesen. Das haelt Tests schnell und nicht-flaky.
 */
fun isReadingsStale(elapsedSinceLastSuccessMs: Long, timeoutMs: Long): Boolean =
    elapsedSinceLastSuccessMs >= timeoutMs
