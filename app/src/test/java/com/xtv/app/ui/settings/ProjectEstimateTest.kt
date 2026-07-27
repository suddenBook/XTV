package com.xtv.app.ui.settings

import com.xtv.app.core.purchase.RateCard
import com.xtv.app.core.purchase.UsdMicros
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cost figure under X's project usage count.
 *
 * Anchored to a real Developer Console statement rather than to the published rate table, because
 * the two disagree about *quantities*. The statement is the arbiter: it is what gets charged.
 */
class ProjectEstimateTest {

    @Test
    fun `it matches the Console statement it was derived from`() {
        // Observed: 640 Post reads, 21 requests, billed $3.22.
        //
        // 640 x $0.005 = $3.20, and the remaining two cents are two User reads at $0.010 — one
        // `/users/me` per provisioning, and this project was provisioned twice. The statement
        // reconciles to the cent, which is what makes it usable as a model rather than a hint.
        assertEquals("$3.20", 640.projectEstimate().formatUsd())

        val identityLookups = 2L
        val reconciled = 640.projectEstimate() + (RateCard.current().userRead * identityLookups)
        assertEquals("$3.22", reconciled.formatUsd())

        // The count itself carries no identity lookups; those belong to provisioning, not to
        // reading a timeline, so this figure deliberately excludes them.
        assertTrue(640.projectEstimate() < reconciled)
    }

    @Test
    fun `authors and media are not billed per post`() {
        val card = RateCard.current()

        // The model this replaces added a User read per Post and 0.6 Media reads per Post, which
        // would have made a hundred posts $1.80 instead of $0.50.
        assertEquals(card.postRead * 100L, 100.projectEstimate())
        assertEquals("$0.50", 100.projectEstimate().formatUsd())
    }

    @Test
    fun `it scales linearly and survives a realistic project total`() {
        assertEquals("$5.00", 1_000.projectEstimate().formatUsd())
        assertEquals("$62.40", 12_480.projectEstimate().formatUsd())
    }

    @Test
    fun `no usage is no money`() {
        assertEquals(UsdMicros.ZERO, 0.projectEstimate())
        assertEquals(UsdMicros.ZERO, (-5).projectEstimate())
    }
}
