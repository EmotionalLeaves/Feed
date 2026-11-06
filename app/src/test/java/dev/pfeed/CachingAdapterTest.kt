package dev.pfeed

import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.FeedMode
import dev.pfeed.domain.model.FeedPrefs
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.source.SeenFilter
import dev.pfeed.source.SourceException
import dev.pfeed.source.net.CachingAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Fake source: each entry has [pages] remote pages of [perPage] items; entries named "bad*" always fail. */
private class FakeAdapter(
    private val names: List<String>,
    private val pages: Int = 5,
    private val perPage: Int = 10,
) : CachingAdapter() {
    override val id = "fake"
    override val displayName = "Fake"
    override val requiresAuth = false
    val calls = AtomicInteger()

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired
    override suspend fun entries() = names

    override suspend fun fetchEntryPage(entry: String, token: String?): EntryPage {
        calls.incrementAndGet()
        if (entry.startsWith("bad")) throw SourceException(id, "boom")
        val page = token?.toInt() ?: 0
        val items = (0 until perPage).map { i -> item("$entry-$page-$i", source = id, tags = listOf(entry)) }
        return EntryPage(items, next = if (page + 1 < pages) (page + 1).toString() else null)
    }
}

class CachingAdapterTest {
    private val prefs = FeedPrefs(FeedMode.FOR_YOU)

    private fun drain(a: CachingAdapter, maxCalls: Int = 200): List<MediaItem> = runBlocking {
        val out = ArrayList<MediaItem>()
        var cursor: String? = null
        repeat(maxCalls) {
            val page = a.fetchFeed(cursor, prefs)
            out += page.items
            cursor = page.nextCursor ?: return@runBlocking out
        }
        fail("stream never ended within $maxCalls calls")
        out
    }

    @Test
    fun `keeps paging past the first remote page of every entry`() {
        val all = drain(FakeAdapter(listOf("a", "b", "c")))
        // 3 entries × 5 remote pages × 10 items — far more than one page per entry (30).
        assertEquals(150, all.map { it.key }.distinct().size)
        assertEquals("no duplicates handed out", all.size, all.map { it.key }.distinct().size)
    }

    @Test
    fun `first page mixes entries instead of one entry filling it`() = runBlocking {
        val page = FakeAdapter(listOf("a", "b", "c")).fetchFeed(null, prefs)
        assertEquals(20, page.items.size)
        assertEquals(setOf("a", "b", "c"), page.items.flatMap { it.tags }.toSet())
    }

    @Test
    fun `already-seen items are skipped`() = runBlocking {
        val a = FakeAdapter(listOf("a"))
        val seenKeys = (0 until 10).map { item("a-0-$it", source = "fake").key }.toSet()
        a.seen = SeenFilter { it in seenKeys }
        val page = a.fetchFeed(null, prefs)
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.items.none { it.key in seenKeys })
    }

    @Test
    fun `one broken entry does not stop the others`() = runBlocking {
        val page = FakeAdapter(listOf("bad1", "a")).fetchFeed(null, prefs)
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.items.all { "a" in it.tags })
        assertNotNull(page.nextCursor)
    }

    @Test
    fun `all entries failing with nothing loaded surfaces an error`() {
        runBlocking {
            try {
                FakeAdapter(listOf("bad1", "bad2")).fetchFeed(null, prefs)
                fail("expected SourceException")
            } catch (expected: SourceException) {
            }
        }
    }

    @Test
    fun `huge entry lists are fetched a batch at a time`() = runBlocking {
        val a = FakeAdapter((1..130).map { "sub$it" })
        a.fetchFeed(null, prefs)
        assertTrue("fetched ${a.calls.get()} entries for one page", a.calls.get() <= 16)
    }

    @Test
    fun `empty entry list ends immediately`() = runBlocking {
        val page = FakeAdapter(emptyList()).fetchFeed(null, prefs)
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
    }
}
