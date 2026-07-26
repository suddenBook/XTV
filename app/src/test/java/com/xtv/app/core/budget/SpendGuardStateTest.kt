package com.xtv.app.core.budget

import com.xtv.app.core.budget.SpendGuard.Companion.PRICE_PER_POST
import com.xtv.app.core.budget.SpendGuard.Companion.affordable
import com.xtv.app.core.budget.SpendGuard.Companion.usd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The money maths, and the rule that keeps two different meters from being confused. */
class SpendGuardStateTest {

    private fun local(posts: Int, cap: Double = 20.0) =
        SpendGuard.State(postsThisMonth = posts, spentUsd = posts * PRICE_PER_POST, capUsd = cap)

    @Test
    fun `without X's meter the local tally is shown and is not authoritative`() {
        val state = local(posts = 100).mergedWith(null)
        assertFalse(state.authoritative)
        assertNull(state.resetDay)
        assertEquals("$0.50", state.spentText)
    }

    @Test
    fun `X's meter replaces the displayed figure and carries the reset day`() {
        val state = local(posts = 86).mergedWith(UsageApi.Usage(posts = 436, capPosts = 2_000_000, resetDay = 26))
        assertTrue(state.authoritative)
        assertEquals(26, state.resetDay)
        assertEquals("$2.18", state.spentText)
    }

    @Test
    fun `X's project cap never becomes the ceiling`() {
        // 2,000,000 posts is about $10,000. Letting it through would retire the $20 tripwire.
        val state = local(posts = 0, cap = 20.0)
            .mergedWith(UsageApi.Usage(posts = 0, capPosts = 2_000_000, resetDay = 26))
        assertEquals("$20.00", state.capText)
    }

    @Test
    fun `the ceiling is judged on this app's own spending, not the whole project`() {
        // The regression this guards: X's figure is project-wide, so a script sharing the
        // credentials could push it past $20 and disable every reel card in an app that spent 50c.
        val state = local(posts = 100, cap = 20.0)
            .mergedWith(UsageApi.Usage(posts = 100_000, capPosts = null, resetDay = 26))
        assertFalse(state.exceeded)
        assertEquals("$500.00", state.spentText)
    }

    @Test
    fun `exceeded fires exactly at the ceiling`() {
        assertFalse(local(posts = 3_999).exceeded)   // $19.995
        assertTrue(local(posts = 4_000).exceeded)    // $20.00 exactly
    }

    @Test
    fun `dollars format the same regardless of the machine's locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("$2.18", usd(2.18))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `an allowance is trimmed to what is left, never refused outright`() {
        assertEquals(30, affordable(capUsd = 20.0, spentUsd = 0.0, want = 30))
        assertEquals(20, affordable(capUsd = 20.0, spentUsd = 19.9, want = 30))
        assertEquals(0, affordable(capUsd = 20.0, spentUsd = 20.0, want = 30))
    }

    @Test
    fun `an overspent month cannot produce a negative allowance`() {
        assertEquals(0, affordable(capUsd = 20.0, spentUsd = 25.0, want = 30))
    }
}
