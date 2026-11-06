package dev.pfeed

import dev.pfeed.domain.reco.AffinityModel
import dev.pfeed.domain.reco.RankWeights
import dev.pfeed.domain.reco.Ranker
import dev.pfeed.domain.reco.Signal
import dev.pfeed.domain.reco.SignalType
import dev.pfeed.domain.reco.SignalWeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.sqrt
import kotlin.random.Random

class RankerTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val noExplore = RankWeights(exploration = 0.0, diversity = 0.0)

    @Test
    fun `liked tag ranks above neutral items`() {
        val items = (1..10).map { item("n$it", tags = listOf("neutral")) } + item("liked", tags = listOf("cats"))
        val ranked = Ranker(noExplore).rank(items, mapOf("cats" to 10.0), emptyMap(), now)
        assertEquals("liked", ranked.first().id)
    }

    @Test
    fun `liking 20 items with a tag raises its share of the top of the feed`() {
        // Simulate the acceptance test: 20 likes on items tagged "cats" (each also carrying one other tag).
        val model = AffinityModel(14.0)
        var cats = 0.0
        val perTag = SignalWeights.delta(Signal(SignalType.LIKE)) / sqrt(2.0)
        repeat(20) { cats = model.update(cats, 0, perTag, 0) }

        val pool = (1..50).map { i -> item("i$i", source = "s${i % 3}", tags = listOf(if (i % 5 == 0) "cats" else "other$i")) }
        val before = Ranker(RankWeights(), Random(1)).rank(pool, emptyMap(), emptyMap(), now).take(10)
        val after = Ranker(RankWeights(), Random(1)).rank(pool, mapOf("cats" to cats), emptyMap(), now).take(10)
        val share = { list: List<dev.pfeed.domain.model.MediaItem> -> list.count { "cats" in it.tags } }
        assertTrue("cats share should rise (before=${share(before)}, after=${share(after)})", share(after) > share(before))
        assertTrue(share(after) >= 7)
    }

    @Test
    fun `creator affinity counts`() {
        val a = item("a", creator = "Fav")
        val b = item("b", creator = "Other")
        val ranked = Ranker(noExplore).rank(listOf(b, a), emptyMap(), mapOf("fav" to 8.0), now)
        assertEquals("a", ranked.first().id)
    }

    @Test
    fun `fresher items win when everything else is equal`() {
        val old = item("old", published = now.minusSeconds(30L * 86400))
        val fresh = item("fresh", published = now.minusSeconds(3600))
        assertEquals("fresh", Ranker(noExplore).rank(listOf(old, fresh), emptyMap(), emptyMap(), now).first().id)
    }

    @Test
    fun `diversity interleaves sources when scores tie`() {
        val items = (1..4).map { item("a$it", source = "A") } + (1..4).map { item("b$it", source = "B") }
        val ranked = Ranker(RankWeights(exploration = 0.0, diversity = 1.0)).rank(items, emptyMap(), emptyMap(), now)
        val sources = ranked.map { it.sourceId }
        // No source appears three times in a row.
        assertTrue(sources.windowed(3).none { w -> w.distinct().size == 1 })
    }

    @Test
    fun `exploration slot fills roughly epsilon share with random picks`() {
        val items = (1..1000).map { item("x$it", tags = listOf(if (it <= 100) "hot" else "cold")) }
        val ranked = Ranker(RankWeights(exploration = 0.10, diversity = 0.0), Random(42))
            .rank(items, mapOf("hot" to 20.0), emptyMap(), now)
        // Without exploration the first 100 would all be "hot". With ε = 10% some cold items get pulled up.
        val coldInTop100 = ranked.take(100).count { "cold" in it.tags }
        assertTrue("expected ~10 cold items in top 100, got $coldInTop100", coldInTop100 in 3..25)
        assertEquals(1000, ranked.size)
        assertEquals(items.toSet(), ranked.toSet())
    }

    @Test
    fun `affinity halves after one half-life`() {
        val model = AffinityModel(14.0)
        val decayed = model.decayed(8.0, 0, (14 * AffinityModel.DAY_MS).toLong())
        assertEquals(4.0, decayed, 1e-9)
    }

    @Test
    fun `limit picks only the best few and leaves the rest for later`() {
        val items = (1..50).map { item("n$it", tags = listOf("neutral")) } + item("liked", tags = listOf("cats"))
        val ranked = Ranker(noExplore).rank(items, mapOf("cats" to 10.0), emptyMap(), now, limit = 12)
        assertEquals(12, ranked.size)
        assertEquals("liked", ranked.first().id)
    }
}
