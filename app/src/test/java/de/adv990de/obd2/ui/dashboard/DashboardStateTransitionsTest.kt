package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Zustandsuebergaenge als reine Funktionen: welche Felder ein Uebergang setzt, zuruecksetzt und stehen laesst. */
class DashboardStateTransitionsTest {

    private val rpm = SensorValue(PidRegistry.RPM, 3000f, 1L)
    private val coolant = SensorValue(PidRegistry.COOLANT_TEMP, 90f, 1L)
    private val speed = SensorValue(PidRegistry.SPEED, 50f, 1L)
    private val error = UiText.DynamicString("boom")
    private val trend = VoltageTrend(13.6f, ChargeState.Charging)

    /** Ein Zustand mitten in einer guten Fahrt: jedes Feld, das ein Uebergang anfasst, steht auf einem Wert ungleich dem Standard. */
    private fun live() = DashboardUiState(
        readings = listOf(rpm, coolant),
        isConnected = true,
        sparklines = mapOf(rpm.pid.id to listOf(1f, 2f)),
        rpmValue = 3000f,
        engineTempC = 90f,
        speedKmh = 50f,
        gear = 3,
        oilBarColor = OilBarColor.Green,
        oilBarGreenInMillis = 1_000L,
        voltageTrend = trend,
    )

    // ── afterLinkUp ──────────────────────────────────────────────────────────

    @Test
    fun `the link coming up marks the connection and waits for the first values`() {
        val before = DashboardUiState(
            isReconnecting = true,
            reconnectAttempt = 3,
            reconnectCause = ConnectionLostCause.LINK,
            error = error,
            isStale = true,
            valuesLost = ValuesLostCause.SILENT,
            gpsSpeedKmh = 40f,
        )

        val after = before.afterLinkUp()

        assertTrue(after.isConnected)
        assertTrue(after.awaitingFirstValues)
        assertFalse(after.isReconnecting)
        assertEquals(0, after.reconnectAttempt)
        assertNull(after.reconnectCause)
        assertNull(after.error)
        assertFalse(after.isStale)
        assertNull(after.valuesLost)
        assertNull(after.gpsSpeedKmh)
    }

    @Test
    fun `the link coming up leaves an already connected state alone`() {
        val before = live().copy(error = error, awaitingFirstValues = false)

        assertSame(before, before.afterLinkUp())
    }

    // ── afterReadings ────────────────────────────────────────────────────────

    @Test
    fun `readings keep only the tiles of the slots and take the computed values`() {
        val before = DashboardUiState(
            isReconnecting = true,
            reconnectAttempt = 2,
            reconnectCause = ConnectionLostCause.FROZEN_VALUES,
            error = error,
            isStale = true,
            valuesLost = ValuesLostCause.FROZEN,
            gpsSpeedKmh = 12f,
            awaitingFirstValues = true,
            reconnectTotal = 4,
        )
        val spark = mapOf(rpm.pid.id to listOf(3f))

        val after = before.afterReadings(
            values = listOf(rpm, coolant, speed),
            displayIds = setOf(rpm.pid.id, speed.pid.id),
            rpmValue = 2500f,
            engineTempC = 88f,
            speedKmh = 60f,
            gear = GearReading(gear = 4, isNeutral = false),
            oilBarColor = OilBarColor.Yellow,
            oilBarGreenInMillis = 5_000L,
            voltageTrend = trend,
            sparklines = spark,
        )

        assertEquals(listOf(rpm, speed), after.readings)
        assertEquals(2500f, after.rpmValue, 0f)
        assertEquals(88f, after.engineTempC)
        assertEquals(60f, after.speedKmh)
        assertEquals(4, after.gear)
        assertFalse(after.isNeutral)
        assertEquals(OilBarColor.Yellow, after.oilBarColor)
        assertEquals(5_000L, after.oilBarGreenInMillis)
        assertEquals(trend, after.voltageTrend)
        assertEquals(spark, after.sparklines)
    }

    @Test
    fun `readings end every kind of missing-link state`() {
        val before = DashboardUiState(
            isReconnecting = true,
            reconnectAttempt = 2,
            reconnectCause = ConnectionLostCause.FROZEN_VALUES,
            error = error,
            isStale = true,
            valuesLost = ValuesLostCause.FROZEN,
            gpsSpeedKmh = 12f,
            awaitingFirstValues = true,
        )

        val after = before.afterReadings(
            values = emptyList(), displayIds = emptySet(), rpmValue = 0f, engineTempC = null, speedKmh = null,
            gear = GearReading.Unknown, oilBarColor = null, oilBarGreenInMillis = OIL_BAR_GREEN_HOLD_MS, voltageTrend = null, sparklines = emptyMap(),
        )

        assertTrue(after.isConnected)
        assertFalse(after.isReconnecting)
        assertNull(after.reconnectCause)
        assertFalse(after.isStale)
        assertNull(after.valuesLost)
        assertNull(after.gpsSpeedKmh)
        assertFalse(after.awaitingFirstValues)
        assertNull(after.error)
    }

    @Test
    fun `readings carry neutral over from the gear reading`() {
        val after = DashboardUiState().afterReadings(
            values = emptyList(), displayIds = emptySet(), rpmValue = 900f, engineTempC = null, speedKmh = 0f,
            gear = GearReading(gear = null, isNeutral = true), oilBarColor = null, oilBarGreenInMillis = OIL_BAR_GREEN_HOLD_MS,
            voltageTrend = null, sparklines = emptyMap(),
        )

        assertNull(after.gear)
        assertTrue(after.isNeutral)
    }

    // ── afterStale ───────────────────────────────────────────────────────────

    @Test
    fun `stale blanks the vehicle values but keeps the link, the oil bar and the sparklines`() {
        val before = live()

        val after = before.afterStale(ValuesLostCause.SILENT, lastGpsSpeedKmh = 42f)

        assertTrue(after.isStale)
        assertEquals(ValuesLostCause.SILENT, after.valuesLost)
        assertEquals(42f, after.gpsSpeedKmh)
        assertTrue(after.readings.isEmpty())
        assertEquals(0f, after.rpmValue, 0f)
        assertNull(after.engineTempC)
        assertNull(after.speedKmh)
        assertNull(after.gear)
        assertFalse(after.isNeutral)
        assertNull(after.voltageTrend)
        assertFalse(after.awaitingFirstValues)
        // bleibt: der Adapter ist ja da, die Farbe hat ihr eigenes Luecken-Limit, die Kurve gehoert zur Verbindung
        assertTrue(after.isConnected)
        assertEquals(OilBarColor.Green, after.oilBarColor)
        assertEquals(1_000L, after.oilBarGreenInMillis)
        assertEquals(before.sparklines, after.sparklines)
    }

    @Test
    fun `stale also ends neutral and the wait for the first values`() {
        val after = live().copy(isNeutral = true, gear = null, awaitingFirstValues = true).afterStale(ValuesLostCause.SILENT, lastGpsSpeedKmh = null)

        assertFalse(after.isNeutral)
        assertFalse(after.awaitingFirstValues)
        assertNull(after.gpsSpeedKmh)
    }

    @Test
    fun `stale takes the cause it is given`() {
        assertEquals(ValuesLostCause.FROZEN, live().afterStale(ValuesLostCause.FROZEN, null).valuesLost)
        assertEquals(ValuesLostCause.SILENT, live().afterStale(ValuesLostCause.SILENT, null).valuesLost)
    }

    @Test
    fun `a frozen state stays frozen when the plain stale event follows`() {
        val frozen = live().afterStale(ValuesLostCause.FROZEN, null)

        assertEquals(ValuesLostCause.FROZEN, frozen.afterStale(ValuesLostCause.SILENT, null).valuesLost)
    }

    @Test
    fun `a silent state turns frozen when the freeze is detected later`() {
        val silent = live().afterStale(ValuesLostCause.SILENT, null)

        assertEquals(ValuesLostCause.FROZEN, silent.afterStale(ValuesLostCause.FROZEN, null).valuesLost)
    }

    // ── afterIdle ────────────────────────────────────────────────────────────

    @Test
    fun `idle without an error or a reconnect resets everything to the clean disconnected state`() {
        assertEquals(DashboardUiState.disconnected(), live().afterIdle())
    }

    @Test
    fun `idle keeps an error and drops the stale readings`() {
        val before = live().copy(error = error, isStale = true, valuesLost = ValuesLostCause.SILENT, gpsSpeedKmh = 9f, awaitingFirstValues = true, isNeutral = true)

        val after = before.afterIdle()

        assertEquals(error, after.error)
        assertFalse(after.isConnected)
        assertFalse(after.isStale)
        assertNull(after.valuesLost)
        assertNull(after.gpsSpeedKmh)
        assertFalse(after.awaitingFirstValues)
        assertTrue(after.readings.isEmpty())
        assertNull(after.speedKmh)
        assertNull(after.gear)
        assertFalse(after.isNeutral)
        assertNull(after.oilBarColor)
        assertEquals(OIL_BAR_GREEN_HOLD_MS, after.oilBarGreenInMillis)
        assertNull(after.voltageTrend)
        assertTrue(after.sparklines.isEmpty())
        // anders als Gang und Geschwindigkeit bleiben Drehzahl und Motortemperatur stehen (sie werden bei der naechsten Verbindung ersetzt)
        assertEquals(3000f, after.rpmValue, 0f)
        assertEquals(90f, after.engineTempC)
    }

    @Test
    fun `idle keeps a running reconnect and its counters`() {
        val before = live().copy(isReconnecting = true, reconnectAttempt = 2, reconnectTotal = 5, reconnectCause = ConnectionLostCause.FROZEN_VALUES)

        val after = before.afterIdle()

        assertTrue(after.isReconnecting)
        assertEquals(2, after.reconnectAttempt)
        assertEquals(5, after.reconnectTotal)
        assertEquals(ConnectionLostCause.FROZEN_VALUES, after.reconnectCause)
        assertFalse(after.isConnected)
    }

    // ── afterConnectionLost ──────────────────────────────────────────────────

    @Test
    fun `a connection lost starts the reconnect with its cause and clears the link-bound values`() {
        val after = live().copy(isStale = true, valuesLost = ValuesLostCause.FROZEN, gpsSpeedKmh = 7f, awaitingFirstValues = true)
            .afterConnectionLost(ConnectionLostCause.FROZEN_VALUES)

        assertFalse(after.isConnected)
        assertTrue(after.isReconnecting)
        assertEquals(0, after.reconnectAttempt)
        assertEquals(RECONNECT_ATTEMPT_COUNT, after.reconnectTotal)
        assertEquals(ConnectionLostCause.FROZEN_VALUES, after.reconnectCause)
        assertFalse(after.isStale)
        assertNull(after.valuesLost)
        assertNull(after.gpsSpeedKmh)
        assertFalse(after.awaitingFirstValues)
        assertTrue(after.sparklines.isEmpty())
        assertNull(after.voltageTrend)
    }

    @Test
    fun `a connection lost leaves the rest to idle`() {
        // readings, Gang usw. raeumt applyIdle() ab — der Uebergang fasst sie nicht an
        val before = live()

        val after = before.afterConnectionLost(ConnectionLostCause.LINK)

        assertEquals(before.readings, after.readings)
        assertEquals(before.gear, after.gear)
        assertEquals(before.oilBarColor, after.oilBarColor)
        assertEquals(ConnectionLostCause.LINK, after.reconnectCause)
    }

    // ── afterConnectionEvent ─────────────────────────────────────────────────

    @Test
    fun `the auto-connect attempt at start keeps the rest of the state`() {
        val before = live()

        val after = before.afterConnectionEvent(ConnectionEvent.Attempting(attempt = 0, total = RECONNECT_ATTEMPT_COUNT))

        assertEquals(before.copy(isReconnecting = true, reconnectAttempt = 0), after)
    }

    @Test
    fun `a numbered attempt replaces the state but keeps the cause of the reconnect`() {
        val before = live().copy(reconnectCause = ConnectionLostCause.FROZEN_VALUES)

        val after = before.afterConnectionEvent(ConnectionEvent.Attempting(attempt = 2, total = 5))

        assertEquals(
            DashboardUiState(isReconnecting = true, reconnectAttempt = 2, reconnectTotal = 5, reconnectCause = ConnectionLostCause.FROZEN_VALUES),
            after,
        )
    }

    @Test
    fun `a numbered attempt without a cause stays without one`() {
        val after = DashboardUiState().afterConnectionEvent(ConnectionEvent.Attempting(attempt = 1, total = 5))

        assertNull(after.reconnectCause)
        assertEquals(1, after.reconnectAttempt)
    }

    @Test
    fun `connected ends the spinner and the cause but keeps everything else`() {
        val before = live().copy(isReconnecting = true, reconnectAttempt = 3, reconnectCause = ConnectionLostCause.LINK, error = error)

        val after = before.afterConnectionEvent(ConnectionEvent.Connected)

        assertEquals(before.copy(isReconnecting = false, reconnectAttempt = 0, reconnectCause = null), after)
    }

    @Test
    fun `a failed connection is the clean disconnected state with the error`() {
        val after = live().afterConnectionEvent(ConnectionEvent.Failed(error))

        assertEquals(DashboardUiState.disconnected(error), after)
    }

    @Test
    fun `aborted by the user is the clean default state without any error`() {
        val after = live().copy(error = error, isReconnecting = true, reconnectCause = ConnectionLostCause.LINK).afterConnectionEvent(ConnectionEvent.Aborted)

        assertEquals(DashboardUiState(), after)
    }
}
