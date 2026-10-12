package de.adv990de.obd2

import java.io.File

/**
 * Das Projektverzeichnis (das mit `settings.gradle.kts`) fuer Tests, die Quelltext, Doku oder Skripte lesen. Gradle startet die
 * JVM-Tests im Modulordner (`app/`); von dort geht es aufwaerts, bis die Datei gefunden ist. Eine Stelle statt neun Kopien
 * (QS TST-16) — und `System.getProperty` ist nullable, die Kopien trugen je eine Compilerwarnung.
 */
internal val projectRoot: File by lazy {
    generateSequence(File(checkNotNull(System.getProperty("user.dir")) { "user.dir ist nicht gesetzt" }).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("settings.gradle.kts nicht gefunden")
}
