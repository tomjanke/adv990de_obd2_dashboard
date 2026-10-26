package de.adv990de.obd2.ui.logbook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogFilter
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.logbookInsights
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Die Karten der Auswertung ([LogbookStatsCards]) in einem breiten und einem schmalen Fenster: ab 600 dp stehen sie **nebeneinander** (Querformat), darunter **untereinander**
 * (Hochformat); ein Tipp auf eine Karte meldet ihre Art. Das Layout haengt an der Breite des Fensters, nicht an der Ausrichtung des Geraets — der Test setzt die Breite selbst
 * und ist deshalb in beiden Ausrichtungen derselbe Test (`requiredWidth` darf das Fenster ueberschreiten; ein Tipp laeuft nur im schmalen Fall).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LogbookStatsCardsLayoutDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    private val opened = mutableListOf<LogType>()

    private fun entry(id: Long, type: LogType, km: Int, ml: Int, full: Boolean? = null) = LogEntry(
        id = id, type = type, date = LocalDate.of(2026, 9, id.toInt()), odometerKm = km, amountMl = ml,
        fullTank = full, costCents = null, note = null, brand = null,
    )

    /** Je Art genug Eintraege fuer einen Wert: drei volle Tankungen (zwei Intervalle), zwei Oel- und zwei Kuehlfluessigkeits-Eintraege. */
    private val insights = logbookInsights(
        listOf(
            entry(1, LogType.FUEL, 1_000, 10_000, true), entry(2, LogType.FUEL, 1_200, 12_000, true), entry(3, LogType.FUEL, 1_500, 24_000, true),
            entry(4, LogType.OIL, 1_000, 250), entry(5, LogType.OIL, 2_000, 300),
            entry(6, LogType.COOLANT, 1_000, 100), entry(7, LogType.COOLANT, 11_000, 260),
        ),
    )

    private fun show(widthDp: Int) {
        composeRule.setContent {
            MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
                Box(Modifier.requiredWidth(widthDp.dp)) {
                    LogbookStatsCards(insightSectionsFor(LogFilter.All).cards, insights, onOpen = { opened += it })
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(type: LogType) = composeRule.onNodeWithTag(logbookStatsCardTestTag(type)).getBoundsInRoot()

    @Test
    fun inAWideWindowTheThreeCardsStandSideBySideAndEquallyWide() {
        show(900)

        val fuel = bounds(LogType.FUEL); val oil = bounds(LogType.OIL); val coolant = bounds(LogType.COOLANT)
        assertEquals("gleiche Oberkante", fuel.top.value, oil.top.value, 1f)
        assertEquals("gleiche Oberkante", oil.top.value, coolant.top.value, 1f)
        assertTrue("von links nach rechts in der Reihenfolge der Karten", fuel.left < oil.left && oil.left < coolant.left)
        assertEquals("gleich breit", fuel.width.value, oil.width.value, 1f)
        assertEquals("gleich breit", oil.width.value, coolant.width.value, 1f)
    }

    @Test
    fun inANarrowWindowTheCardsAreStackedAndEachFillsTheWidth() {
        show(360)

        val fuel = bounds(LogType.FUEL); val oil = bounds(LogType.OIL); val coolant = bounds(LogType.COOLANT)
        assertTrue("untereinander: ${fuel.bottom} <= ${oil.top}", fuel.bottom <= oil.top)
        assertTrue("untereinander: ${oil.bottom} <= ${coolant.top}", oil.bottom <= coolant.top)
        assertEquals("gleiche linke Kante", fuel.left.value, oil.left.value, 1f)
        assertEquals("gleiche Breite", fuel.width.value, coolant.width.value, 1f)
    }

    @Test
    fun aTapOnACardReportsItsType() {
        show(360)

        composeRule.onNodeWithTag(logbookStatsCardTestTag(LogType.OIL)).performClick()

        assertEquals(listOf(LogType.OIL), opened)
    }
}
