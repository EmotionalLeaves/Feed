package dev.pfeed

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.reco.Engagement
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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * Plays the For You loop forward with a simulated user, mirroring the app: the same TasteModel,
 * Engagement targets (dwell relative to the user's own pace, likes not overwritten by the dwell),
 * Ranker and batch size as FeedSession, plus FeedSession's pool behaviour: fresh candidates every
 * load, stale ones aged out, and searches for the strongest learned interests.
 */
class AlgorithmSimulationTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")

    private class World(val rng: Random, val target: String, val baseRate: Double) {
        private var next = 0
        private val vocab = (1..60).map { "tag$it" }
        /** A board-style post: 10 tags, [target] with probability [baseRate] (or always, if [with] is it). */
        fun post(with: String? = null): MediaItem {
            next++
            val tags = vocab.shuffled(rng).take(9).toMutableList()
            tags += if (rng.nextDouble() < baseRate) target else vocab.random(rng)
            if (with != null) tags += with
            val score = if (rng.nextBoolean()) rng.nextInt(0, 400).toString() else null
            return item("p$next", source = "s${next % 3}", tags = tags.distinct().sorted(), type = MediaType.IMAGE)
                .copy(extras = buildMap { put("media", "m"); score?.let { put("score", it) } })
        }
    }

    /**
     * Runs [rounds] batches. The user lingers 6–14 s on [target] posts (liking ~30%) and flicks past
     * the rest (60% under 1.5 s, the others 1.5–3.5 s). Returns the share of [target] per batch.
     */
    private fun simulate(target: String, rounds: Int, seed: Int, baseRate: Double = 0.10, interests: Boolean = true): List<Double> {
        val rng = Random(seed)
        val world = World(rng, target, baseRate)
        val pool = MutableList(150) { world.post() }
        val pooledAt = HashMap<String, Int>().apply { pool.forEach { put(it.key, 0) } }
        val aff = HashMap<String, Double>()
        var logTypical = ln(Engagement.DEFAULT_IMAGE_MS)
        val ranker = Ranker(RankWeights(), Random(seed))
        val recentSources = ArrayList<String>()
        val recent = ArrayList<MediaItem>()
        return List(rounds) { round ->
            // FeedSession: fresh candidates every load; candidates passed over for 10 loads age out.
            repeat(60) { world.post().also { pool += it; pooledAt[it.key] = round } }
            pool.removeAll { round - (pooledAt[it.key] ?: round) > 10 }
            if (interests) {
                aff.entries.filter { it.value >= 0.6 }.sortedByDescending { it.value }.take(2).forEach { (q, _) ->
                    repeat(20) { world.post(with = q).also { pool += it; pooledAt[it.key] = round } }
                }
            }
            val batch = ranker.rank(pool, aff, emptyMap(), now, recentSources, limit = 12, recent = recent)
            pool.removeAll(batch.toSet())
            batch.forEach { item ->
                recentSources += item.sourceId
                recent += item
                val liked = target in item.tags
                val dwell = when {
                    liked -> rng.nextLong(6_000, 14_000)
                    rng.nextDouble() < 0.6 -> rng.nextLong(300, 1_400)
                    else -> rng.nextLong(1_500, 3_500)
                }
                val typical = exp(logTypical)
                val t = if (liked && rng.nextDouble() < 0.3) {
                    SignalWeights.delta(Signal(SignalType.LIKE)) // explicit: the dwell isn't recorded on top
                } else {
                    Engagement.target(dwell, isVideo = false, durationMs = null, replays = 0, typicalMs = typical)
                }
                aff += TasteModel.update(Features.of(item), aff, t)
                logTypical = Engagement.updateBaseline(logTypical, dwell)
            }
            batch.count { target in it.tags } / batch.size.toDouble()
        }
    }

    @Test
    fun `feed converges on what the user lingers on, within a few batches`() {
        // "zz_rope" sorts last alphabetically: the old "first 15 tags" rule could never learn it.
        val shares = (1..8).map { seed -> simulate("zz_rope", rounds = 14, seed = seed) }
        val first = shares.map { it[0] }.average()
        val late = shares.map { it.takeLast(4).average() }.average()
        assertTrue("first batch share $first should be near the base rate", first < 0.35)
        assertTrue("after a few batches most of the feed should match (got $late)", late >= 0.7)
    }

    @Test
    fun `feed does not forget, it stays locked on rather than drifting back to random`() {
        val shares = (1..8).map { seed -> simulate("zz_rope", rounds = 14, seed = seed) }
        shares.forEach { s -> assertTrue("a run fell back to ${s.takeLast(4)}", s.takeLast(4).average() >= 0.6) }
    }

    @Test
    fun `a rare taste is found by searching for it`() {
        // Only 3% of general posts match: ranking alone can't surface enough of it.
        val with = (1..8).map { simulate("zz_rope", rounds = 14, seed = it, baseRate = 0.03) }.map { it.takeLast(4).average() }.average()
        val without = (1..8).map { simulate("zz_rope", rounds = 14, seed = it, baseRate = 0.03, interests = false) }.map { it.takeLast(4).average() }.average()
        assertTrue("with interest searches the feed should mostly match (got $with)", with >= 0.6)
        assertTrue("interest searches should clearly beat ranking alone ($with vs $without)", with > without + 0.3)
    }

    @Test
    fun `dwell is judged against the user's own pace`() {
        val fast = 2_000.0
        val slow = 10_000.0
        // 4 s is twice a fast swiper's usual: positive. For a slow viewer it's well under usual: negative.
        assertTrue(Engagement.target(4_000, false, null, 0, fast) > 0.5)
        assertTrue(Engagement.target(4_000, false, null, 0, slow) < -0.5)
        assertEquals(Engagement.SKIP_TARGET, Engagement.target(900, false, null, 0, fast), 0.0)
    }

    @Test
    fun `finishing or replaying a video counts for more`() {
        val plain = Engagement.target(9_000, true, 30_000, 0, 9_000.0)
        val finished = Engagement.target(9_000, true, 9_500, 0, 9_000.0)
        val replayed = Engagement.target(9_000, true, 4_000, 2, 9_000.0)
        assertTrue(finished > plain)
        assertTrue(replayed > finished)
    }

    @Test
    fun `a near-duplicate of what was just shown is pushed down`() {
        val shown = item("s", tags = listOf("a", "b", "c", "d", "e"))
        val twin = item("t", tags = listOf("a", "b", "c", "d", "e"), source = "x")
        val other = item("o", tags = listOf("f", "g", "h", "i", "j"), source = "x")
        val ranked = Ranker(RankWeights(exploration = 0.0, diversity = 0.0, freshness = 0.0, quality = 0.0))
            .rank(listOf(twin, other), emptyMap(), emptyMap(), now, recent = listOf(shown))
        assertEquals("o", ranked.first().id)
    }

    @Test
    fun `higher community score ranks first when taste is equal`() {
        val low = item("low", tags = listOf("a")).copy(extras = mapOf("media" to "m", "score" to "2"))
        val high = item("high", tags = listOf("b")).copy(extras = mapOf("media" to "m", "score" to "250"))
        val ranked = Ranker(RankWeights(exploration = 0.0, diversity = 0.0, similarity = 0.0)).rank(listOf(low, high), emptyMap(), emptyMap(), now)
        assertEquals("high", ranked.first().id)
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
