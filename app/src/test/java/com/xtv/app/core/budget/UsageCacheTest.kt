package com.xtv.app.core.budget

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The home card is rebuilt from ten places; each one used to be its own request to a metered
 * endpoint. These pin the behaviour that stopped that.
 */
class UsageCacheTest {

    private val usage = UsageApi.Usage(posts = 436, capPosts = 2_000_000, resetDay = 26)
    private var clockMs = 1_000L
    private var fetches = 0

    private fun cache(result: () -> UsageApi.Usage? = { usage }) =
        UsageCache(now = { clockMs }, fetch = { fetches++; result() })

    @Test
    fun `no bearer means no request at all`() = runBlocking {
        val subject = cache()
        assertNull(subject.get(null))
        assertNull(subject.get("   "))
        assertEquals(0, fetches)
    }

    @Test
    fun `a second read inside the window is free`() = runBlocking {
        val subject = cache()
        assertEquals(usage, subject.get("bearer"))
        clockMs += UsageCache.SUCCESS_TTL_MS - 1
        assertEquals(usage, subject.get("bearer"))
        assertEquals(1, fetches)
    }

    @Test
    fun `the figure is refetched once the window passes`() = runBlocking {
        val subject = cache()
        subject.get("bearer")
        clockMs += UsageCache.SUCCESS_TTL_MS
        subject.get("bearer")
        assertEquals(2, fetches)
    }

    @Test
    fun `a failure is retried sooner than a success would be`() = runBlocking {
        val subject = cache { null }
        assertNull(subject.get("bearer"))
        clockMs += UsageCache.FAILURE_TTL_MS - 1
        assertNull(subject.get("bearer"))
        assertEquals(1, fetches)

        clockMs += 1
        assertNull(subject.get("bearer"))
        assertEquals(2, fetches)
    }

    @Test
    fun `re-provisioning with a different bearer does not serve the old answer`() = runBlocking {
        val subject = cache()
        subject.get("old-bearer")
        subject.get("new-bearer")
        assertEquals(2, fetches)
    }

    @Test
    fun `buying a reel drops the stored figure`() = runBlocking {
        val subject = cache()
        subject.get("bearer")
        subject.invalidate()
        subject.get("bearer")
        assertEquals(2, fetches)
    }

    @Test
    fun `concurrent readers share one request`() = runBlocking {
        val subject = cache()
        (1..8).map { async { subject.get("bearer") } }.awaitAll()
        assertEquals(1, fetches)
    }
}
