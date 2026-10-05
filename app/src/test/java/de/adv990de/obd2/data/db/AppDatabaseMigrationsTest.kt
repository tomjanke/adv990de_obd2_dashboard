package de.adv990de.obd2.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Migrationsliste ist die EINE Quelle fuer Produktion (`AppModule`) und Tests (B36): Vorher listeten
 * `AppModule` und mehrere Instrumentation-Tests `MIGRATION_1_2, MIGRATION_2_3` von Hand — wer eine neue
 * Migration schrieb und in `AppModule` vergass, hatte gruene Tests und crashte beim ersten Start eines
 * Update-Nutzers. Diese Pruefung braucht kein Geraet.
 */
class AppDatabaseMigrationsTest {

    @Test
    fun `the migrations form a gapless chain from version 1 to the current version`() {
        val steps = ALL_MIGRATIONS.sortedBy { it.startVersion }

        assertEquals("die Kette beginnt bei Version 1", 1, steps.first().startVersion)
        steps.zipWithNext { a, b ->
            assertEquals("Luecke zwischen ${a.endVersion} und ${b.startVersion}", a.endVersion, b.startVersion)
        }
        assertEquals(
            "die Kette endet bei der Version der Datenbank (@Database(version)) — eine neue Version braucht ihre Migration",
            APP_DATABASE_VERSION,
            steps.last().endVersion,
        )
    }

    @Test
    fun `every migration moves exactly one version up and none is listed twice`() {
        ALL_MIGRATIONS.forEach { m ->
            assertEquals("${m.startVersion}->${m.endVersion} springt", m.startVersion + 1, m.endVersion)
        }
        assertEquals(ALL_MIGRATIONS.size, ALL_MIGRATIONS.map { it.startVersion }.toSet().size)
    }

    @Test
    fun `the exported schema of the current version exists`() {
        // Room validiert Migrationen gegen das exportierte Schema (AppDatabaseMigrationTest): fehlt die Datei der
        // aktuellen Version, laeuft dieser Test auf dem Geraet nicht mehr.
        val candidates = listOf(
            java.io.File("schemas/de.adv990de.obd2.data.db.AppDatabase/$APP_DATABASE_VERSION.json"),
            java.io.File("app/schemas/de.adv990de.obd2.data.db.AppDatabase/$APP_DATABASE_VERSION.json"),
        )
        assertTrue("fehlt: ${candidates.map { it.path }}", candidates.any { it.isFile })
    }
}
