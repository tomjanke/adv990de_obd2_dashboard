package de.adv990de.obd2.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // --- closeGearPairs ---

    @Test
    fun `the real vehicle's default ratios have no conflicting pairs`() {
        // Naechster Fall im echten Fahrzeug ist 5./6. Gang mit ca. 7.5% Abstand — ueber
        // STAY_TOLERANCE (6%), also kein Konflikt. Regressionstest gegen die Produktivwerte.
        assertEquals(emptyList<Pair<Int, Int>>(), closeGearPairs(ratios.primaryRatio, ratios.secondaryRatio, ratios.gearRatios))
    }

    @Test
    fun `two identical gear ratios are reported as a conflicting pair`() {
        val gearRatios = listOf(10f, 10f, 5f)
        assertEquals(listOf(1 to 2), closeGearPairs(primaryRatio = 1f, secondaryRatio = 1f, gearRatios = gearRatios))
    }

    @Test
    fun `a deviation just under STAY_TOLERANCE is a conflict, just over it is not`() {
        // deviation = |a-b| / max(a,b); die exakte 6%-Grenze liegt hier bei a=10.6383 (b=10) —
        // wie bei determineGear von beiden Seiten mit Abstand eingerahmt statt exakt getroffen.
        val justInside = closeGearPairs(primaryRatio = 1f, secondaryRatio = 1f, gearRatios = listOf(10.63f, 10f))
        val justOutside = closeGearPairs(primaryRatio = 1f, secondaryRatio = 1f, gearRatios = listOf(10.65f, 10f))

        assertEquals(listOf(1 to 2), justInside)
        assertEquals(emptyList<Pair<Int, Int>>(), justOutside)
    }

    @Test
    fun `a non-adjacent conflicting pair is still found`() {
        // Gang 1 und Gang 3 liegen nah beieinander, Gang 2 liegt dazwischen aber weit genug weg
        // von beiden — die Pruefung darf sich nicht auf indexbenachbarte Paare beschraenken.
        val gearRatios = listOf(10f, 7f, 9.8f)
        assertEquals(listOf(1 to 3), closeGearPairs(primaryRatio = 1f, secondaryRatio = 1f, gearRatios = gearRatios))
    }

    // --- holdGear ---

    @Test
    fun `a freshly computed gear always wins, regardless of speed or held gear`() {
        assertEquals(3, holdGear(computedGear = 3, speedKmh = 60f, heldGear = 5))
        assertEquals(3, holdGear(computedGear = 3, speedKmh = null, heldGear = null))
    }

    @Test
    fun `no speed at all clears the held gear`() {
        // Kein GPS-Fix — z.B. GPS aus oder Signal komplett weg, nicht nur eine kurze Fahrt-
        // Unterbrechung. Das ist kein Fall, in dem der Fahrer einen alten Gang sehen soll.
        assertNull(holdGear(computedGear = null, speedKmh = null, heldGear = 3))
    }

    @Test
    fun `speed below the minimum clears the held gear`() {
        assertNull(holdGear(computedGear = null, speedKmh = 7.9f, heldGear = 3))
    }

    @Test
    fun `exactly the minimum speed still holds the gear, not clears it`() {
        // Muss zum "<" in determineGear passen: exakt MIN_SPEED_KMH gilt dort noch als gueltig,
        // also darf holdGear hier nicht strenger sein und den Gang faelschlich loeschen.
        assertEquals(3, holdGear(computedGear = null, speedKmh = 8.0f, heldGear = 3))
    }

    @Test
    fun `an implausible reading above the minimum speed holds the last gear`() {
        // Der Kernfall aus dem Feedback: Kupplung gezogen oder Gas weg macht die Motordrehzahl
        // kurzzeitig unplausibel gegenueber der (weiterhin validen) Geschwindigkeit — die Anzeige
        // soll dabei nicht auf "–" springen, solange das Fahrzeug erkennbar noch rollt.
        assertEquals(3, holdGear(computedGear = null, speedKmh = 60f, heldGear = 3))
    }

    @Test
    fun `nothing to hold and no fresh gear yields null`() {
        assertNull(holdGear(computedGear = null, speedKmh = 60f, heldGear = null))
    }

    // --- isNeutral ---

    @Test
    fun `standing with the engine running is neutral`() {
        assertTrue(isNeutral(engineRpm = 1200f, speedKmh = 0f, wasNeutral = false))
    }

    @Test
    fun `a stopped or cranking engine is never neutral`() {
        // Zuendung aus (0) und Anlasser (~250) sind kein Leerlauf — erst ab ENGINE_RUNNING_MIN_RPM.
        assertFalse(isNeutral(engineRpm = 0f, speedKmh = 0f, wasNeutral = false))
        assertFalse(isNeutral(engineRpm = 499f, speedKmh = 0f, wasNeutral = true))
        assertTrue(isNeutral(engineRpm = 500f, speedKmh = 0f, wasNeutral = false))
    }

    @Test
    fun `a NaN engine speed is not neutral`() {
        assertFalse(isNeutral(engineRpm = Float.NaN, speedKmh = 0f, wasNeutral = false))
    }

    @Test
    fun `an unknown speed is never neutral`() {
        // Anders als die Fahrterkennung (fehlende Geschwindigkeit gilt dort als "steht"): ohne
        // Geschwindigkeit ist unbekannt, ob das Fahrzeug steht — lieber "–" als ein falsches "N".
        assertFalse(isNeutral(engineRpm = 1200f, speedKmh = null, wasNeutral = false))
        assertFalse(isNeutral(engineRpm = 1200f, speedKmh = null, wasNeutral = true))
    }

    @Test
    fun `entering neutral requires a speed below 2 kmh`() {
        assertTrue(isNeutral(engineRpm = 1200f, speedKmh = 1.99f, wasNeutral = false))
        assertFalse(isNeutral(engineRpm = 1200f, speedKmh = 2.0f, wasNeutral = false))
    }

    @Test
    fun `an already neutral state holds up to 4 kmh, then drops`() {
        // Schmitt-Trigger gegen GPS-Rauschen um die Eintrittsschwelle: 3 km/h reichen nicht zum
        // Eintritt, halten aber ein bereits angezeigtes "N".
        assertFalse(isNeutral(engineRpm = 1200f, speedKmh = 3f, wasNeutral = false))
        assertTrue(isNeutral(engineRpm = 1200f, speedKmh = 3f, wasNeutral = true))
        assertTrue(isNeutral(engineRpm = 1200f, speedKmh = 3.99f, wasNeutral = true))
        assertFalse(isNeutral(engineRpm = 1200f, speedKmh = 4.0f, wasNeutral = true))
    }

    @Test
    fun `moving is never neutral`() {
        assertFalse(isNeutral(engineRpm = 3000f, speedKmh = 50f, wasNeutral = false))
        assertFalse(isNeutral(engineRpm = 3000f, speedKmh = 50f, wasNeutral = true))
    }

    @Test
    fun `between the neutral hold threshold and the minimum gear speed nothing is determined`() {
        // Luecke zwischen "N" (< 4 km/h) und Gang (ab 8 km/h): bei 5 km/h zeigt die Anzeige weder
        // "N" noch einen Gang, also bleibt es bei "–" — zu langsam fuer eine verlaessliche
        // GPS-Uebersetzung, aber zu schnell, um "steht" zu behaupten.
        assertFalse(isNeutral(engineRpm = 2000f, speedKmh = 5f, wasNeutral = true))
        assertNull(determineGear(engineRpm = 2000f, speedKmh = 5f, lastGear = null, ratios = ratios))
    }
}
