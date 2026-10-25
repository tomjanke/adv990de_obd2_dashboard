package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.alarm.AlarmSettings
import de.adv990de.obd2.fakeAppPreferences
import de.adv990de.obd2.obd.PidRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Einstellungen, die eine Sitzung einmal liest: PIDs, Aufzeichnungsregel, Watchdog-Zeit und die langsamen PIDs. */
class SessionPlanTest {

    private val voltage = PidRegistry.CONTROL_MODULE_VOLTAGE
    private val slots = listOf(PidRegistry.SPEED.id)

    @Test
    fun `the watchdog setting is read in seconds and handed on in milliseconds`() = runTest {
        val plan = readSessionPlan(slots, supported = null, appPreferences = fakeAppPreferences(obdWatchdogTimeoutSeconds = 20))

        assertEquals(20_000L, plan.watchdogTimeoutMs)
    }

    @Test
    fun `the recording rule comes from the settings`() = runTest {
        assertTrue(readSessionPlan(slots, null, fakeAppPreferences(logOnlyDtcSessions = true)).logOnlyDtc)
        assertFalse(readSessionPlan(slots, null, fakeAppPreferences(logOnlyDtcSessions = false)).logOnlyDtc)
    }

    @Test
    fun `the PIDs are the slots plus the mandatory ones, with the oil temperature only for the dynamic threshold`() = runTest {
        val without = readSessionPlan(slots, null, fakeAppPreferences(rpmDynamicThreshold = false))
        val with = readSessionPlan(slots, null, fakeAppPreferences(rpmDynamicThreshold = true))

        assertEquals(selectPids(slots, null, dynamicThresholdEnabled = false), without.selectedPids)
        assertFalse(PidRegistry.ENGINE_OIL_TEMP.id in without.selectedPids.map { it.id })
        assertEquals(selectPids(slots, null, dynamicThresholdEnabled = true), with.selectedPids)
        assertTrue(PidRegistry.ENGINE_OIL_TEMP.id in with.selectedPids.map { it.id })
    }

    @Test
    fun `the supported set filters the slots and is passed on to the slow PIDs`() = runTest {
        val supported = setOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id, voltage.id)

        val plan = readSessionPlan(listOf(PidRegistry.SPEED.id), supported, fakeAppPreferences(alarmSettings = AlarmSettings(voltageEnabled = true)))

        assertFalse("die Geschwindigkeit meldet das Steuergeraet nicht", PidRegistry.SPEED.id in plan.selectedPids.map { it.id })
        assertEquals(listOf(voltage), plan.slowPids)
    }

    @Test
    fun `the voltage is a slow PID only while its alarm is on`() = runTest {
        val supported = setOf(voltage.id)

        assertEquals(listOf(voltage), readSessionPlan(slots, supported, fakeAppPreferences(alarmSettings = AlarmSettings(voltageEnabled = true))).slowPids)
        assertTrue(readSessionPlan(slots, supported, fakeAppPreferences(alarmSettings = AlarmSettings(voltageEnabled = false))).slowPids.isEmpty())
    }

    @Test
    fun `without a discovery result there are no slow PIDs`() = runTest {
        val plan = readSessionPlan(slots, supported = null, appPreferences = fakeAppPreferences(alarmSettings = AlarmSettings(voltageEnabled = true)))

        assertTrue(plan.slowPids.isEmpty())
    }

    @Test
    fun `a voltage tile is polled every cycle and so not a slow PID`() = runTest {
        val plan = readSessionPlan(listOf(voltage.id), supported = setOf(voltage.id), appPreferences = fakeAppPreferences(alarmSettings = AlarmSettings(voltageEnabled = true)))

        assertTrue(plan.slowPids.isEmpty())
        assertTrue(voltage.id in plan.selectedPids.map { it.id })
    }
}
