package com.xtv.app.ui.setup

import com.xtv.app.MissingCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Five states in the domain, two things to say on a television.
 *
 * The collapse is the point, so it is tested rather than assumed — and tested exhaustively, so that
 * adding a sixth credential state fails here instead of silently landing in whichever branch the
 * `when` happens to end with.
 */
class SetupPresentationTest {

    @Test
    fun `everything fixed by re-running the script shares one presentation`() {
        val sameAction = listOf(
            MissingCredential.CLIENT_ID,
            MissingCredential.BEARER,
            MissingCredential.SESSION,
            MissingCredential.PROVISIONING,
        )

        sameAction.forEach {
            assertEquals(
                "$it is fixed from a computer, like the others",
                SetupPresentation.NEEDS_SETUP,
                it.setupPresentation(),
            )
        }
    }

    @Test
    fun `an unreadable envelope is the one state that needs a decision on the device`() {
        assertEquals(
            SetupPresentation.BROKEN,
            MissingCredential.PRIVATE_STATE.setupPresentation(),
        )
    }

    @Test
    fun `every state maps, and every state keeps its own detail line`() {
        val details = MissingCredential.entries.map { it.detailRes() }

        assertEquals(MissingCredential.entries.size, details.size)
        // Precision survives the collapse: which value failed to take is still recoverable, it just
        // is not the headline any more.
        assertEquals(details.size, details.distinct().size)
        details.forEach { assertNotEquals(0, it) }
    }
}
