package de.adv990de.obd2.ui.settings

import android.net.Uri
import de.adv990de.obd2.data.MapRepository
import de.adv990de.obd2.map.MapImportResult
import de.adv990de.obd2.map.MapInfo
import de.adv990de.obd2.map.MapProblem
import de.adv990de.obd2.map.MapState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Die Zeile „Karte“: Einspielen (hoechstens eines zugleich), Fehler, Entfernen. */
@OptIn(ExperimentalCoroutinesApi::class)
class MapSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val info = MapInfo("x", null, 9.0, 49.0, 11.0, 51.0, 2, 14, 100)
    private val uri: Uri = mockk()
    private val state = MutableStateFlow<MapState>(MapState.NoMap)

    private fun repo(block: MapRepository.() -> Unit = {}): MapRepository = mockk<MapRepository>(relaxed = true).also {
        every { it.state } returns state
        it.block()
    }

    @Test
    fun `the map state is the one of the repository and nothing is being imported at first`() {
        val vm = MapSettingsViewModel(repo())
        assertEquals(state.value, vm.mapState.value)
        assertEquals(MapImportUi.Idle, vm.importState.value)
    }

    @Test
    fun `a successful import is working meanwhile and idle afterwards`() = runTest {
        val gate = CompletableDeferred<MapImportResult>()
        val r = repo { coEvery { import(uri) } coAnswers { gate.await() } }
        val vm = MapSettingsViewModel(r)
        vm.import(uri)
        assertEquals(MapImportUi.Working, vm.importState.value)
        dispatcher.scheduler.runCurrent()
        gate.complete(MapImportResult.Success(info))
        dispatcher.scheduler.runCurrent()
        assertEquals(MapImportUi.Idle, vm.importState.value)
    }

    @Test
    fun `a failed import shows its problem`() = runTest {
        val r = repo { coEvery { import(uri) } returns MapImportResult.Failure(MapProblem.NOT_VECTOR) }
        val vm = MapSettingsViewModel(r)
        vm.import(uri)
        dispatcher.scheduler.runCurrent()
        assertEquals(MapImportUi.Failed(MapProblem.NOT_VECTOR), vm.importState.value)
    }

    @Test
    fun `a second import while one is running does nothing`() = runTest {
        val gate = CompletableDeferred<MapImportResult>()
        val r = repo { coEvery { import(uri) } coAnswers { gate.await() } }
        val vm = MapSettingsViewModel(r)
        vm.import(uri)
        dispatcher.scheduler.runCurrent()
        vm.import(uri)
        dispatcher.scheduler.runCurrent()
        coVerify(exactly = 1) { r.import(uri) }
        gate.complete(MapImportResult.Success(info))
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `a new import is possible after a failure and replaces the message`() = runTest {
        var calls = 0
        val r = repo { coEvery { import(uri) } coAnswers { if (calls++ == 0) MapImportResult.Failure(MapProblem.TOO_LARGE) else MapImportResult.Success(info) } }
        val vm = MapSettingsViewModel(r)
        vm.import(uri)
        dispatcher.scheduler.runCurrent()
        assertEquals(MapImportUi.Failed(MapProblem.TOO_LARGE), vm.importState.value)
        vm.import(uri)
        assertEquals(MapImportUi.Working, vm.importState.value)
        dispatcher.scheduler.runCurrent()
        assertEquals(MapImportUi.Idle, vm.importState.value)
        coVerify(exactly = 2) { r.import(uri) }
    }

    @Test
    fun `dismissing clears a failure and nothing else`() = runTest {
        val gate = CompletableDeferred<MapImportResult>()
        val r = repo { coEvery { import(uri) } coAnswers { gate.await() } }
        val vm = MapSettingsViewModel(r)
        vm.reportUnavailable()
        assertEquals(MapImportUi.Failed(MapProblem.UNREADABLE), vm.importState.value)
        vm.dismissFailure()
        assertEquals(MapImportUi.Idle, vm.importState.value)
        vm.import(uri)
        vm.dismissFailure()
        assertEquals(MapImportUi.Working, vm.importState.value)
        vm.reportUnavailable()
        assertEquals(MapImportUi.Working, vm.importState.value)
        gate.complete(MapImportResult.Success(info))
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `removing asks the repository`() = runTest {
        val r = repo()
        val vm = MapSettingsViewModel(r)
        vm.remove()
        dispatcher.scheduler.runCurrent()
        coVerify(exactly = 1) { r.remove() }
    }
}
