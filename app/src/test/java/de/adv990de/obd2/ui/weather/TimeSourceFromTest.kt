package de.adv990de.obd2.ui.weather

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [timeSourceFrom]: the 30-minute hold window of the outside temperature runs on a clock that counts deep sleep
 * (`SystemClock.elapsedRealtimeNanos()`), not on `System.nanoTime()` — a Kotlin `TimeSource.Monotonic` would pause while
 * the phone sleeps in a pocket and keep an old value far past the limit. The production clock itself needs Android
 * (device test `ElapsedRealtimeSourceDeviceTest`); what the JVM can pin down is that the time source reads ONLY the
 * clock it was given and derives elapsed time from it correctly.
 */
class TimeSourceFromTest {

    private class FakeClock(var nanos: Long)

    @Test
    fun `elapsed time is the difference of the supplied clock`() {
        val clock = FakeClock(1_000_000_000L)
        val source = timeSourceFrom { clock.nanos }

        val mark = source.markNow()
        clock.nanos += 5.minutes.inWholeNanoseconds

        assertEquals(5.minutes, mark.elapsedNow())
    }

    @Test
    fun `a frozen clock means no time passes - the JVM clock is not consulted`() {
        val clock = FakeClock(42L)
        val source = timeSourceFrom { clock.nanos }
        val mark = source.markNow()

        Thread.sleep(30) // real time passes, the supplied clock does not

        assertEquals("nothing elapsed on the supplied clock", 0.nanoseconds, mark.elapsedNow())
    }

    @Test
    fun `a clock that keeps counting through a sleep is honoured - a jump of hours shows`() {
        val clock = FakeClock(0L)
        val source = timeSourceFrom { clock.nanos }
        val mark = source.markNow()

        clock.nanos += 3.hours.inWholeNanoseconds // the device slept three hours

        assertEquals(3.hours, mark.elapsedNow())
    }

    @Test
    fun `marks are independent of each other`() {
        val clock = FakeClock(0L)
        val source = timeSourceFrom { clock.nanos }
        val early = source.markNow()
        clock.nanos += 10.seconds.inWholeNanoseconds
        val late = source.markNow()
        clock.nanos += 10.seconds.inWholeNanoseconds

        assertEquals(20.seconds, early.elapsedNow())
        assertEquals(10.seconds, late.elapsedNow())
    }
}
