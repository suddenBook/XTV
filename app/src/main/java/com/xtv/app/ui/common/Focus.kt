package com.xtv.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * A [FocusRequester] that keeps asking until the node it is attached to exists.
 *
 * On a TV every screen must hand the remote a starting point, but `requestFocus()` throws while the
 * target has not been placed yet — and on a lazy layout, or a screen entered mid-recomposition,
 * that is the common case rather than the rare one. Six copies of a retry loop had accumulated
 * across the app to work around it, each with slightly different numbers, and one that gave up
 * after a single attempt.
 *
 * The retries here stop at the *first successful call*. The loops they replace re-requested a fixed
 * five times regardless, which meant a viewer who pressed DOWN inside the first 300 ms had focus
 * yanked back out from under them.
 *
 * [keys] re-arms the request — pass whatever identifies "this is now a different screen". [enabled]
 * suppresses it, so a screen does not fight a dialog that has opened on top of it.
 */
@Composable
internal fun rememberInitialFocus(
    vararg keys: Any?,
    enabled: Boolean = true,
): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(enabled, *keys) {
        if (!enabled) return@LaunchedEffect
        repeat(ATTEMPTS) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(RETRY_MS)
        }
    }
    return requester
}

/** Roughly half a second in total: long enough for a slow panel, short enough to feel immediate. */
private const val ATTEMPTS = 8
private const val RETRY_MS = 60L
