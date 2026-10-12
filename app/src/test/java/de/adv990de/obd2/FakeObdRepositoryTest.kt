package de.adv990de.obd2

import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Standardwerte von [fakeObdRepository] und dass [configure] sie ueberschreibt, nicht nur ergaenzt. */
class FakeObdRepositoryTest {

    @Test
    fun `by default it is not connected and has nothing to report`() = runTest {
        val repo = fakeObdRepository()

        assertFalse(repo.isConnectedFlow.value)
        assertEquals(0, repo.userDisconnectCount.value)
        assertNull(repo.supportedPidIds.value)
        assertNull(repo.readVin())
        assertTrue(repo.readDtcs().isEmpty())
    }

    @Test
    fun `configure overrides a default`() {
        val connected = MutableStateFlow(true)

        val repo = fakeObdRepository { every { this@fakeObdRepository.isConnectedFlow } returns connected }

        assertTrue(repo.isConnectedFlow.value)
    }
}
