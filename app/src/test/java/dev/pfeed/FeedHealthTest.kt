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
        assertTrue(CachingAdapter.isPermanentFailure(SourceException("s", "HTTP 403 from x")))
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
