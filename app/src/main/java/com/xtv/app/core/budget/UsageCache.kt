package com.xtv.app.core.budget

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds X's usage figure for a short while so the home screen can be rebuilt for free.
 *
 * [com.xtv.app.MainActivity] refreshes home state from ten different places — every note, every
 * return from the reel, every error branch. Without this each of them would be its own request to a
 * metered endpoint, and since a failure reverts the label to the estimate wording, exhausting the
 * allowance would look like the bearer had quietly stopped working.
 *
 * Process-scoped via [shared], not remembered in a composition: a system language change recreates
 * the activity, and re-fetching because the user switched to 繁體中文 would be silly.
 *
 * The clock and the fetcher are parameters so this can be tested without a device or a network.
 */
class UsageCache(
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: suspend (String) -> UsageApi.Usage? = UsageApi::postsThisPeriod,
) {

    private val mutex = Mutex()
    private var cached: UsageApi.Usage? = null
    /** Null means "never tried". A sentinel long would overflow the freshness subtraction. */
    private var attemptedAtMs: Long? = null
    private var forBearer: String? = null

    /**
     * The current figure, fetching at most once per window.
     *
     * Holding the lock across the call is what makes it single-flight: a second caller arriving mid
     * request waits and then finds the answer already there, rather than opening its own socket.
     */
    suspend fun get(appOnlyBearer: String?): UsageApi.Usage? {
        val bearer = appOnlyBearer?.takeIf { it.isNotBlank() } ?: return null
        return mutex.withLock {
            // A failure is remembered too, for a shorter time. Retrying on every refresh would mean
            // ten timeouts in a row against a bearer that is simply wrong.
            val ttl = if (cached == null) FAILURE_TTL_MS else SUCCESS_TTL_MS
            val last = attemptedAtMs
            val fresh = bearer == forBearer && last != null && now() - last < ttl
            if (!fresh) {
                cached = fetch(bearer)
                attemptedAtMs = now()
                forBearer = bearer
            }
            cached
        }
    }

    /**
     * Drop the stored figure.
     *
     * Called straight after a reel is bought: that is the one moment the user is looking at the
     * spend line expecting it to move, and a minute-old number would read as the app having lost
     * track of its own money.
     */
    suspend fun invalidate() = mutex.withLock {
        cached = null
        attemptedAtMs = null
        forBearer = null
    }

    companion object {
        const val SUCCESS_TTL_MS = 60_000L
        const val FAILURE_TTL_MS = 15_000L

        val shared = UsageCache()
    }
}
