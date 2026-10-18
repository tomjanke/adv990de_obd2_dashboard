package de.adv990de.obd2.alarm

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Die Texte der Alarme aus den echten Ressourcen ([alarmMessage], [alarmDetail]) — beide Sprachen exakt. Die Sprache der Ressourcen kommt aus einem
 * Konfigurationskontext, die Zahlenschreibweise (Komma oder Punkt) aus der Standard-Locale: `formatSensorNumber` formatiert nach `Locale.getDefault()`,
 * nicht nach der Sprache der Ressourcen. Beides wird hier gesetzt, damit der Test nicht von der Sprache des Geräts abhängt.
 */
@RunWith(AndroidJUnit4::class)
class AlarmMessagesDeviceTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun resourcesFor(locale: Locale): Resources =
        ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(locale) }).resources

    // Der JVM-Test-Quellsatz hat dieselbe Hilfe (`withDefaultLocale`); von androidTest aus ist er nicht sichtbar.
    private fun <T> inLocale(locale: Locale, block: (Resources) -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block(resourcesFor(locale))
        } finally {
            Locale.setDefault(previous)
        }
    }

    // --- Kurztext fuer den Screenreader (ohne die Live-Zahl) ---

    @Test
    fun theSummaryNamesTheKindWithoutTheNumber_inBothLanguages() {
        val voltage = Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.1f)
        val coolant = Alarm(AlarmKind.COOLANT, AlarmSeverity.NOTICE, value = 97.4f)

        assertEquals("Spannung niedrig", inLocale(Locale.GERMANY) { alarmSummary(it, voltage) })
        assertEquals("Kühlmittel heiß", inLocale(Locale.GERMANY) { alarmSummary(it, coolant) })
        assertEquals("Low voltage", inLocale(Locale.US) { alarmSummary(it, voltage) })
        assertEquals("Coolant hot", inLocale(Locale.US) { alarmSummary(it, coolant) })
    }

    @Test
    fun theSummaryDoesNotChangeWhenOnlyTheNumberChanges() {
        val a = inLocale(Locale.GERMANY) { alarmSummary(it, Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.1f)) }
        val b = inLocale(Locale.GERMANY) { alarmSummary(it, Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 11.4f)) }

        assertEquals(a, b)
    }

    @Test
    fun theSummaryOfAFaultAlarmNamesTheCodes() {
        assertEquals("Neuer Fehlercode: P0301", inLocale(Locale.GERMANY) { alarmSummary(it, Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301"))) })
        assertEquals("Neue Fehlercodes: P0301, P0420", inLocale(Locale.GERMANY) { alarmSummary(it, Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301", "P0420"))) })
    }

    // --- Spannung ---

    @Test
    fun theVoltageMessageInGermanHasACommaAndOneDecimal() {
        val text = inLocale(Locale.GERMANY) { alarmMessage(it, Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.34f)) }

        assertEquals("Spannung niedrig: 12,3 V", text)
    }

    @Test
    fun theVoltageMessageInEnglishHasAPointAndOneDecimal() {
        val text = inLocale(Locale.US) { alarmMessage(it, Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.34f)) }

        assertEquals("Low voltage: 12.3 V", text)
    }

    @Test
    fun aVoltageAlarmWithoutAValueShowsDashesInsteadOfAWrongNumber() {
        val text = inLocale(Locale.GERMANY) { alarmMessage(it, Alarm(AlarmKind.VOLTAGE, AlarmSeverity.NOTICE, value = null)) }

        assertEquals("Spannung niedrig: -- V", text)
    }

    @Test
    fun aVoltageAlarmWithANonFiniteValueShowsDashes() {
        val text = inLocale(Locale.GERMANY) { alarmMessage(it, Alarm(AlarmKind.VOLTAGE, AlarmSeverity.NOTICE, value = Float.NaN)) }

        assertEquals("Spannung niedrig: -- V", text)
    }

    // --- Kühlmittel ---

    @Test
    fun theCoolantMessageIsAWholeNumberInBothLanguages() {
        val alarm = Alarm(AlarmKind.COOLANT, AlarmSeverity.WARNING, value = 97.4f)

        assertEquals("Kühlmittel heiß: 97 °C", inLocale(Locale.GERMANY) { alarmMessage(it, alarm) })
        assertEquals("Coolant hot: 97 °C", inLocale(Locale.US) { alarmMessage(it, alarm) })
    }

    // --- Fehlercodes ---

    @Test
    fun oneNewFaultCodeIsSingular() {
        val alarm = Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301"))

        assertEquals("Neuer Fehlercode: P0301", inLocale(Locale.GERMANY) { alarmMessage(it, alarm) })
        assertEquals("New fault code: P0301", inLocale(Locale.US) { alarmMessage(it, alarm) })
    }

    @Test
    fun severalNewFaultCodesArePluralAndCommaSeparated() {
        val alarm = Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.NOTICE, codes = listOf("P0301", "P0420"))

        assertEquals("Neue Fehlercodes: P0301, P0420", inLocale(Locale.GERMANY) { alarmMessage(it, alarm) })
        assertEquals("New fault codes: P0301, P0420", inLocale(Locale.US) { alarmMessage(it, alarm) })
    }

    // --- Die zweite Zeile ---

    @Test
    fun theDetailOfASingleFaultCodeIsItsDescription() {
        val alarm = Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301"))

        assertEquals("Zylinder 1: Zündaussetzer erkannt", inLocale(Locale.GERMANY) { alarmDetail(it, alarm) })
        assertEquals("Cylinder 1 Misfire Detected", inLocale(Locale.US) { alarmDetail(it, alarm) })
    }

    @Test
    fun anUnknownSingleFaultCodeSaysSo() {
        val alarm = Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P1234"))

        assertEquals(resourcesFor(Locale.US).getString(R.string.dtc_unknown), inLocale(Locale.US) { alarmDetail(it, alarm) })
    }

    @Test
    fun severalFaultCodesHaveNoDetail() {
        val alarm = Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301", "P0420"))

        assertNull(alarmDetail(resourcesFor(Locale.GERMANY), alarm))
    }

    @Test
    fun voltageAndCoolantHaveNoDetail() {
        val resources = resourcesFor(Locale.GERMANY)

        assertNull(alarmDetail(resources, Alarm(AlarmKind.VOLTAGE, AlarmSeverity.WARNING, value = 12.1f)))
        assertNull(alarmDetail(resources, Alarm(AlarmKind.COOLANT, AlarmSeverity.WARNING, value = 101f)))
    }
}
