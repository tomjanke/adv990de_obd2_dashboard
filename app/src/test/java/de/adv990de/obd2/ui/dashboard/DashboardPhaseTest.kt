package de.adv990de.obd2.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Welchen der vier Dashboard-Zustaende die Oberflaeche zeigt (B23/B24/B29). */
class DashboardPhaseTest {

    @Test
    fun `without a link the dashboard is offline`() {
        assertEquals(
            DashboardPhase.Offline,
            dashboardPhase(isConnected = false, awaitingFirstValues = false, isStale = false)
        )
    }

    @Test
    fun `offline wins over everything else - a flag left over from a dropped link must not show up`() {
        assertEquals(
            DashboardPhase.Offline,
            dashboardPhase(isConnected = false, awaitingFirstValues = true, isStale = true)
        )
    }

    @Test
    fun `a link that is up but has not delivered yet is waiting - not 'not connected' (B24)`() {
        assertEquals(
            DashboardPhase.Waiting,
            dashboardPhase(isConnected = true, awaitingFirstValues = true, isStale = false)
        )
    }

    @Test
    fun `a link whose ECU went silent is stale`() {
        assertEquals(
            DashboardPhase.Stale,
            dashboardPhase(isConnected = true, awaitingFirstValues = false, isStale = true)
        )
    }

    @Test
    fun `stale wins over waiting - a silent ECU from the very start ends at 'no current readings'`() {
        assertEquals(
            DashboardPhase.Stale,
            dashboardPhase(isConnected = true, awaitingFirstValues = true, isStale = true)
        )
    }

    @Test
    fun `a link with values is live, even if none of them belongs to a slot (B23)`() {
        assertEquals(
            DashboardPhase.Live,
            dashboardPhase(isConnected = true, awaitingFirstValues = false, isStale = false)
        )
    }
}
