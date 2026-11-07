package de.adv990de.obd2.ui.navigation

import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.ui.transfer.TRANSFER_MODE_ARG
import de.adv990de.obd2.ui.transfer.TRANSFER_PART_ARG
import de.adv990de.obd2.ui.transfer.TRANSFER_PICK_ARG
import de.adv990de.obd2.ui.transfer.TransferMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Adresse der Seite „Daten sichern und wiederherstellen“ und ihrer Abkuerzungen: das Muster (`destination.route`) und was [Screen.DataTransfer.routeFor] einsetzt. */
class ScreenRoutesTest {

    @Test
    fun `the route is the pattern with the three optional arguments`() {
        assertEquals("data_transfer?mode={mode}&part={part}&pick={pick}", Screen.DataTransfer.route)
    }

    @Test
    fun `the argument names of the pattern are the ones the view model and the nav host read`() {
        val route = Screen.DataTransfer.route
        for (name in listOf(TRANSFER_MODE_ARG, TRANSFER_PART_ARG, TRANSFER_PICK_ARG)) {
            assertTrue("$name={$name} fehlt im Muster", route.contains("$name={$name}"))
        }
    }

    @Test
    fun `without arguments the address exports with no part and no pick`() {
        assertEquals("data_transfer?mode=export&part=&pick=false", Screen.DataTransfer.routeFor())
    }

    @Test
    fun `the mode is written in lower case`() {
        assertEquals("data_transfer?mode=import&part=&pick=false", Screen.DataTransfer.routeFor(TransferMode.IMPORT))
    }

    @Test
    fun `a part is written as its name and every kind is readable from the route`() {
        for (part in DataPart.entries) {
            val route = Screen.DataTransfer.routeFor(part = part)
            assertEquals("data_transfer?mode=export&part=${part.name}&pick=false", route)
            val value = route.substringAfter("part=").substringBefore('&')
            assertEquals(part, DataPart.entries.single { it.name == value })
        }
    }

    @Test
    fun `pick is part of the address`() {
        assertEquals("data_transfer?mode=import&part=TRIPS&pick=true", Screen.DataTransfer.routeFor(TransferMode.IMPORT, DataPart.TRIPS, pick = true))
    }
}
