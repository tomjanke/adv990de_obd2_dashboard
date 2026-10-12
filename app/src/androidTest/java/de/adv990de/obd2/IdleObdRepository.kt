package de.adv990de.obd2

import de.adv990de.obd2.data.ObdRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Das [ObdRepository] der Compose-Tests, die Hilt brauchen: ein Mock, nicht verbunden, ohne Nutzer-Trennung, steht still.
 *
 * Jede dieser Testklassen bindet ihn per `@BindValue`, weil Hilt den ganzen App-Graphen je Klasse prueft (er haengt ueber den
 * Einstiegspunkt des Vordergrunddienstes am echten Repository, dessen Konstruktor Bluetooth braucht) — `@BindValue` muss in der
 * Klasse stehen, ein Basistyp oder ein Modul koennen es nicht uebernehmen. Was sich teilen laesst, ist der Inhalt: vorher standen
 * dieselben drei Stubs in 24 Klassen. [configure] ueberschreibt oder ergaenzt (ein spaeteres `every` gewinnt), z. B.
 * `@BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository { every { isConnectedFlow } returns connected }`.
 *
 * Relaxed: was nicht gestubbt ist, antwortet mit einem Standardwert — anders als die strengen JVM-Attrappen ([fakeObdRepository]),
 * denn diese Tests pruefen Oberflaeche, nicht das Zusammenspiel mit dem Repository.
 */
internal fun idleObdRepository(configure: ObdRepository.() -> Unit = {}): ObdRepository =
    mockk<ObdRepository>(relaxed = true) {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { userDisconnectCount } returns MutableStateFlow(0)
        every { isMovingFlow } returns MutableStateFlow(false)
    }.apply(configure)
