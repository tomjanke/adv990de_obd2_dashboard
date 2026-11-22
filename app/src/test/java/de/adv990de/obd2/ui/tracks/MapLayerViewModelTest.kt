package de.adv990de.obd2.ui.tracks

import de.adv990de.obd2.data.MapRepository
import de.adv990de.obd2.map.ByteArraySource
import de.adv990de.obd2.map.MapInfo
import de.adv990de.obd2.map.MapProblem
import de.adv990de.obd2.map.MapState
import de.adv990de.obd2.map.PmTilesArchive
import de.adv990de.obd2.map.PmTilesTestWriter
import de.adv990de.obd2.map.TileKey
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/** Die Karte fuer die Ansichten eines Tracks: nur mit einer brauchbaren Datei, und sie folgt dem Einspielen und Entfernen. */
@OptIn(ExperimentalCoroutinesApi::class)
class MapLayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val info = MapInfo("x", null, 9.0, 49.0, 11.0, 51.0, 2, 14, 100)
    private fun archive(): PmTilesArchive = kotlinx.coroutines.runBlocking {
        PmTilesArchive.open(ByteArraySource(PmTilesTestWriter.archive(listOf(PmTilesTestWriter.Tile(TileKey(5, 1, 1), ByteArray(4))))))!!
    }

    @Test
    fun `without a map there are no tiles`() = runTest {
        val repo = mockk<MapRepository> { every { state } returns MutableStateFlow(MapState.NoMap) }
        val vm = MapLayerViewModel(repo)
        backgroundScope.launch { vm.tiles.collect {} }
        runCurrent()
        assertNull(vm.tiles.value)
    }

    @Test
    fun `a broken file and a file that is still loading give no tiles`() = runTest {
        val state = MutableStateFlow<MapState>(MapState.Loading)
        val repo = mockk<MapRepository> { every { this@mockk.state } returns state }
        val vm = MapLayerViewModel(repo)
        backgroundScope.launch { vm.tiles.collect {} }
        runCurrent()
        assertNull(vm.tiles.value)
        state.value = MapState.Broken(MapProblem.NOT_PMTILES)
        runCurrent()
        assertNull(vm.tiles.value)
    }

    @Test
    fun `a ready map gives its reader and its info`() = runTest {
        val a = archive()
        val repo = mockk<MapRepository> {
            every { state } returns MutableStateFlow(MapState.Ready(info))
            coEvery { archive() } returns a
        }
        val vm = MapLayerViewModel(repo)
        backgroundScope.launch { vm.tiles.collect {} }
        runCurrent()
        assertNotNull(vm.tiles.value)
        assertSame(a, vm.tiles.value!!.archive)
        assertEquals(info, vm.tiles.value!!.info)
    }

    @Test
    fun `a ready state whose reader is gone gives no tiles`() = runTest {
        val repo = mockk<MapRepository> {
            every { state } returns MutableStateFlow(MapState.Ready(info))
            coEvery { archive() } returns null
        }
        val vm = MapLayerViewModel(repo)
        backgroundScope.launch { vm.tiles.collect {} }
        runCurrent()
        assertNull(vm.tiles.value)
    }

    @Test
    fun `removing the map takes the tiles away and importing brings them`() = runTest {
        val state = MutableStateFlow<MapState>(MapState.Ready(info))
        val a = archive()
        val repo = mockk<MapRepository> {
            every { this@mockk.state } returns state
            coEvery { archive() } returns a
        }
        val vm = MapLayerViewModel(repo)
        backgroundScope.launch { vm.tiles.collect {} }
        runCurrent()
        assertNotNull(vm.tiles.value)
        state.value = MapState.NoMap
        runCurrent()
        assertNull(vm.tiles.value)
        state.value = MapState.Ready(info.copy(name = "neu"))
        runCurrent()
        assertEquals("neu", vm.tiles.value!!.info.name)
    }
}
