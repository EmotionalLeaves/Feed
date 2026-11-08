package dev.pfeed

import dev.pfeed.domain.filter.Blocklist
import dev.pfeed.domain.filter.ContentRating
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.reco.RankWeights
import dev.pfeed.domain.reco.Ranker
import dev.pfeed.domain.reco.Spread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class SpreadAndRatingTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")

    private fun sub(id: String, sub: String) = item(id, source = "reddit", tags = listOf(sub))
    private fun board(id: String, host: String) = item("$host/$id", source = "booru")

    private fun assertNoRuns(list: List<MediaItem>) {
        list.map(Spread::origin).zipWithNext().forEach { (a, b) -> assertTrue("back-to-back $a in ${list.map(Spread::origin)}", a != b) }
        list.windowed(Spread.WINDOW, partialWindows = true).forEach { w ->
            w.groupingBy(Spread::origin).eachCount().forEach { (o, n) -> assertTrue("$o appears $n times in 12", n <= Spread.MAX_PER_ORIGIN) }
        }
    }

    @Test
    fun `latest order never shows one subreddit or site back to back`() {
        // One busy subreddit posted the 20 newest items; others are older.
        val busy = (1..20).map { sub("busy$it", "BDSM").copy(publishedAt = now.minusSeconds(it.toLong())) }
        val others = (1..20).map { i -> sub("o$i", "sub${i % 6}").copy(publishedAt = now.minusSeconds(1000L + i)) }
        val ordered = Spread.order((busy + others).sortedByDescending { it.publishedAt }, emptyList(), 12)
        assertEquals(12, ordered.size)
        assertNoRuns(ordered)
    }

    @Test
    fun `for you spreads origins even when one origin scores best`() {
        // Everything from rule34 matches the user's taste; other sites don't.
        val loved = (1..30).map { board("$it", "rule34.xxx").copy(tags = listOf("rope")) }
        val rest = (1..30).map { i -> board("x$i", "site${i % 6}.net").copy(tags = listOf("other$i")) }
        val ranked = Ranker(RankWeights(exploration = 0.0), Random(1))
            .rank(loved + rest, mapOf("rope" to 10.0), emptyMap(), now, limit = 12)
        assertNoRuns(ranked)
        // Taste still matters: the loved site gets its full share.
        assertEquals(Spread.MAX_PER_ORIGIN, ranked.count { it.id.startsWith("rule34.xxx/") })
    }

    @Test
    fun `spacing carries across batches`() {
        val shown = listOf(board("1", "a.net"), board("2", "b.net"), board("3", "a.net"), board("4", "c.net"), board("5", "a.net"))
        // a.net already has 3 in the window, so it must not open the next batch.
        val next = Spread.order(listOf(board("6", "a.net"), board("7", "d.net")), shown, 1)
        assertEquals("d.net/7", next.single().id)
    }

    @Test
    fun `one gallery post contributes one item per window`() {
        val gallery = (1..10).map { i -> item("coomer.su/99/$i", source = "kemono").copy(pageUrl = "https://coomer.su/post/99") }
        val others = (1..10).map { i -> item("coomer.su/$i/0", source = "kemono").copy(pageUrl = "https://coomer.su/post/$i") }
        val subs = (1..10).map { i -> sub("r$i", "sub$i") }
        val ordered = Spread.order(gallery + others + subs, emptyList(), 12)
        assertEquals(1, ordered.count { it.pageUrl == "https://coomer.su/post/99" })
    }

    @Test
    fun `never stalls when only one origin is available`() {
        val only = (1..5).map { board("$it", "one.net") }
        assertEquals(5, Spread.order(only, emptyList(), 12).size)
    }

    @Test
    fun `safe-rated and unmarked posts are filtered out`() {
        fun rated(r: String) = item("b", source = "booru").copy(extras = mapOf("media" to "m", "rating" to r))
        assertFalse(ContentRating.isAdult(rated("s")))
        assertTrue(ContentRating.isAdult(rated("e")))
        assertTrue(ContentRating.isAdult(rated("q")))
        assertEquals("s", ContentRating.normalize("general"))
        assertEquals("s", ContentRating.normalize("sensitive"))
        assertEquals("e", ContentRating.normalize("Explicit"))
        // HTML-scraped board posts carry the rating as a tag.
        assertFalse(ContentRating.isAdult(item("h", source = "booru", tags = listOf("rope", "rating:safe"))))
        // Mastodon: only media the poster marked sensitive.
        val masto = item("m", source = "mastodon")
        assertFalse(ContentRating.isAdult(masto.copy(extras = mapOf("sensitive" to "false"))))
        assertTrue(ContentRating.isAdult(masto.copy(extras = mapOf("sensitive" to "true"))))
        // Philomena tags its rating.
        assertFalse(ContentRating.isAdult(item("p", source = "philomena", tags = listOf("safe", "pony"))))
        // Unrated adult sources pass.
        assertTrue(ContentRating.isAdult(item("r", source = "redgifs")))
        // And the built-in filter applies even with an empty user blocklist.
        assertFalse(Blocklist.EMPTY.allows(rated("s")))
    }
}
