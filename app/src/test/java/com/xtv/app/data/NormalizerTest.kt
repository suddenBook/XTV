package com.xtv.app.data

import com.xtv.app.core.model.MediaKind
import com.xtv.app.core.model.PageResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests against sanitised captures of real API responses (see
 * tools/phase0/sanitize_fixtures.py). The upstream can change without warning and CI cannot call the
 * live API — it needs a paid, user-context token — so these fixtures are the only regression net the
 * parser gets. Refresh them whenever the response shape moves.
 */
class NormalizerTest {

    private fun load(name: String): PageResult {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name")
            ?: error("missing fixture: $name")
        return Normalizer.parse(Json.parseToJsonElement(stream.reader().readText()))
    }

    private fun ok(name: String): PageResult.Ok =
        load(name) as? PageResult.Ok ?: error("expected Ok for $name, got ${load(name)}")

    // --- the happy path -----------------------------------------------------------------------

    @Test
    fun `following page yields media items joined to their authors`() {
        val result = ok("following_page1.json")

        assertEquals(99, result.stats.postsSeen)
        assertEquals(99, result.stats.postsRecognised)
        assertFalse("no shape drift expected on a known-good capture", result.stats.shapeDrift)
        assertTrue("expected media on a 70%-density timeline", result.stats.mediaExtracted > 50)

        // Every item must resolve a real display URL and a real author, or the reel shows a blank.
        result.items.forEach {
            assertTrue("empty displayUrl on ${it.id}", it.displayUrl.isNotBlank())
            assertTrue("unresolved author on ${it.id}", it.author.username.isNotBlank())
            assertTrue("bad dimensions on ${it.id}", it.width > 0 && it.height > 0)
        }
    }

    @Test
    fun `media density matches what the Phase 0 probe measured`() {
        // Cross-check between two independent implementations: the Python probe measured 70.7% on
        // this exact capture, and the whole cost model ($0.0071 per useful media item, ~$8.6/month)
        // rests on that ratio. If the Kotlin parser disagrees, one of them is wrong.
        val stats = ok("following_page1.json").stats
        assertEquals(70, stats.postsSeen - stats.postsWithoutMedia)
        assertEquals(0.707f, stats.mediaDensity, 0.005f)
    }

    @Test
    fun `newestPostId is the first post, for use as the next since_id`() {
        val result = ok("following_page1.json")
        assertNotNull(result.newestPostId)
        assertEquals("t0000", result.newestPostId)
    }

    @Test
    fun `item ids are unique and shaped postId colon mediaKey`() {
        val result = ok("following_page1.json")
        assertEquals(result.items.size, result.items.map { it.id }.toSet().size)
        result.items.forEach { assertTrue("malformed id ${it.id}", it.id.matches(Regex("t\\d+:\\d+_m\\d+"))) }
    }

    // --- the things that actually broke during Phase 0 ----------------------------------------

    @Test
    fun `photos take their url from media_url`() {
        val photos = ok("following_page1.json").items.filter { it.kind == MediaKind.PHOTO }
        assertTrue("fixture should contain photos", photos.isNotEmpty())
        // Regression guard for the Phase 0 bug where `url` was missing from media.fields and every
        // photo came back with nowhere to load from.
        photos.forEach { assertTrue("photo url not a photo URL: ${it.displayUrl}", "/media/" in it.displayUrl) }
    }

    @Test
    fun `video picks the highest bitrate mp4 and never the bitrate-less HLS variant`() {
        val videos = ok("following_page1.json").items.filter { it.kind == MediaKind.VIDEO }
        assertTrue("fixture should contain videos", videos.isNotEmpty())
        videos.forEach {
            assertTrue("selected a non-mp4 variant: ${it.displayUrl}", it.displayUrl.endsWith(".mp4"))
            assertFalse("selected the HLS variant: ${it.displayUrl}", it.displayUrl.endsWith(".m3u8"))
        }
        // The sanitiser encodes the chosen bitrate into the URL, so "highest wins" is observable.
        val ladder = videos.map { it.displayUrl.substringAfterLast('/').removeSuffix(".mp4").toInt() }
        assertTrue("expected a high-bitrate rung to win", ladder.any { it > 800_000 })
    }

    @Test
    fun `animated gif parses with an absent duration rather than throwing`() {
        val result = ok("animated_gif.json")
        assertEquals(1, result.items.size)
        val gif = result.items.single()
        assertEquals(MediaKind.GIF, gif.kind)
        // The whole point of the nullable type: this field is simply not there for GIFs.
        assertNull(gif.durationMs)
        assertTrue(gif.displayUrl.endsWith(".mp4"))
    }

    @Test
    fun `videos carry a duration`() {
        val videos = ok("following_page1.json").items.filter { it.kind == MediaKind.VIDEO }
        assertTrue(videos.all { (it.durationMs ?: 0) > 0 })
    }

    // --- multi-media posts --------------------------------------------------------------------

    @Test
    fun `a multi-photo post expands into one item per photo, numbered`() {
        val result = ok("following_page1.json")
        val groups = result.items.groupBy { it.id.substringBefore(':') }
        val multi = groups.values.filter { it.size > 1 }
        assertTrue("Phase 0 capture contained 2- and 4-media posts", multi.isNotEmpty())
        multi.forEach { group ->
            assertEquals(group.map { it.indexInPost }.sorted(), group.indices.toList())
            group.forEach { assertEquals(group.size, it.countInPost) }
        }
    }

    // --- sensitive content --------------------------------------------------------------------

    @Test
    fun `sensitive posts keep their media`() {
        // The finding the whole product depends on: the official API does not strip adult content.
        // If this ever fails against a fresh capture, the premise has changed.
        val result = ok("following_page1.json")
        val sensitive = result.items.filter { it.possiblySensitive }
        assertTrue("fixture should contain sensitive posts", sensitive.isNotEmpty())
        sensitive.forEach { assertTrue("sensitive item lost its URL: ${it.id}", it.displayUrl.isNotBlank()) }
    }

    // --- empty vs error: the distinction that must never collapse ------------------------------

    @Test
    fun `a genuinely empty page is Ok with zero items`() {
        val result = ok("empty_result.json")
        assertEquals(0, result.items.size)
        assertEquals(0, result.stats.postsSeen)
        assertNull(result.newestPostId)
    }

    @Test
    fun `last page with no next token still parses`() {
        val result = ok("likes_page2_end.json")
        assertEquals(0, result.items.size)
    }

    @Test
    fun `credits depleted is PaymentRequired, not an empty page`() {
        // This is the bug the Phase 0 probe shipped with: HTTP 402 counted as "0 items", which reads
        // to the user as "nothing new tonight" while the real cause is an empty wallet.
        val result = load("error_402_credits_depleted.json")
        assertTrue("got $result", result is PageResult.PaymentRequired)
        assertEquals("credits depleted", (result as PageResult.PaymentRequired).detail)
    }

    @Test
    fun `likes page parses as all-video with full density`() {
        val result = ok("likes_page1.json")
        assertEquals(9, result.stats.postsSeen)
        assertEquals(9, result.items.size)
        assertTrue(result.items.all { it.kind == MediaKind.VIDEO })
        assertEquals(0, result.stats.postsWithoutMedia)
    }

    // --- shape drift --------------------------------------------------------------------------

    @Test
    fun `unknown media type is counted as dropped rather than silently ignored`() {
        val json = """
            {"data":[{"id":"t1","text":"x","author_id":"u1","attachments":{"media_keys":["9_m1"]}}],
             "includes":{"media":[{"media_key":"9_m1","type":"hologram","width":1,"height":1}],
                         "users":[{"id":"u1","username":"c","name":"C"}]}}
        """.trimIndent()
        val result = Normalizer.parse(Json.parseToJsonElement(json)) as PageResult.Ok
        assertEquals(0, result.items.size)
        assertEquals(1, result.stats.mediaDropped)
    }

    @Test
    fun `a non-object response is reported as an upstream change`() {
        val result = Normalizer.parse(Json.parseToJsonElement("[]"))
        assertTrue("got $result", result is PageResult.UpstreamChanged)
    }
}
