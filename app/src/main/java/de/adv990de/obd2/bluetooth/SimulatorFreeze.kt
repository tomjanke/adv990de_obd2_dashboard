package de.adv990de.obd2.bluetooth

import de.adv990de.obd2.BuildConfig

/**
 * Wann der ECU-Simulator einfriert — eine **Testhilfe** fuer `scripts/freeze_check.py` (Debug-Build, Spezifikation 2026-10-24): `./gradlew assembleDebug -PsimFreezeAfterSeconds=90` baut einen
 * Simulator, dessen Steuergeraet **90 s nach der ersten Simulator-Verbindung des Prozesses** weiter antwortet, aber immer mit demselben Wert ([SimulatedEcuDispatcher], Parameter `freezeAtMs`) — auch
 * nach einem Neuverbinden. Ohne die Property (und in jedem Release-Build) ist [atMs] `null`: der Simulator friert nie ein.
 *
 * Der Zeitpunkt entsteht einmal je Prozess, beim ersten Zugriff (`SimulatorObdConnection`): die Wanduhr zu diesem Zeitpunkt plus die Sekunden aus dem Build.
 */
internal object SimulatorFreeze {
    val atMs: Long? = BuildConfig.SIM_FREEZE_AFTER_SECONDS
        .takeIf { it > 0 }
        ?.let { System.currentTimeMillis() + it * 1_000L }
}
