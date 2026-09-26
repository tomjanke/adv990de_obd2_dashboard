package de.adv990de.obd2.data

/**
 * Reine Entscheidungsfunktion fuer den OBD-Watchdog: friert `ObdRepository.livePids()` laenger als
 * [timeoutMs] ein (kein einziger nicht-leerer Lesezyklus) und antwortet der Adapter dabei gar nicht
 * mehr (Timeout), soll ein erzwungener Reconnect ausgeloest werden.
 *
 * Root Cause (User-Bericht 2026-09-26, s. Memory `obd-watchdog-bugfix-2026-09-26.md`):
 * `Timeout`/`NoData`/`UnknownResponse`-Fehler wurden pro PID kommentarlos uebersprungen, ohne
 * jeden Eskalations-Zaehler — im Gegensatz zu `BusInit`-Fehlern, die einen 5er-Streak-Zaehler
 * haben. Damit blieb `isConnectedFlow` faelschlich `true`, waehrend die Poll-Schleife bei einem
 * stillen BLE-Stack-Haenger leer weiterlief, ohne dass die App das je selbst bemerkt haette.
 *
 * [elapsedSinceLastSuccessMs] ist echte, per `TimeSource`-Marke gemessene Zeit seit dem letzten
 * erfolgreichen Zyklus (s. `livePids()`); Tests speisen dort die virtuelle Uhr von
 * kotlinx-coroutines-test ein, statt real zu warten. Eine fruehere Fassung summierte stattdessen
 * `pollIntervalMs` pro leerem Zyklus auf — das unterschlug die 5 s, die jeder einzelne echte Timeout
 * blockiert, und verzoegerte die Eskalation bei einem echten Haenger um das 30-40-Fache.
 */
fun isReadingsStale(elapsedSinceLastSuccessMs: Long, timeoutMs: Long): Boolean =
    elapsedSinceLastSuccessMs >= timeoutMs
