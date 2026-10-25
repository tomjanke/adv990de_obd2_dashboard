package de.adv990de.obd2.ui.dashboard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GpsSpeedFlowTest {

    private val source = MutableStateFlow<Float?>(null)
    private val enabled = MutableStateFlow(false)
    private var upstreamStarts = 0
    private var upstreamActive = false

    /** Die GPS-Quelle, die mitzaehlt, wann sie laeuft: ein abonnierter Empfaenger verbraucht Strom. */
    private val countingSource = flow {
        upstreamStarts++
        upstreamActive = true
        try {
            source.collect { emit(it) }
        } finally {
            upstreamActive = false
        }
    }

    @Test
    fun `with the setting off there is no speed and the receiver never runs`() = runTest(UnconfinedTestDispatcher()) {
        source.value = 80f

        val speed = gpsSpeedFlow(backgroundScope, enabled, countingSource)

        assertNull(speed.value)
        assertEquals(0, upstreamStarts)
    }

    @Test
    fun `with the setting on the speed follows the source`() = runTest(UnconfinedTestDispatcher()) {
        enabled.value = true
        source.value = 45f

        val speed = gpsSpeedFlow(backgroundScope, enabled, countingSource)

        assertEquals(45f, speed.value)
        source.value = 52f
        assertEquals(52f, speed.value)
        source.value = null
        assertNull(speed.value)
    }

    @Test
    fun `it runs without any collector - Eagerly is not a matter of taste`() = runTest(UnconfinedTestDispatcher()) {
        enabled.value = true
        source.value = 30f

        // Niemand sammelt den Flow: nur .value wird gelesen. Mit WhileSubscribed startete der Upstream nie.
        val speed = gpsSpeedFlow(backgroundScope, enabled, countingSource)

        assertEquals(30f, speed.value)
        assertEquals(1, upstreamStarts)
    }

    @Test
    fun `switching the setting off stops the receiver and clears the speed, switching it on starts it again`() = runTest(UnconfinedTestDispatcher()) {
        enabled.value = true
        source.value = 60f
        val speed = gpsSpeedFlow(backgroundScope, enabled, countingSource)
        assertEquals(60f, speed.value)

        enabled.value = false

        assertNull(speed.value)
        assertEquals("der Empfaenger laeuft nur, solange der Schalter an ist", false, upstreamActive)

        source.value = 70f
        enabled.value = true

        assertEquals(70f, speed.value)
        assertEquals(2, upstreamStarts)
    }

    @Test
    fun `before the first fix the speed is null`() = runTest(UnconfinedTestDispatcher()) {
        enabled.value = true

        val speed = gpsSpeedFlow(backgroundScope, enabled, countingSource)

        assertNull(speed.value)
    }
}
