package dev.pfeed.domain.reco

import dev.pfeed.domain.filter.Blocklist
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh
import kotlin.random.Random

@Serializable
data class RankWeights(
    val tag: Double = 1.0,
    val creator: Double = 0.6,
    /** Age matters little for this kind of content; mostly a tie-breaker. */
    val freshness: Double = 0.15,
    /** Light nudge to mix sources (Reddit / boards / Mastodon). */
    val diversity: Double = 0.15,
    /** Penalty for looking like what was just shown (shared tags), so the feed doesn't repeat itself. */
    val similarity: Double = 0.45,
    /** Community vote score where the source has one (image boards). */
    val quality: Double = 0.3,
    /**
     * Bonus for a subreddit / site / server you haven't seen lately: the spacing rules only stop
     * runs, they don't pull in places you never see, so with thousands of feeds the same dozen
     * origins can still take every slot. This spreads the feed across many more of them.
     */
    val novelty: Double = 0.2,
    /** Probability that a slot is filled by a random candidate (ε-greedy). */
    val exploration: Double = 0.10,
) {
    /** The defaults before v2.2, which over-weighted freshness and forced sources to alternate. */
    fun isPre22Default(): Boolean =
        tag == 1.0 && creator == 0.8 && freshness == 0.4 && diversity == 0.5 && exploration == 0.10
}

/**
 * score = w_tag·taste(features) + w_creator·creatorAffinity + w_quality·votes + w_fresh·freshness,
 * then, while picking greedily: + w_div·sourceVariety − w_sim·similarityToRecent, with an ε-greedy
 * exploration slot.
 */
class Ranker(
    private val weights: RankWeights,
    private val random: Random = Random.Default,
) {
    fun baseScore(
        item: MediaItem,
        tagAffinity: Map<String, Double>,
        creatorAffinity: Map<String, Double>,
        now: Instant,
        qualityValue: Double = quality(item),
    ): Double {
        val x = components(item, tagAffinity, creatorAffinity, now, qualityValue)
        return weights.tag * x[0] + weights.creator * x[1] + weights.quality * x[2] + weights.freshness * x[3] + formatPrior(item)
    }

    /** The parts of an item's score, before weighting: taste by tags, the creator, community votes, freshness. */
    fun components(
        item: MediaItem,
        tagAffinity: Map<String, Double>,
        creatorAffinity: Map<String, Double>,
        now: Instant,
        qualityValue: Double = quality(item),
    ): DoubleArray {
        val tagScore = TasteModel.score(Features.of(item), tagAffinity)
        val creatorScore = item.creator
            ?.let { squash(creatorAffinity[Blocklist.normalize(it)] ?: 0.0) } ?: 0.0
        val freshness = item.publishedAt?.let {
            val ageDays = Duration.between(it, now).toMinutes().coerceAtLeast(0L) / 1440.0
            exp(-ageDays / FRESHNESS_TAU_DAYS)
        } ?: UNKNOWN_FRESHNESS
        return doubleArrayOf(tagScore, creatorScore, qualityValue, freshness)
    }

    /**
     * Greedy re-ranking. [recentSources] are the source ids of items already shown and [recent] the
     * items themselves, so variety carries across batches. [limit] stops after that many picks: the
     * feed draws a small batch from a large candidate pool and re-ranks the rest later with fresher
     * affinities.
     */
    fun rank(
        items: List<MediaItem>,
        tagAffinity: Map<String, Double>,
        creatorAffinity: Map<String, Double>,
        now: Instant,
        recentSources: List<String> = emptyList(),
        limit: Int = items.size,
        recent: List<MediaItem> = emptyList(),
        allowForcedRun: Boolean = true,
        /** Origins (see [Spread.origin]) of the last [NOVELTY_WINDOW] or so items shown. */
        recentOrigins: Collection<String> = emptySet(),
        /**
         * The taste engine (interests on a map of meaning, the current mood). With it, every slot
         * serves one of your interests in turn, and exploration looks just past what you know
         * instead of anywhere at random.
         */
        engine: TasteEngine? = null,
    ): List<MediaItem> {
        val seenOrigins = HashSet(recentOrigins)
        val relative = relativeQuality(items)
        // With the engine, every part's weight is also tuned to you (see [TasteEngine.tune]): someone
        // who always stays for the top-voted posts gets votes counted more, someone who doesn't, less.
        val base = doubleArrayOf(weights.tag, weights.creator, weights.quality, weights.freshness, SEMANTIC, MOOD, -AVOID)
        val w = engine?.let { e -> DoubleArray(base.size) { i -> base[i] * e.tune[i] } } ?: base
        val pool = items.map {
            val q = relative[it.key] ?: UNKNOWN_QUALITY
            val c = components(it, tagAffinity, creatorAffinity, now, q)
            val vec = engine?.vectorOf(it)
            val x = DoubleArray(TasteEngine.PARTS)
            for (i in c.indices) x[i] = c[i]
            if (engine != null && vec != null) {
                x[4] = engine.interestScore(vec); x[5] = engine.moodScore(vec); x[6] = engine.aversion(vec)
            }
            var score = formatPrior(it)
            for (i in x.indices) score += w[i] * x[i]
            Candidate(it, score, signature(it), vec, x[4], q, x[6], x)
        }.toMutableList()
        val window = ArrayDeque(recentSources.takeLast(DIVERSITY_WINDOW))
        val recentSigs = ArrayDeque(recent.takeLast(SIMILARITY_WINDOW).map(::signature))
        // With the engine: "the same kind of thing" by meaning too, not only by shared tags, over a
        // slightly longer stretch (people tire of one theme even when the tags differ).
        val recentVecs = ArrayDeque(if (engine != null) recent.takeLast(SEMANTIC_WINDOW).mapNotNull { engine.vectorOf(it) } else emptyList())
        fun repetition(c: Candidate): Double {
            val tags = maxSimilarity(c.sig, recentSigs)
            val v = c.vec ?: return tags
            var sem = 0.0
            recentVecs.forEachIndexed { i, r ->
                // The nearer in the past, the more it counts.
                val w = (i + 1).toDouble() / recentVecs.size
                sem = kotlin.math.max(sem, TasteEngine.cos(v, r) * w)
            }
            return kotlin.math.max(tags, REPEAT * sem)
        }
        val out = ArrayList<MediaItem>(limit.coerceIn(0, items.size))
        // Hard spacing (no runs from one subreddit/site/creator/post) decides which candidates are
        // eligible; the score picks among them. When [allowForcedRun] is false, a pick that would
        // break a spacing rule is refused (index -1) so the batch stops short rather than running
        // one origin; the feed resumes next load once more varied candidates arrive.
        val minLevel = if (allowForcedRun) 0 else Spread.ALL_RULES
        val spread = Spread(recent)
        var slot = 0
        val exploration = engine?.explorationRate(weights.exploration) ?: weights.exploration
        while (pool.isNotEmpty() && out.size < limit) {
            val explore = random.nextDouble() < exploration
            val index = if (explore && engine != null && engine.interests.isNotEmpty()) {
                // Exploring with a map: something good you haven't shown a taste for yet (far from
                // every interest, decent quality), rather than any random post.
                spread.pick(pool, { it.item }, minLevel) { c ->
                    (1.0 - c.fit) * 0.6 + c.quality * 0.4 + random.nextDouble() * 0.5 -
                        1.5 * c.aversion - weights.similarity * repetition(c)
                }
            } else if (explore) {
                spread.pickRandom(pool, { it.item }, random, minLevel)
            } else {
                // This slot's interest (taking turns, in proportion to how much you like each).
                val focus = engine?.pickInterest(random)
                spread.pick(pool, { it.item }, minLevel) { c ->
                    c.score +
                        (if (focus != null && c.vec != null) SLOT * TasteEngine.cos(focus.centroid, c.vec) else 0.0) +
                        weights.diversity * diversityBonus(c.item.sourceId, window) +
                        (if (Spread.origin(c.item) in seenOrigins) 0.0 else weights.novelty) -
                        weights.similarity * repetition(c)
                }
            }
            slot++
            if (index < 0) break
            val picked = pool.removeAt(index)
            // Remember what it was shown for, so the reaction to it can tune the weights.
            if (engine != null && picked.parts != null) engine.shown(picked.item.key, picked.parts)
            seenOrigins += Spread.origin(picked.item)
            spread.add(picked.item)
            out += picked.item
            window.addLast(picked.item.sourceId)
            if (window.size > DIVERSITY_WINDOW) window.removeFirst()
            recentSigs.addLast(picked.sig)
            if (recentSigs.size > SIMILARITY_WINDOW) recentSigs.removeFirst()
            picked.vec?.let { recentVecs.addLast(it); if (recentVecs.size > SEMANTIC_WINDOW) recentVecs.removeFirst() }
        }
        return out
    }

    /**
     * A small lean toward video before anything is learned: measured on a fresh install, For You
     * served 35% video and 20% comics, which is backwards for most people opening a porn app. It's
     * small next to taste (±0.45), so the learned type:video / type:comic preference takes over fast.
     */
    private fun formatPrior(item: MediaItem): Double = when {
        item.isGallery -> -0.06
        item.type == MediaType.VIDEO || item.type == MediaType.EMBED -> 0.12
        item.type == MediaType.GIF -> 0.04
        else -> 0.0
    }

    private class Candidate(
        val item: MediaItem,
        val score: Double,
        val sig: Set<String>,
        val vec: FloatArray? = null,
        val fit: Double = 0.0,
        val quality: Double = UNKNOWN_QUALITY,
        val aversion: Double = 0.0,
        val parts: DoubleArray? = null,
    )

    /**
     * Quality as a percentile within each source's own candidates. Vote scales differ wildly
     * (a Derpibooru post has thousands, a good Rule34 post a few hundred, Reddit RSS none), and on an
     * absolute scale the big-number sites maxed out the bonus and outranked everything (that is how
     * My Little Pony took over). Within a source, the best still rise; across sources it's even.
     */
    internal fun relativeQuality(items: List<MediaItem>): Map<String, Double> {
        val out = HashMap<String, Double>(items.size)
        items.groupBy { it.sourceId }.forEach { (_, group) ->
            val scored = group.mapNotNull { i -> i.extras["score"]?.toDoubleOrNull()?.let { i.key to it } }.sortedBy { it.second }
            if (scored.size < MIN_FOR_PERCENTILE) {
                scored.forEach { (k, _) -> out[k] = quality(group.first { it.key == k }) }
            } else {
                val n = scored.size - 1
                scored.forEachIndexed { rank, (k, _) -> out[k] = 0.1 + 0.9 * rank / n }
            }
        }
        return out
    }

    private fun diversityBonus(sourceId: String, window: Collection<String>): Double =
        1.0 - window.count { it == sourceId }.toDouble() / DIVERSITY_WINDOW

    companion object {
        /** Weight of the engine's interest match, the mood, and a disliked direction. */
        const val SEMANTIC = 0.9
        const val MOOD = 0.35
        const val AVOID = 0.8
        /** Bonus toward the interest a slot is serving. */
        const val SLOT = 0.6
        /** Semantic repetition: how many recent posts, and how much a near-twin in meaning counts. */
        const val SEMANTIC_WINDOW = 4
        const val REPEAT = 1.2
        const val DIVERSITY_WINDOW = 5
        /** How many recently shown origins count as "seen lately" for the novelty bonus. */
        const val NOVELTY_WINDOW = 80
        /** How many just-shown items a candidate is compared with for similarity. */
        const val SIMILARITY_WINDOW = 3
        const val FRESHNESS_TAU_DAYS = 7.0
        /** No date (tube and comic listings): treated as middling, not as old. */
        const val UNKNOWN_FRESHNESS = 0.45
        const val SQUASH_SCALE = 5.0
        /** Vote score treated as "top quality" (1.0). Typical good board posts score 20–300. */
        private const val QUALITY_REF = 300.0
        /**
         * Items with no vote data (Reddit RSS, Mastodon, Coomer) sit at about the median of scored
         * ones (a 50-vote board post ≈ 0.69, so 0.35 was a quiet penalty on every vote-less source).
         */
        const val UNKNOWN_QUALITY = 0.55
        private const val SIG_FEATURES = 20
        /** Fewer scored candidates than this from one source: use the absolute scale instead. */
        private const val MIN_FOR_PERCENTILE = 5

        fun squash(x: Double): Double = tanh(x / SQUASH_SCALE)

        /**
         * Votes normalised per source: board scores (tens to hundreds) and RedGIFs likes (hundreds to
         * tens of thousands) aren't on the same scale, and with one shared reference every RedGIFs
         * clip maxed out and outranked everything else.
         */
        fun quality(item: MediaItem): Double {
            val score = item.extras["score"]?.toDoubleOrNull() ?: return UNKNOWN_QUALITY
            if (score <= 0) return 0.0
            val ref = QUALITY_REFS[item.sourceId] ?: QUALITY_REF
            return (ln(1 + score) / ln(1 + ref)).coerceIn(0.0, 1.0)
        }

        private val QUALITY_REFS = mapOf("redgifs" to 20_000.0, "philomena" to 4_000.0, "booru" to QUALITY_REF, "nhentai" to 8_000.0)

        /** The features that make an item "the same kind of thing" as another. */
        internal fun signature(item: MediaItem): Set<String> = Features.of(item).filterNot(Features::isMeta).take(SIG_FEATURES).toHashSet()

        /** Overlap coefficient with the most similar recent item: 1 = same tags, 0 = nothing shared. */
        internal fun maxSimilarity(sig: Set<String>, recent: Collection<Set<String>>): Double {
            if (sig.isEmpty()) return 0.0
            var best = 0.0
            for (r in recent) {
                if (r.isEmpty()) continue
                val shared = sig.count { it in r }
                val sim = shared.toDouble() / minOf(sig.size, r.size)
                if (sim > best) best = sim
            }
            return best
        }
    }
}
