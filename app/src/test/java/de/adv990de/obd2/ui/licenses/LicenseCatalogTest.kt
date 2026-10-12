package de.adv990de.obd2.ui.licenses

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Lizenzseite (QS C / SEC-10): Jede mitgelieferte Komponente steht mit ihrer Lizenz in der App, und die Lizenztexte
 * sind die der Originalquellen. Der Katalog ist von Hand gepflegt — dieser Test zwingt jede NEUE direkte Abhaengigkeit zu
 * einem Eintrag, damit eine Bibliothek nicht still ohne Lizenzhinweis ausgeliefert wird.
 */
class LicenseCatalogTest {

    private fun read(relative: String) = File(projectRoot, relative).readText()

    /** Direkte Laufzeit-Abhaengigkeiten aus `app/build.gradle.kts` (`implementation(libs.x)`), als Gruppen-IDs aus dem Katalog. */
    private fun directRuntimeGroups(): Map<String, String> {
        val catalog = read("gradle/libs.versions.toml")
        val libraries = Regex("""^([A-Za-z0-9_.-]+)\s*=\s*\{\s*(?:module\s*=\s*"([^":]+):[^"]+"|group\s*=\s*"([^"]+)")""", RegexOption.MULTILINE)
            .findAll(catalog).associate { m -> m.groupValues[1].replace('_', '-').replace('.', '-') to (m.groupValues[2].ifEmpty { m.groupValues[3] }) }
        val accessors = Regex("""^\s*implementation\((?:platform\()?libs\.([A-Za-z0-9.]+)\)""", RegexOption.MULTILINE)
            .findAll(read("app/build.gradle.kts")).map { it.groupValues[1] }.toList()
        return accessors.associateWith { accessor ->
            libraries[accessor.replace('.', '-')] ?: error("Katalog-Eintrag fuer libs.$accessor nicht gefunden")
        }
    }

    @Test
    fun `every direct runtime dependency is covered by a licence entry`() {
        val uncovered = directRuntimeGroups().filter { (_, group) ->
            LICENSED_COMPONENTS.none { component -> component.groupPrefixes.any { group.startsWith(it) } }
        }
        assertTrue("Direkte Abhaengigkeiten ohne Lizenzeintrag (LicenseCatalog.kt ergaenzen): $uncovered", uncovered.isEmpty())
    }

    @Test
    fun `the parser sees the dependencies - the check above is not vacuous`() {
        val groups = directRuntimeGroups().values.toSet()
        assertTrue("androidx fehlt: $groups", groups.any { it.startsWith("androidx.") })
        assertTrue("sentry fehlt: $groups", "io.sentry" in groups)
        assertTrue("hilt fehlt: $groups", "com.google.dagger" in groups)
        assertTrue("mehr als zehn direkte Abhaengigkeiten erwartet, gefunden ${groups.size} Gruppen", directRuntimeGroups().size > 10)
    }

    @Test
    fun `every licence text exists in docs-licenses and is the real text`() {
        fun asset(kind: LicenseKind) = File(projectRoot, "docs/licenses/${kind.assetName}").also {
            assertTrue("Lizenztext fehlt: docs/licenses/${kind.assetName}", it.isFile)
        }.readText()

        assertTrue(asset(LicenseKind.APACHE_2).let { it.contains("Apache License") && it.contains("Version 2.0, January 2004") && it.contains("TERMS AND CONDITIONS") })
        val mit = asset(LicenseKind.MIT_SENTRY)
        assertTrue("MIT-Text", mit.contains("MIT License") && mit.contains("Permission is hereby granted, free of charge"))
        // Der Originaltext nennt ZWEI Urheber (Sentry und den ANRWatchDog) — beide muessen mitgeliefert werden.
        assertTrue("Copyright Sentry", mit.contains("Copyright (c) 2019 Sentry"))
        assertTrue("Copyright ANRWatchDog", mit.contains("Copyright (c) 2015 Salomon BRYS"))
        assertTrue(asset(LicenseKind.OFL_IBM_PLEX).contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(asset(LicenseKind.OFL_SPACE_GROTESK).let { it.contains("SIL OPEN FONT LICENSE Version 1.1") && it.startsWith("Copyright 2020 The Space Grotesk Project Authors") })
    }

    @Test
    fun `no licence file is orphaned and every kind is used`() {
        val files = File(projectRoot, "docs/licenses").listFiles { f -> f.isFile }!!.map { it.name }.toSet()
        assertEquals("Dateien ohne Eintrag oder Eintraege ohne Datei", LicenseKind.values().map { it.assetName }.toSet(), files)
        for (kind in LicenseKind.values()) {
            assertTrue("$kind wird von keiner Komponente benutzt", LICENSED_COMPONENTS.any { it.license == kind })
        }
    }

    @Test
    fun `component names are unique and not blank`() {
        val names = LICENSED_COMPONENTS.map { it.name }
        assertEquals(names.distinct(), names)
        assertTrue(names.all { it.isNotBlank() })
    }

    @Test
    fun `the licence texts are packaged into the APK as assets`() {
        val gradle = read("app/build.gradle.kts")
        assertTrue(
            "docs/licenses muss als Asset-Verzeichnis eingebunden sein, sonst fehlen die Texte in der App",
            gradle.contains("""assets.directories.add(rootProject.file("docs/licenses").path)"""),
        )
    }
}
