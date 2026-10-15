package de.adv990de.obd2.ui.fuel

import de.adv990de.obd2.fuel.FuelGaugeState
import de.adv990de.obd2.fuel.FuelRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelGaugeViewModelTest {

    private val state = MutableStateFlow<FuelGaugeState?>(null)
    private val repository = mockk<FuelRepository> {
        every { this@mockk.state } returns this@FuelGaugeViewModelTest.state
        every { reset() } just Runs
    }

    @Test
    fun `without a configured range the gauge is null - the bar stays hidden`() {
        assertNull(FuelGaugeViewModel(repository).gauge.value)
    }

    @Test
    fun `the gauge follows the repository`() {
        val vm = FuelGaugeViewModel(repository)

        state.value = FuelGaugeState(320, 100f)

        assertEquals(FuelGaugeState(320, 100f), vm.gauge.value)
    }

    @Test
    fun `reset goes to the repository`() {
        FuelGaugeViewModel(repository).reset()

        verify(exactly = 1) { repository.reset() }
    }
}
