package com.xtv.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The launch decision.
 *
 * This exists because the one bug this app has shipped in this area — a freshly provisioned install
 * stranded on the setup screen — lived inside a `LaunchedEffect` where the only way to observe it was
 * to reinstall on a television and look at the panel. It was consequently misdiagnosed twice.
 */
class StartDecisionTest {

    private val id = "client-id"
    private val bearer = "app-only-bearer"

    @Test
    fun `both credentials and a session go home`() {
        assertEquals(Start.Home, decideStart(id, bearer, fixture = null, hasSession = true))
    }

    @Test
    fun `both credentials without a session require secure reprovisioning`() {
        assertEquals(
            Start.NeedsSetup(MissingCredential.SESSION),
            decideStart(id, bearer, fixture = null, hasSession = false),
        )
    }

    @Test
    fun `a missing client id names the client id`() {
        assertEquals(
            Start.NeedsSetup(MissingCredential.CLIENT_ID),
            decideStart(null, bearer, fixture = null, hasSession = true),
        )
    }

    @Test
    fun `a missing bearer names the bearer, not the client id`() {
        // The upgrade case: provisioned before the bearer was required, so a client id and a live
        // session are both present. Saying "no credentials yet" here would be false.
        assertEquals(
            Start.NeedsSetup(MissingCredential.BEARER),
            decideStart(id, null, fixture = null, hasSession = true),
        )
    }

    @Test
    fun `blank and whitespace credentials count as missing`() {
        assertEquals(
            Start.NeedsSetup(MissingCredential.CLIENT_ID),
            decideStart("   ", bearer, fixture = null, hasSession = true),
        )
        assertEquals(
            Start.NeedsSetup(MissingCredential.BEARER),
            decideStart(id, "", fixture = null, hasSession = true),
        )
    }

    @Test
    fun `a fixture run beats every credential check`() {
        // Fixture playback is offline and free. Gating it behind credentials made the documented
        // offline-playback command unusable on any build without a compiled-in client id, which is
        // every published build.
        assertEquals(
            Start.Fixture("dead_links.json"),
            decideStart(null, null, fixture = "dead_links.json", hasSession = false),
        )
    }
}
