package dev.pfeed

import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.FeedMode
import dev.pfeed.domain.model.FeedPrefs
import dev.pfeed.source.FeedHealth
import dev.pfeed.source.FeedHealthMemory
import dev.pfeed.source.SourceException
import dev.pfeed.source.net.CachingAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "empty*" entries return nothing; "gone*" answer 404; "slow*" time out; everything else has posts. */
private class HealthFake(private val names: List<String>) : CachingAdapter() {
    override val id = "hf"
    override val displayName = "HF"
    override val requiresAuth = false
    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired
    override suspend fun entries() = names
    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage = when {
        entry.startsWith("empty") -> EntryPage(emptyList(), null)
        entry.startsWith("gone") -> throw SourceException(id, "HTTP 404 from example.org")
        entry.startsWith("slow") -> throw SourceException(id, "Network error from example.org", cause = java.io.IOException("reset"))
        else -> EntryPage((0 until 5).map { item("$entry-$it", source = id) }, null)
    }
}

class FeedHealthTest {
    private val prefs = FeedPrefs(FeedMode.FOR_YOU)

    private fun runOnce(names: List<String>, health: FeedHealth) = runBlocking {
        val a = HealthFake(names).also { it.health = health }
        runCatching { a.fetchFeed(null, prefs) }
    }

    @Test
    fun `dead entries are struck, live and flaky ones are not`() {
        val h = FeedHealthMemory()
        repeat(FeedHealth.STRIKES_TO_DEAD) { runOnce(listOf("a", "empty1", "gone1", "slow1"), h) }
        assertTrue(h.isDead("hf", "empty1"))
        assertTrue(h.isDead("hf", "gone1"))
        assertFalse(h.isDead("hf", "slow1")) // network trouble never counts
        assertFalse(h.isDead("hf", "a"))
    }

    @Test
    fun `nothing is struck when the whole batch fails or comes back empty`() {
        val h = FeedHealthMemory()
        repeat(5) { runOnce(listOf("empty1", "gone1"), h) }
        assertFalse(h.isDead("hf", "empty1"))
        assertFalse(h.isDead("hf", "gone1"))
    }

    @Test
    fun `dead entries get another try after the retry window, and one strike re-kills them`() {
        var now = 0L
        val h = FeedHealthMemory { now }
        repeat(FeedHealth.STRIKES_TO_DEAD) { h.strike("s", "x") }
        assertTrue(h.isDead("s", "x"))
        now += FeedHealth.RETRY_AFTER_MS + 1
        assertFalse(h.isDead("s", "x"))
        h.strike("s", "x")
        assertTrue(h.isDead("s", "x"))
        h.ok("s", "x")
        assertFalse(h.isDead("s", "x"))
    }

    @Test
    fun `only doesn't-exist answers are permanent`() {
        assertTrue(CachingAdapter.isPermanentFailure(SourceException("s", "HTTP 404 from x")))
        assertFalse(CachingAdapter.isPermanentFailure(SourceException("s", "HTTP 403 from x"))) // busy/WAF, not gone
        assertTrue(CachingAdapter.isPermanentFailure(SourceException("s", "HTTP 410 from x")))
        assertFalse(CachingAdapter.isPermanentFailure(SourceException("s", "HTTP 503 from x")))
        assertFalse(CachingAdapter.isPermanentFailure(SourceException("s", "x is rate-limiting, backing off")))
        assertFalse(CachingAdapter.isPermanentFailure(SourceException("s", "Timed out")))
    }
}

class TransientFeedHolderTest {
    @Test
    fun `search results carry their query for the endless continuation`() {
        val h = dev.pfeed.data.repo.TransientFeedHolder()
        val withQuery = h.put(listOf(item("1")), 0, continueWith = "rope")
        val plain = h.put(listOf(item("2")), 0)
        org.junit.Assert.assertEquals("rope", h.continuation(withQuery))
        org.junit.Assert.assertNull(h.continuation(plain))
        // Old lists are evicted along with their continuation.
        repeat(10) { h.put(emptyList(), 0, continueWith = "x$it") }
        org.junit.Assert.assertNull(h.get(withQuery))
        org.junit.Assert.assertNull(h.continuation(withQuery))
    }
}

class QualityScaleTest {
    private fun scored(source: String, score: Long) =
        item("$source-$score", source = source).copy(extras = mapOf("score" to score.toString()))

    @Test
    fun `vote scores are normalised per source`() {
        val r = dev.pfeed.domain.reco.Ranker
        // A popular-but-ordinary RedGIFs clip shouldn't max out the way it did with the board scale.
        assertTrue(r.quality(scored("redgifs", 2_000)) < 0.85)
        assertTrue(r.quality(scored("booru", 300)) >= 0.99)
        // A vote-less post sits near an average scored one, not far below it.
        val unscored = r.quality(item("x", source = "reddit"))
        val median = r.quality(scored("booru", 50))
        assertTrue(kotlin.math.abs(unscored - median) < 0.2)
    }
}

/** Entries are "host/tag"; each fetch records which entries were asked for. */
private class GroupedFake(private val names: List<String>) : CachingAdapter() {
    override val id = "gf"
    override val displayName = "GF"
    override val requiresAuth = false
    override val entriesPerExtend = 6
    override val maxPerGroupPerExtend = 2
    override fun groupOf(entry: String) = entry.substringBefore('/')
    val asked = java.util.Collections.synchronizedList(ArrayList<String>())
    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired
    override suspend fun entries() = names
    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        asked += entry
        return EntryPage((0 until 30).map { item("$entry-$it", source = id) }, null)
    }
}

class GroupedFetchTest {
    @Test
    fun `one round asks each server for at most two feeds`() = runBlocking {
        // 40 feeds on one big site, 2 each on five small ones.
        val names = (1..40).map { "big/$it" } + (1..5).flatMap { h -> listOf("small$h/a", "small$h/b") }
        val a = GroupedFake(names)
        a.fetchFeed(null, FeedPrefs(FeedMode.FOR_YOU))
        val firstRound = a.asked.take(6)
        assertTrue("asked: $firstRound", firstRound.count { it.startsWith("big/") } <= 2)
        assertTrue(firstRound.map { it.substringBefore('/') }.distinct().size >= 3)
    }
}

/** "down/…" entries always fail with a 503; others return posts. */
private class OutageFake(private val names: List<String>) : CachingAdapter() {
    override val id = "of"
    override val displayName = "OF"
    override val requiresAuth = false
    override val entriesPerExtend = 4
    override val maxPerGroupPerExtend = 2
    override fun groupOf(entry: String) = entry.substringBefore('/')
    val asked = java.util.Collections.synchronizedList(ArrayList<String>())
    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired
    override suspend fun entries() = names
    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        asked += entry
        if (entry.startsWith("down/")) throw SourceException(id, "HTTP 503 from down")
        val page = token?.toInt() ?: 0
        return EntryPage((0 until 3).map { item("$entry-$page-$it", source = id) }, (page + 1).toString())
    }
}

class SiteOutageTest {
    @Test
    fun `a site that keeps failing is left alone for a while, then tried again`() = runBlocking {
        var now = 0L
        val a = OutageFake((1..20).map { "down/$it" } + (1..3).flatMap { h -> (1..4).map { "up$h/$it" } })
        a.clock = { now }
        var cursor: String? = null
        repeat(8) { cursor = a.fetchFeed(cursor, FeedPrefs(FeedMode.FOR_YOU)).nextCursor }
        assertTrue(a.restingGroups().contains("down"))
        val before = a.asked.count { it.startsWith("down/") }
        repeat(6) { cursor = a.fetchFeed(cursor, FeedPrefs(FeedMode.FOR_YOU)).nextCursor }
        assertEquals(before, a.asked.count { it.startsWith("down/") })
        now += 11 * 60_000L
        repeat(3) { cursor = a.fetchFeed(cursor, FeedPrefs(FeedMode.FOR_YOU)).nextCursor }
        assertTrue(a.asked.count { it.startsWith("down/") } > before)
    }
}
