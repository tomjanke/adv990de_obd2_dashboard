package de.adv990de.obd2.transfer

import de.adv990de.obd2.data.DtcHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class DtcHistoryMergeTest {

    private fun e(first: Long, last: Long, pending: Boolean, code: String = "P0301") = DtcHistoryEntry(code, first, last, pending)

    @Test
    fun `first is always the smaller and last the larger over every order of the times`() {
        val times = listOf(100L, 200L, 300L, 400L)
        for (ef in times) for (el in times.filter { it >= ef }) for (nf in times) for (nl in times.filter { it >= nf }) {
            val merged = mergeDtcHistoryEntry(e(ef, el, false), e(nf, nl, true))
            assertEquals("first for $ef..$el and $nf..$nl", minOf(ef, nf), merged.firstSeen)
            assertEquals("last for $ef..$el and $nf..$nl", maxOf(el, nl), merged.lastSeen)
        }
    }

    @Test
    fun `the pending flag comes from the entry with the larger last and the existing one wins a tie`() {
        assertEquals(true, mergeDtcHistoryEntry(e(1, 100, false), e(1, 200, true)).lastWasPending)
        assertEquals(false, mergeDtcHistoryEntry(e(1, 200, false), e(1, 100, true)).lastWasPending)
        assertEquals(false, mergeDtcHistoryEntry(e(1, 100, false), e(1, 100, true)).lastWasPending)
        assertEquals(true, mergeDtcHistoryEntry(e(1, 100, true), e(1, 100, false)).lastWasPending)
    }

    @Test
    fun `the plan counts new updated and unchanged codes`() {
        val existing = listOf(e(10, 100, false, "P0301"), e(10, 100, false, "P0420"), e(10, 100, false, "P0171"))
        val parsed = ParsedDtcHistory(
            listOf(
                e(10, 100, false, "P0301"), // identical: unchanged
                e(20, 50, true, "P0420"), // inside the existing range, older last: unchanged
                e(5, 100, false, "P0171"), // lowers first: updated
                e(1, 2, false, "P0500"), // new
            ),
            rejected = 3,
        )

        val plan = planDtcHistoryMerge(existing, parsed)

        assertEquals(1, plan.newCodes)
        assertEquals(1, plan.updatedCodes)
        assertEquals(2, plan.unchangedCodes)
        assertEquals(3, plan.rejected)
        assertEquals(0, plan.droppedByCap)
        assertEquals(setOf(e(5, 100, false, "P0171"), e(1, 2, false, "P0500")), plan.entries.toSet())
    }

    @Test
    fun `an entry that only changes the pending flag by a newer last counts as updated`() {
        val plan = planDtcHistoryMerge(listOf(e(10, 100, false)), ParsedDtcHistory(listOf(e(10, 200, true)), 0))

        assertEquals(1, plan.updatedCodes)
        assertEquals(e(10, 200, true), plan.entries.single())
    }

    @Test
    fun `an existing newer entry stays newer`() {
        val plan = planDtcHistoryMerge(listOf(e(10, 500, false)), ParsedDtcHistory(listOf(e(20, 100, true)), 0))

        assertEquals(0, plan.updatedCodes)
        assertEquals(1, plan.unchangedCodes)
        assertEquals(emptyList<DtcHistoryEntry>(), plan.entries)
    }

    @Test
    fun `190 existing plus 20 new drop the ten oldest over the cap of 200`() {
        val existing = (0 until 190).map { e(1, 1_000L + it, false, "P%04X".format(it)) }
        val incoming = (0 until 20).map { e(1, 5_000L + it, false, "U%04X".format(it)) }

        val plan = planDtcHistoryMerge(existing, ParsedDtcHistory(incoming, 0))

        assertEquals(20, plan.newCodes)
        assertEquals(10, plan.droppedByCap)
    }

    @Test
    fun `nothing is dropped at exactly the cap`() {
        val existing = (0 until 180).map { e(1, 1_000L + it, false, "P%04X".format(it)) }
        val incoming = (0 until 20).map { e(1, 5_000L + it, false, "U%04X".format(it)) }

        assertEquals(0, planDtcHistoryMerge(existing, ParsedDtcHistory(incoming, 0)).droppedByCap)
    }

    @Test
    fun `an empty file changes nothing`() {
        val plan = planDtcHistoryMerge(listOf(e(1, 2, false)), ParsedDtcHistory(emptyList(), 0))

        assertEquals(0, plan.newCodes + plan.updatedCodes + plan.unchangedCodes)
        assertEquals(emptyList<DtcHistoryEntry>(), plan.entries)
    }
}
