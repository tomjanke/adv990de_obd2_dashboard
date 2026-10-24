package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.R
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.obd.model.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Welches Band das Dashboard fuer fehlende Fahrzeugwerte zeigt, und was die Geschwindigkeits-Kachel dann zeigt (D3). */
class ValuesLostTest {

    private fun banner(
        phase: DashboardPhase,
        lost: ValuesLostCause? = null,
        cause: ConnectionLostCause? = null,
        reconnecting: Boolean = false,
        attempt: Int = 0,
        total: Int = 5,
    ) = valuesBanner(phase, lost, cause, reconnecting, attempt, total)

    // --- das laute Band ---

    @Test
    fun `while values flow, wait or the link is simply off there is no loud banner - the small ones stay`() {
        assertNull(banner(DashboardPhase.Live))
        assertNull(banner(DashboardPhase.Waiting))
        assertNull(banner(DashboardPhase.Offline))
        assertNull("ein ganz normales Neuverbinden nach einem Link-Verlust bleibt das schmale Band", banner(DashboardPhase.Offline, cause = ConnectionLostCause.LINK, reconnecting = true, attempt = 1))
    }

    @Test
    fun `a hold says the values are frozen and what that means for the display`() {
        val b = banner(DashboardPhase.Stale, lost = ValuesLostCause.FROZEN)!!

        assertEquals(R.string.values_lost_frozen_title, b.titleRes)
        assertEquals(R.string.values_lost_frozen_detail, b.detailRes)
        assertEquals(emptyList<Any>(), b.titleArgs)
    }

    @Test
    fun `an adapter that is silent says there are no current values, without a claim about the cause`() {
        val b = banner(DashboardPhase.Stale, lost = ValuesLostCause.SILENT)!!

        assertEquals(R.string.values_lost_silent_title, b.titleRes)
        assertNull(b.detailRes)
    }

    @Test
    fun `stale without a recorded cause is silent - the safe reading`() {
        assertEquals(R.string.values_lost_silent_title, banner(DashboardPhase.Stale, lost = null)!!.titleRes)
    }

    @Test
    fun `the reconnect after frozen values names the attempt and has priority over the small preview banner`() {
        val b = banner(DashboardPhase.Offline, cause = ConnectionLostCause.FROZEN_VALUES, reconnecting = true, attempt = 2, total = 5)!!

        assertEquals(R.string.values_lost_frozen_reconnecting, b.titleRes)
        assertEquals(listOf<Any>(2, 5), b.titleArgs)
        assertNull(b.detailRes)
    }

    @Test
    fun `before the first attempt is numbered the banner has no number either`() {
        val b = banner(DashboardPhase.Offline, cause = ConnectionLostCause.FROZEN_VALUES, reconnecting = true, attempt = 0)!!

        assertEquals(R.string.values_lost_frozen_reconnecting_start, b.titleRes)
        assertEquals(emptyList<Any>(), b.titleArgs)
    }

    @Test
    fun `a cause that is left over without a running reconnect shows nothing`() {
        assertNull(banner(DashboardPhase.Offline, cause = ConnectionLostCause.FROZEN_VALUES, reconnecting = false, attempt = 3))
    }

    // --- D3: die GPS-Geschwindigkeit in "veraltet" ---

    private val placeholders = listOf(
        SensorValue(PidRegistry.RPM, 0f),
        SensorValue(PidRegistry.SPEED, 0f),
        SensorValue(PidRegistry.COOLANT_TEMP, 0f),
    )

    @Test
    fun `in the stale phase the speed placeholder becomes the GPS speed`() {
        val shown = placeholders.withStaleGpsSpeed(DashboardPhase.Stale, 87.5f)

        val speed = shown.single { it.pid.id == PidRegistry.SPEED.id }
        assertEquals(87.5f, speed.value, 0f)
        assertEquals("die Quelle bleibt sichtbar", ValueSource.GPS, speed.source)
        assertEquals("nur die Geschwindigkeit wird ersetzt", placeholders.filter { it.pid.id != PidRegistry.SPEED.id }, shown.filter { it.pid.id != PidRegistry.SPEED.id })
        assertEquals("die Reihenfolge der Belegung bleibt", placeholders.map { it.pid.id }, shown.map { it.pid.id })
    }

    @Test
    fun `without a usable GPS value or outside the stale phase the placeholders stay as they are`() {
        for (kmh in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertSame("GPS $kmh", placeholders, placeholders.withStaleGpsSpeed(DashboardPhase.Stale, kmh))
        }
        for (phase in listOf(DashboardPhase.Offline, DashboardPhase.Waiting, DashboardPhase.Live)) {
            assertSame("Phase $phase: eine Vorschau mit Zahlen war schon einmal der Anlass einer Fehlersuche", placeholders, placeholders.withStaleGpsSpeed(phase, 50f))
        }
    }

    @Test
    fun `an assignment without a speed tile is left alone`() {
        val noSpeed = placeholders.filter { it.pid.id != PidRegistry.SPEED.id }

        assertSame(noSpeed, noSpeed.withStaleGpsSpeed(DashboardPhase.Stale, 50f))
    }

    @Test
    fun `only the GPS reading of the stale phase is drawn as a value, every other placeholder stays a dash`() {
        assertEquals(true, SensorValue(PidRegistry.SPEED, 50f, source = ValueSource.GPS).isStaleGpsReading())
        assertEquals(false, SensorValue(PidRegistry.SPEED, 0f).isStaleGpsReading())
        assertEquals(false, SensorValue(PidRegistry.RPM, 0f, source = ValueSource.ECU).isStaleGpsReading())
    }
}
