package de.adv990de.obd2.map

import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.MapRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class MapRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tiles = listOf(PmTilesTestWriter.Tile(TileKey(5, 17, 11), ByteArray(10)))
    private fun good() = PmTilesTestWriter.archive(tiles, minZoom = 2, maxZoom = 14, bbox = doubleArrayOf(9.0, 49.0, 11.0, 51.0))

    /** Wartet (in echter Zeit: die Datei wird auf dem IO-Dispatcher gelesen), bis der Zustand nicht mehr „laedt“ ist. */
    private suspend fun Setup.settle() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(10_000) { repo.state.first { it !is MapState.Loading } }
        }
    }

    private class Setup(val repo: MapRepository, val log: MutableList<Pair<LogLevel, String>>)

    private fun kotlinx.coroutines.test.TestScope.setup(preset: ByteArray? = null): Setup {
        val dir = tmp.newFolder()
        if (preset != null) java.io.File(dir, MAP_FILE_NAME).writeBytes(preset)
        val log = ArrayList<Pair<LogLevel, String>>()
        val repo = MapRepository(MapFileStore(dir), backgroundScope, { l, m -> log.add(l to m) }, { null }, { null })
        return Setup(repo, log)
    }

    @Test
    fun `the state starts loading and then says there is no map`() = runTest {
        val s = setup()
        assertEquals(MapState.Loading, s.repo.state.value)
        s.settle()
        assertEquals(MapState.NoMap, s.repo.state.value)
        assertNull(s.repo.archive())
    }

    @Test
    fun `a map that lies there is found at the start`() = runTest {
        val s = setup(good())
        runCurrent()
        s.settle()
        val state = s.repo.state.value
        assertTrue(state is MapState.Ready)
        assertNotNull(s.repo.archive())
    }

    @Test
    fun `importing makes the state ready and gives a reader`() = runTest {
        val s = setup()
        runCurrent()
        s.settle()
        val bytes = good()
        val result = s.repo.importFrom({ ByteArrayInputStream(bytes) }, bytes.size.toLong())
        assertTrue(result is MapImportResult.Success)
        assertEquals(MapState.Ready((result as MapImportResult.Success).info), s.repo.state.value)
        assertNotNull(s.repo.archive())
        assertTrue(s.log.isEmpty())
    }

    @Test
    fun `a wrong file is logged by kind only and keeps the old map`() = runTest {
        val s = setup(good())
        runCurrent()
        s.settle()
        val before = s.repo.state.value
        val result = s.repo.importFrom({ ByteArrayInputStream("not a map".toByteArray()) }, 9)
        assertEquals(MapImportResult.Failure(MapProblem.NOT_PMTILES), result)
        assertEquals(before, s.repo.state.value)
        assertEquals(listOf(LogLevel.WARN to "Map import failed: NOT_PMTILES"), s.log)
    }

    @Test
    fun `a source that cannot be opened is a failure and is logged`() = runTest {
        val s = setup()
        runCurrent()
        s.settle()
        val result = s.repo.importFrom({ null }, null)
        assertEquals(MapImportResult.Failure(MapProblem.UNREADABLE), result)
        assertEquals(MapState.NoMap, s.repo.state.value)
        assertEquals(1, s.log.size)
    }

    @Test
    fun `removing deletes the map and the state follows`() = runTest {
        val s = setup(good())
        runCurrent()
        s.settle()
        s.repo.remove()
        assertEquals(MapState.NoMap, s.repo.state.value)
        assertNull(s.repo.archive())
    }
}
