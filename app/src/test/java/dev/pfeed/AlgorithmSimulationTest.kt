package dev.pfeed

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.reco.Features
import dev.pfeed.domain.reco.RankWeights
import dev.pfeed.domain.reco.Ranker
import dev.pfeed.domain.reco.Signal
import dev.pfeed.domain.reco.SignalType
import dev.pfeed.domain.reco.SignalWeights
import dev.pfeed.domain.reco.TasteModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Plays the For You loop forward with a simulated user, using the same learning rule as
 * SignalRepository (TasteModel over every feature) and
 * the same Ranker and batch size as FeedSession. Checks the feed actually learns.
 */
class AlgorithmSimulationTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val vocab = (1..60).map { "tag$it" }

    /** Board-style items: 10 random tags each, ~15% carry [target], sorted alphabetically like boards do. */
    private fun pool(rng: Random, n: Int, target: String): MutableList<MediaItem> = MutableList(n) { i ->
        val tags = vocab.shuffled(rng).take(9).toMutableList()
        if (rng.nextDouble() < 0.15) tags += target else tags += vocab.random(rng)
        item("p$i", source = "s${i % 4}", tags = tags.sorted(), type = MediaType.IMAGE, published = now.minusSeconds(rng.nextLong(0, 7 * 86400)))
    }

    private class Learner {
        val aff = HashMap<String, Double>()
        fun record(item: MediaItem, s: Signal) {
            aff += TasteModel.update(Features.of(item), aff, SignalWeights.delta(s))
        }
    }

    /** Runs [rounds] batches; the user watches + likes [target] items and skips the rest. Returns target share per round. */
    private fun simulate(target: String, rounds: Int, seed: Int, alsoDislike: String? = null): List<Double> {
        val rng = Random(seed)
        val candidates = pool(rng, 1200, target)
        val learner = Learner()
        val ranker = Ranker(RankWeights(), Random(seed))
        val recent = ArrayList<String>()
        return List(rounds) {
            val batch = ranker.rank(candidates, learner.aff, emptyMap(), now, recent, limit = 12)
            candidates.removeAll(batch.toSet())
            batch.forEach { item ->
                recent += item.sourceId
                when {
                    alsoDislike != null && alsoDislike in item.tags -> learner.record(item, Signal(SignalType.FAST_SKIP))
                    target in item.tags -> {
                        learner.record(item, Signal(SignalType.WATCH, 1.2))
                        learner.record(item, Signal(SignalType.COMPLETE))
                        if (rng.nextDouble() < 0.4) learner.record(item, Signal(SignalType.LIKE))
                    }
                    else -> learner.record(item, Signal(SignalType.FAST_SKIP))
                }
            }
            batch.count { target in it.tags } / batch.size.toDouble()
        }
    }

    @Test
    fun `feed converges on what the user watches, within a few batches`() {
        // "zz_rope" sorts last alphabetically: the old "first 15 tags" rule could never learn it.
        val shares = (1..8).map { seed -> simulate("zz_rope", rounds = 12, seed = seed) }
        val first = shares.map { it[0] }.average()
        val late = shares.map { it.takeLast(4).average() }.average()
        assertTrue("first batch share $first should be near the base rate (~15-25%)", first < 0.45)
        assertTrue("after a few batches most of the feed should match (got $late)", late >= 0.6)
    }

    @Test
    fun `feed does not forget, it stays locked on rather than drifting back to random`() {
        val shares = (1..8).map { seed -> simulate("zz_rope", rounds = 12, seed = seed) }
        shares.forEach { s -> assertTrue("a run fell back to ${s.takeLast(4)}", s.takeLast(4).average() >= 0.5) }
    }

    @Test
    fun `a disliked tag pulls items down even when they carry a liked one`() {
        val liked = item("a", tags = listOf("rope", "x1", "x2", "x3", "x4", "x5"))
        val likedButDisliked = item("b", tags = listOf("rope", "feet", "x1", "x2", "x3", "x4"))
        val aff = mapOf("rope" to 8.0, "feet" to -8.0)
        val ranked = Ranker(RankWeights(exploration = 0.0, diversity = 0.0)).rank(listOf(likedButDisliked, liked), aff, emptyMap(), now)
        assertEquals("a", ranked.first().id)
    }

    @Test
    fun `learns from every tag on heavily tagged posts`() {
        val tags = (1..120).map { "t%03d".format(it) }
        assertEquals(Features.MAX, Features.of(item("x", tags = tags)).size)
        assertTrue("t080" in Features.of(item("x", tags = tags)))
    }

    @Test
    fun `reddit-style posts learn from title words`() {
        val f = Features.of(item("r", title = "Tied up and gagged for the weekend", tags = listOf("bdsm", "nsfw")))
        assertTrue("bdsm" in f)
        assertTrue("nsfw is noise on every Reddit post" , "nsfw" !in f)
        assertTrue("w:tied" in f && "w:gagged" in f && "w:weekend" in f)
        assertTrue("stop words dropped", "w:the" !in f && "w:and" !in f)
    }

    @Test
    fun `tag spellings from different sources meet`() {
        assertEquals(Features.normalizeTag("rough_sex"), Features.normalizeTag("Rough Sex"))
        assertEquals(Features.normalizeTag("#rough_sex"), Features.normalizeTag("rough sex"))
    }
}
