package dev.pfeed.sim

/*
 * A simulated world and simulated people, to measure For You before it ships. Shared by
 * TasteEngineTest (and the same as the harness the engine was tuned with).
 *
 * The world: 24 themes of 16 tags each (popular tags of a theme show up more), two sparse-tag
 * sources (Reddit-like: one or two tags) and four rich ones (boards), a little cross-theme noise.
 * People: like one to three themes with different strengths, dislike one, tire of seeing the same
 * theme over and over, stay longer the more they enjoy something, get distracted (12% of the time
 * they flick past even what they'd like, 5% they linger on anything), like ~30% of what they love,
 * and say "Not interested" to 20% of what they dislike.
 */
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.reco.Engagement
import dev.pfeed.domain.reco.Features
import dev.pfeed.domain.reco.RankWeights
import dev.pfeed.domain.reco.Ranker
import dev.pfeed.domain.reco.Signal
import dev.pfeed.domain.reco.SignalType
import dev.pfeed.domain.reco.SignalWeights
import dev.pfeed.domain.reco.TasteEngine
import dev.pfeed.domain.reco.TasteModel
import java.time.Instant
import kotlin.math.*
import kotlin.random.Random

/**
 * A world with structure: tags belong to themes (rope, ties, knots… are one theme), posts are
 * mostly about one theme, sources tag sparsely or richly. Users like a few themes with different
 * weights, dislike one, and get bored of the same theme over and over (satiation).
 */
internal class World(val rng: Random, val themes: Int = 24, val perTheme: Int = 16) {
    val themeTags = List(themes) { t -> List(perTheme) { i -> "t${t}_$i" } }
    val generic = List(40) { "g$it" }
    private var next = 0

    fun post(theme: Int = rng.nextInt(themes), extraTag: String? = null): Post {
        next++
        val source = rng.nextInt(6)
        // Some sources tag richly (boards), some sparsely (reddit-like: 1–2 tags).
        val nTags = if (source < 2) rng.nextInt(1, 3) else rng.nextInt(4, 11)
        val tags = LinkedHashSet<String>()
        // Popular tags of a theme show up more (Zipf within the theme).
        while (tags.size < nTags) {
            val r = rng.nextDouble()
            if (r < 0.75) tags += themeTags[theme][zipf(perTheme)]
            else if (r < 0.85) tags += themeTags[rng.nextInt(themes)][zipf(perTheme)] // a second theme, a little
            else tags += generic[rng.nextInt(generic.size)]
        }
        if (extraTag != null) tags += extraTag
        val type = when (rng.nextInt(10)) { in 0..4 -> MediaType.VIDEO; in 5..7 -> MediaType.IMAGE; else -> MediaType.GIF }
        val score = if (rng.nextBoolean()) rng.nextInt(0, 400).toString() else null
        val item = MediaItem(
            id = "p$next", sourceId = "s$source", type = type, title = null, creator = "c${rng.nextInt(400)}",
            tags = tags.toList(), durationSec = if (type == MediaType.VIDEO) rng.nextInt(10, 1200) else null,
            thumbnailUrl = null, pageUrl = "", publishedAt = null,
            extras = buildMap { put("media", "m"); score?.let { put("score", it) } },
        )
        return Post(item, theme, (score?.toDouble() ?: 100.0) / 400.0)
    }

    private fun zipf(n: Int): Int {
        // P(i) ∝ 1/(i+1)
        val h = (1..n).sumOf { 1.0 / it }
        var x = rng.nextDouble() * h
        for (i in 0 until n) { x -= 1.0 / (i + 1); if (x <= 0) return i }
        return n - 1
    }

    /** A search for [tag]: posts that carry it (from the theme it belongs to). */
    fun search(tag: String, n: Int): List<Post> {
        val theme = themeTags.indexOfFirst { tag in it }
        return List(n) { post(if (theme >= 0) theme else rng.nextInt(themes), extraTag = tag) }
    }
}

internal class Post(val item: MediaItem, val theme: Int, val quality: Double)

/** A simulated person: likes some themes (weights), dislikes one, gets bored of repetition. */
private object Debug { val q = java.util.concurrent.atomic.DoubleAdder(); val same = java.util.concurrent.atomic.DoubleAdder(); val n = java.util.concurrent.atomic.LongAdder(); val base = java.util.concurrent.atomic.DoubleAdder()
    fun reset() { q.reset(); same.reset(); n.reset(); base.reset() }
    fun line() = "avgQ %.3f avgSame %.2f avgBase %.3f".format(q.sum() / n.sum(), same.sum() / n.sum(), base.sum() / n.sum()) }

internal class User(val likes: Map<Int, Double>, val dislike: Int?, val satiation: Boolean = true) {
    private val recentThemes = ArrayDeque<Int>()
    /** How much they enjoy [p] right now (0..1+), given what they've just seen. */
    fun enjoy(p: Post): Double {
        var e = likes[p.theme] ?: 0.0
        if (p.theme == dislike) e = -1.0
        Debug.q.add(p.quality); Debug.n.increment(); Debug.base.add(e); Debug.same.add(recentThemes.takeLast(6).count { it == p.theme }.toDouble())
        if (satiation && e > 0) {
            val same = recentThemes.takeLast(6).count { it == p.theme }
            e *= (1.0 - 0.07 * same).coerceAtLeast(0.55)
        }
        e += 0.15 * p.quality
        return e
    }
    fun saw(p: Post) { recentThemes.addLast(p.theme); if (recentThemes.size > 20) recentThemes.removeFirst() }
}

/** One recommender under test: ranks a batch from a pool and learns from what the user did. */
internal interface Recommender {
    fun batch(pool: List<MediaItem>, n: Int): List<MediaItem>
    fun learn(item: MediaItem, target: Double, liked: Boolean)
    fun observe(pool: List<MediaItem>) {}
    /** Tags it would search for (the feed's interest searches). */
    fun interests(): List<String>
}

internal val NOW: Instant = Instant.parse("2026-11-01T12:00:00Z")

internal class Baseline(seed: Int) : Recommender {
    val aff = HashMap<String, Double>()
    private val ranker = Ranker(RankWeights(), Random(seed))
    private val recent = ArrayList<MediaItem>()
    private val recentSources = ArrayList<String>()
    override fun batch(pool: List<MediaItem>, n: Int): List<MediaItem> =
        ranker.rank(pool, aff, emptyMap(), NOW, recentSources, limit = n, recent = recent).also { b -> recent += b; recentSources += b.map { it.sourceId } }
    override fun learn(item: MediaItem, target: Double, liked: Boolean) { aff += TasteModel.update(Features.of(item), aff, target) }
    override fun interests(): List<String> = aff.entries.filter { it.value >= 0.6 && !Features.isMeta(it.key) }.sortedByDescending { it.value }.take(2).map { it.key }
}

internal data class Run(val enjoy: List<Double>, val hits: List<Double>, val coverage: Double, val shares: Map<Int, Double>)

internal fun simulate(make: (Int) -> Recommender, user: User, seed: Int, rounds: Int = 30, batch: Int = 12, shiftAt: Int? = null, shiftTo: User? = null): Run {
    val rng = Random(seed * 7919 + 1)
    val world = World(rng)
    val rec = make(seed)
    val pool = ArrayList<Post>()
    val byKey = HashMap<String, Post>()
    val pooledAt = HashMap<String, Int>()
    fun add(p: Post, r: Int) { pool += p; byKey[p.item.key] = p; pooledAt[p.item.key] = r }
    repeat(150) { add(world.post(), 0) }
    var u = user
    val enjoyPerRound = ArrayList<Double>(); val hitsPerRound = ArrayList<Double>()
    val themeCount = HashMap<Int, Int>()
    var logTypical = ln(Engagement.DEFAULT_IMAGE_MS)
    for (round in 0 until rounds) {
        if (shiftAt != null && round == shiftAt && shiftTo != null) u = shiftTo
        repeat(60) { add(world.post(), round) }
        pool.removeAll { round - (pooledAt[it.item.key] ?: round) > 10 }
        rec.interests().forEach { t -> world.search(t, 20).forEach { add(it, round) } }
        rec.observe(pool.map { it.item })
        val b = rec.batch(pool.map { it.item }, batch)
        val picked = b.mapNotNull { byKey[it.key] }
        pool.removeAll(picked.toSet())
        var e = 0.0; var h = 0
        for (p in picked) {
            val joy = u.enjoy(p)
            u.saw(p)
            e += joy
            val liked = joy > 0.35
            if (liked) h++
            if (round >= rounds / 2 && joy > 0.35) themeCount[p.theme] = (themeCount[p.theme] ?: 0) + 1
            val noise = rng.nextDouble()
            val dwell = when {
                joy < 0 -> rng.nextLong(250, 800) // put off: gone at once
                noise < 0.12 -> rng.nextLong(300, 1_400) // distracted: skips even what they'd like
                noise < 0.17 -> rng.nextLong(6_000, 12_000) // lingers on anything, phone down
                // The more they enjoy it, the longer they stay (give or take).
                joy > 0.1 -> (1_500 + joy * 13_000 * (0.7 + 0.6 * rng.nextDouble())).toLong()
                rng.nextDouble() < 0.7 -> rng.nextLong(300, 1_400)
                else -> rng.nextLong(1_500, 3_000)
            }
            val explicitLike = joy > 0.6 && rng.nextDouble() < 0.3
            val t = if (joy < 0 && rng.nextDouble() < 0.2) SignalWeights.delta(Signal(SignalType.NOT_INTERESTED))
            else if (explicitLike) SignalWeights.delta(Signal(SignalType.LIKE))
            else Engagement.target(dwell, isVideo = false, durationMs = null, replays = 0, typicalMs = exp(logTypical))
            rec.learn(p.item, t, explicitLike)
            logTypical = Engagement.updateBaseline(logTypical, dwell)
        }
        enjoyPerRound += e / picked.size.coerceAtLeast(1); hitsPerRound += h.toDouble() / picked.size.coerceAtLeast(1)
    }
    // Coverage: how well the liked hits in the second half match the user's own weights (1 = exactly).
    val total = themeCount.values.sum().toDouble().coerceAtLeast(1.0)
    val shares = u.likes.keys.associateWith { (themeCount[it] ?: 0) / total }
    val wsum = u.likes.values.sum()
    val coverage = 1.0 - 0.5 * u.likes.entries.sumOf { (t, w) -> abs(w / wsum - (shares[t] ?: 0.0)) }
    return Run(enjoyPerRound, hitsPerRound, coverage, shares)
}


internal class EngineRec(seed: Int, val weights: RankWeights = RankWeights()) : Recommender {
    val aff = HashMap<String, Double>()
    val engine = TasteEngine()
    private val ranker = Ranker(weights, Random(seed))
    private val recent = ArrayList<MediaItem>()
    private val recentSources = ArrayList<String>()
    private val observed = HashSet<String>()
    override fun observe(pool: List<MediaItem>) { engine.observe(pool.filter { observed.add(it.key) }) }
    override fun batch(pool: List<MediaItem>, n: Int): List<MediaItem> =
        ranker.rank(pool, aff, emptyMap(), NOW, recentSources, limit = n, recent = recent, engine = engine).also { b -> recent += b; recentSources += b.map { it.sourceId } }
    override fun learn(item: MediaItem, target: Double, liked: Boolean) {
        aff += TasteModel.update(Features.of(item), aff, target)
        engine.learn(item, target)
    }
    override fun interests(): List<String> = engine.searchTags(aff, 3).ifEmpty {
        aff.entries.filter { it.value >= 0.6 && !Features.isMeta(it.key) }.sortedByDescending { it.value }.take(2).map { it.key }
    }
}

