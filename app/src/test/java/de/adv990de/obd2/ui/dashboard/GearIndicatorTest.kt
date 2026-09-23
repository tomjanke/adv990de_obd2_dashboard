package de.adv990de.obd2.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GearIndicatorTest {

    // Reale Fahrzeugdaten (siehe Design-Spec): 150/70-18 Hinterreifen, 6-Gang-Getriebe.
    private val ratios = GearRatios(
        wheelCircumferenceMm = 2096f,
        primaryRatio = 1.9143f,
        secondaryRatio = 2.625f,
        gearRatios = listOf(2.9167f, 2.1333f, 1.6667f, 1.35f, 1.125f, 1.04f),
    )

    private fun wheelRpm(speedKmh: Float, circumferenceMm: Float = ratios.wheelCircumferenceMm) =
        speedKmh * 1_000_000f / (60f * circumferenceMm)

    private fun engineRpmForGear(gear: Int, speedKmh: Float): Float {
        val totalRatio = ratios.primaryRatio * ratios.secondaryRatio * ratios.gearRatios[gear - 1]
        return totalRatio * wheelRpm(speedKmh)
    }

    // --- Test 1 ---

    @Test
    fun `exact match with simple round numbers`() {
        // 2000mm Umfang, 1x1 Primaer-/Sekundaerbuersetzung: Ist-Uebersetzung = Gangverhaeltnis.
        val simpleRatios = GearRatios(
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f, 5f),
        )
        // 60 km/h, 2000mm Umfang -> wheelRpm = 60_000_000 / 120_000 = 500 rpm exakt.
        val gear = determineGear(engineRpm = 5000f, speedKmh = 60f, lastGear = null, ratios = simpleRatios)
        assertEquals(1, gear)
    }

    // --- Test 2 ---

    @Test
    fun `matches each of the six real gears at 100 kmh`() {
        for (gear in 1..6) {
            val rpm = engineRpmForGear(gear, speedKmh = 100f)
            assertEquals(
                "gear $gear",
                gear,
                determineGear(engineRpm = rpm, speedKmh = 100f, lastGear = null, ratios = ratios)
            )
        }
    }

    // --- Test 3 ---

    @Test
    fun `below the minimum speed the gear is unknown`() {
        assertNull(determineGear(engineRpm = 5000f, speedKmh = 7.9f, lastGear = 3, ratios = ratios))
    }

    // --- Test 4 ---

    @Test
    fun `a null speed (GPS unavailable) yields an unknown gear`() {
        assertNull(determineGear(engineRpm = 5000f, speedKmh = null, lastGear = 3, ratios = ratios))
    }

    // --- Test 5 ---

    @Test
    fun `an implausible ratio with no prior lock yields an unknown gear`() {
        // Engine RPM far too low for any gear at this speed (e.g. a bogus/glitched reading).
        assertNull(determineGear(engineRpm = 500f, speedKmh = 100f, lastGear = null, ratios = ratios))
    }

    // --- Test 6 ---

    @Test
    fun `hysteresis keeps the locked gear when a neighbor is only marginally closer`() {
        // Ist-Uebersetzung 5.40 liegt zwischen 5. Gang (5.653) und 6. Gang (5.226), naeher an 6 —
        // ohne Hysterese wuerde ein zuvor eingerasteter 5. Gang sofort zu 6 wechseln.
        val speedKmh = 100f
        val actualRatio = 5.40f
        val rpm = actualRatio * wheelRpm(speedKmh)

        assertEquals(5, determineGear(engineRpm = rpm, speedKmh = speedKmh, lastGear = 5, ratios = ratios))
    }

    // --- Test 7 ---

    @Test
    fun `a real gear change is still detected once the old gear drifts out of range`() {
        // Ist-Uebersetzung entspricht jetzt eindeutig dem 6. Gang (Abweichung 0%) — die
        // Abweichung des alten 5.-Gang-Locks dazu (~7.5%) liegt ueber STAY_TOLERANCE (6%).
        val rpm = engineRpmForGear(gear = 6, speedKmh = 100f)

        assertEquals(6, determineGear(engineRpm = rpm, speedKmh = 100f, lastGear = 5, ratios = ratios))
    }

    // --- Test 8 ---

    @Test
    fun `a locked gear that becomes implausible without a confident alternative goes unknown`() {
        // Weder der alte Lock (5) noch irgendein anderer Gang passt noch zur Ist-Uebersetzung —
        // z.B. Kupplung gezogen, Rad dreht frei durch. Muss "-" ergeben, nicht stur beim alten
        // Gang bleiben.
        assertNull(determineGear(engineRpm = 500f, speedKmh = 100f, lastGear = 5, ratios = ratios))
    }

    // --- Test 9 ---

    @Test
    fun `at exactly the minimum speed the reading is still used, not rejected`() {
        // MIN_SPEED_KMH ist 8.0f; der Vergleich im Code ist "< MIN_SPEED_KMH" — exakt 8.0 muss
        // also noch als gueltig gelten, nicht als "zu langsam" verworfen werden.
        val simpleRatios = GearRatios(
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f),
        )
        val speedKmh = 8.0f
        val rpm = 10f * wheelRpm(speedKmh, circumferenceMm = 2000f)

        assertEquals(1, determineGear(engineRpm = rpm, speedKmh = speedKmh, lastGear = null, ratios = simpleRatios))
    }

    // --- Test 10 ---

    @Test
    fun `a deviation just under ENTER_TOLERANCE locks in a new gear, just over it does not`() {
        // ENTER_TOLERANCE ist 0.04f (4 %). Ein exakter Bit-Treffer auf eine Float-Grenze laesst
        // sich durch Multiplikation/Division nicht zuverlaessig herstellen (siehe Testverlauf:
        // ein erster Versuch mit "genau 4 %"/"genau 6 %" lieferte bei 6 % durch Rundung ein
        // Ergebnis knapp ueber der Grenze) — stattdessen wird die Grenze von beiden Seiten eng
        // eingerahmt: 3,9 % muss noch einrasten, 4,1 % nicht mehr.
        val simpleRatios = GearRatios(
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f),
        )
        val speedKmh = 60f
        val wheelRpmValue = wheelRpm(speedKmh, circumferenceMm = 2000f)

        val justInside = determineGear(
            engineRpm = 10.39f * wheelRpmValue, speedKmh = speedKmh, lastGear = null, ratios = simpleRatios
        )
        val justOutside = determineGear(
            engineRpm = 10.41f * wheelRpmValue, speedKmh = speedKmh, lastGear = null, ratios = simpleRatios
        )

        assertEquals(1, justInside)
        assertNull(justOutside)
    }

    // --- Test 11 ---

    @Test
    fun `a deviation just under STAY_TOLERANCE keeps the locked gear, just over it does not`() {
        // STAY_TOLERANCE ist 0.06f (6 %), aus demselben Grund wie oben von beiden Seiten
        // eingerahmt statt exakt getroffen: 5,9 % muss den 1. Gang halten, 6,1 % nicht mehr —
        // kein anderer Gang existiert in dieser Fixture, der stattdessen ueber ENTER_TOLERANCE
        // (4 %) einrasten koennte, das beweist, dass hier wirklich STAY_TOLERANCE greift.
        val simpleRatios = GearRatios(
            wheelCircumferenceMm = 2000f,
            primaryRatio = 1f,
            secondaryRatio = 1f,
            gearRatios = listOf(10f),
        )
        val speedKmh = 60f
        val wheelRpmValue = wheelRpm(speedKmh, circumferenceMm = 2000f)

        val justInside = determineGear(
            engineRpm = 10.59f * wheelRpmValue, speedKmh = speedKmh, lastGear = 1, ratios = simpleRatios
        )
        val justOutside = determineGear(
            engineRpm = 10.61f * wheelRpmValue, speedKmh = speedKmh, lastGear = 1, ratios = simpleRatios
        )

        assertEquals(1, justInside)
        assertNull(justOutside)
    }
}
