package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.fakeAppPreferences
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GearRatiosFlowTest {

    private val defaults = GearRatios(
        AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM,
        AppPreferences.DEFAULT_PRIMARY_RATIO,
        AppPreferences.DEFAULT_SECONDARY_RATIO,
        AppPreferences.DEFAULT_GEAR_RATIOS,
    )

    @Test
    fun `the value is the combination of the four settings`() = runTest(UnconfinedTestDispatcher()) {
        val prefs = fakeAppPreferences(
            wheelCircumferenceMm = 2000f,
            primaryRatio = 2f,
            secondaryRatio = 3f,
            gearRatios = listOf(10f, 8f, 6f, 5f, 4f, 3f),
        )

        val ratios = gearRatiosFlow(prefs, backgroundScope)

        assertEquals(GearRatios(2000f, 2f, 3f, listOf(10f, 8f, 6f, 5f, 4f, 3f)), ratios.value)
    }

    @Test
    fun `before every setting has arrived the value is the default, and it works without a collector`() = runTest(UnconfinedTestDispatcher()) {
        val wheel = MutableSharedFlow<Float>()
        val prefs = fakeAppPreferences()
        every { prefs.wheelCircumferenceMm } returns wheel

        // Eagerly: der Wert wird nie ueber einen Compose-Collector gehalten, nur ueber .value gelesen.
        val ratios = gearRatiosFlow(prefs, backgroundScope)

        assertEquals(defaults, ratios.value)
        wheel.emit(1950f)
        assertEquals(defaults.copy(wheelCircumferenceMm = 1950f), ratios.value)
    }

    @Test
    fun `a later change of one setting reaches the value`() = runTest(UnconfinedTestDispatcher()) {
        val primary = MutableSharedFlow<Float>()
        val prefs = fakeAppPreferences(wheelCircumferenceMm = 2000f)
        every { prefs.primaryRatio } returns primary
        val ratios = gearRatiosFlow(prefs, backgroundScope)

        primary.emit(1.9f)
        primary.emit(2.2f)

        assertEquals(GearRatios(2000f, 2.2f, AppPreferences.DEFAULT_SECONDARY_RATIO, AppPreferences.DEFAULT_GEAR_RATIOS), ratios.value)
    }
}
