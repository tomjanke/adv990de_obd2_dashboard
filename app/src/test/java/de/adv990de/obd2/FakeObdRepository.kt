package de.adv990de.obd2

import de.adv990de.obd2.data.ObdRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Ein [ObdRepository] fuer JVM-Tests einer Sitzung: nicht verbunden, ohne Nutzer-Trennung, ohne bekannte PIDs, ohne FIN und
 * ohne Fehlercodes (hart gelesen: leere Liste; weich unterwegs gelesen: keine Antwort) — die Stubs, die ViewModel und Pipeline bei jeder Sitzung brauchen und die vorher in ueber 50 Testbloecken
 * Zeile fuer Zeile standen. [configure] ueberschreibt oder ergaenzt: ein spaeteres `every` gewinnt.
 *
 * Streng (kein `relaxed`): alles, was hier nicht steht und der Test nicht stubbt, scheitert — der Test sieht, wenn die Produktion
 * etwas Neues vom Repository will. Innerhalb von [configure] heisst der Bezug `this@fakeObdRepository` (nicht `this@mockk`).
 */
internal fun fakeObdRepository(configure: ObdRepository.() -> Unit = {}): ObdRepository =
    mockk<ObdRepository> {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { userDisconnectCount } returns MutableStateFlow(0)
        every { supportedPidIds } returns MutableStateFlow(null)
        every { setMoving(any()) } just Runs
        every { updateLatestReadings(any()) } just Runs
        coEvery { readVin() } returns null
        coEvery { readDtcs() } returns emptyList()
        // Die Fehlerwache (ReadingsPipeline) liest unterwegs weich nach; "keine Antwort" ist der neutrale Standard (kein Alarm, keine Meldung).
        coEvery { readDtcsOrSkip() } returns null
    }.apply(configure)
