package de.adv990de.obd2.ui.history

import android.net.Uri
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.data.db.entity.SessionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setup()    { Dispatchers.setMain(testDispatcher) }
    @After  fun teardown() { Dispatchers.resetMain() }

    // pagingSource() is called lazily by Paging3 when the flow is first collected.
    // Since none of these tests subscribe to vm.sessions, a relaxed mock is sufficient.
    private fun makeRepo() = mockk<SessionRepository>(relaxed = true)

    @Test
    fun `export sets shareUri on success`() = runTest {
        val uri = mockk<Uri>()
        val repo = makeRepo().also {
            coEvery { it.exportToCsv(42L) } returns uri
        }
        val vm = HistoryViewModel(repo)

        vm.export(42L)
        advanceUntilIdle()

        assertEquals(uri, vm.shareUri.value)
        assertNull(vm.exportError.value)
    }

    @Test
    fun `export sets exportError on failure`() = runTest {
        val repo = makeRepo().also {
            coEvery { it.exportToCsv(any()) } throws Exception("write failed")
        }
        val vm = HistoryViewModel(repo)

        vm.export(1L)
        advanceUntilIdle()

        assertEquals("write failed", vm.exportError.value)
        assertNull(vm.shareUri.value)
    }

    @Test
    fun `consumeShareUri clears shareUri`() = runTest {
        val uri = mockk<Uri>()
        val repo = makeRepo().also {
            coEvery { it.exportToCsv(any()) } returns uri
        }
        val vm = HistoryViewModel(repo)

        vm.export(1L)
        advanceUntilIdle()
        assertNotNull(vm.shareUri.value)

        vm.consumeShareUri()

        assertNull(vm.shareUri.value)
    }

    @Test
    fun `consumeExportError clears exportError`() = runTest {
        val repo = makeRepo().also {
            coEvery { it.exportToCsv(any()) } throws Exception("some error")
        }
        val vm = HistoryViewModel(repo)

        vm.export(1L)
        advanceUntilIdle()
        assertNotNull(vm.exportError.value)

        vm.consumeExportError()

        assertNull(vm.exportError.value)
    }

    @Test
    fun `requestDelete sets deleteConfirmation to the given session`() = runTest {
        val repo = makeRepo()
        val vm = HistoryViewModel(repo)
        val session = SessionEntity(id = 7L, startTime = 1_000L)

        vm.requestDelete(session)

        assertEquals(session, vm.deleteConfirmation.value)
    }

    @Test
    fun `cancelDelete clears deleteConfirmation without deleting`() = runTest {
        val repo = makeRepo()
        val vm = HistoryViewModel(repo)
        val session = SessionEntity(id = 7L, startTime = 1_000L)
        vm.requestDelete(session)

        vm.cancelDelete()
        advanceUntilIdle()

        assertNull(vm.deleteConfirmation.value)
        coVerify(exactly = 0) { repo.deleteSession(any()) }
    }

    @Test
    fun `confirmDelete deletes the pending session and clears deleteConfirmation`() = runTest {
        val repo = makeRepo()
        val vm = HistoryViewModel(repo)
        val session = SessionEntity(id = 7L, startTime = 1_000L)
        vm.requestDelete(session)

        vm.confirmDelete()

        // deleteConfirmation is cleared synchronously, before the coroutine runs.
        assertNull(vm.deleteConfirmation.value)
        advanceUntilIdle()
        coVerify(exactly = 1) { repo.deleteSession(7L) }
    }

    @Test
    fun `confirmDelete without a pending delete does not call the repository`() = runTest {
        val repo = makeRepo()
        val vm = HistoryViewModel(repo)

        vm.confirmDelete()
        advanceUntilIdle()

        assertNull(vm.deleteConfirmation.value)
        coVerify(exactly = 0) { repo.deleteSession(any()) }
    }
}
