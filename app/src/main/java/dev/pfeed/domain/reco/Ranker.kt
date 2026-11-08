package dev.pfeed.domain.reco

import dev.pfeed.domain.filter.Blocklist
import dev.pfeed.domain.model.MediaItem
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
    ): Double {
        val tagScore = TasteModel.score(Features.of(item), tagAffinity)
        val creatorScore = item.creator
            ?.let { squash(creatorAffinity[Blocklist.normalize(it)] ?: 0.0) } ?: 0.0
        val freshness = item.publishedAt?.let {
            val ageDays = Duration.between(it, now).toMinutes().coerceAtLeast(0L) / 1440.0
            exp(-ageDays / FRESHNESS_TAU_DAYS)
        } ?: UNKNOWN_FRESHNESS
        return weights.tag * tagScore + weights.creator * creatorScore +
            weights.quality * quality(item) + weights.freshness * freshness
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
    ): List<MediaItem> {
        val pool = items.map { Candidate(it, baseScore(it, tagAffinity, creatorAffinity, now), signature(it)) }.toMutableList()
        val window = ArrayDeque(recentSources.takeLast(DIVERSITY_WINDOW))
        val recentSigs = ArrayDeque(recent.takeLast(SIMILARITY_WINDOW).map(::signature))
        val out = ArrayList<MediaItem>(limit.coerceIn(0, items.size))
        // Hard spacing (no runs from one subreddit/site/creator/post) decides which candidates are
        // eligible; the score picks among them. When [allowForcedRun] is false, a pick that would
        // break a spacing rule is refused (index -1) so the batch stops short rather than running
        // one origin; the feed resumes next load once more varied candidates arrive.
        val minLevel = if (allowForcedRun) 0 else Spread.ALL_RULES
        val spread = Spread(recent)
        while (pool.isNotEmpty() && out.size < limit) {
            val index = if (random.nextDouble() < weights.exploration) {
                spread.pickRandom(pool, { it.item }, random, minLevel)
            } else {
                spread.pick(pool, { it.item }, minLevel) { c ->
                    c.score +
                        weights.diversity * diversityBonus(c.item.sourceId, window) -
                        weights.similarity * maxSimilarity(c.sig, recentSigs)
                }
            }
            if (index < 0) break
            val picked = pool.removeAt(index)
            spread.add(picked.item)
            out += picked.item
            window.addLast(picked.item.sourceId)
            if (window.size > DIVERSITY_WINDOW) window.removeFirst()
            recentSigs.addLast(picked.sig)
            if (recentSigs.size > SIMILARITY_WINDOW) recentSigs.removeFirst()
        }
        return out
    }

    private class Candidate(val item: MediaItem, val score: Double, val sig: Set<String>)

    private fun diversityBonus(sourceId: String, window: Collection<String>): Double =
        1.0 - window.count { it == sourceId }.toDouble() / DIVERSITY_WINDOW

    companion object {
        const val DIVERSITY_WINDOW = 5
        /** How many just-shown items a candidate is compared with for similarity. */
        const val SIMILARITY_WINDOW = 3
        const val FRESHNESS_TAU_DAYS = 7.0
        const val UNKNOWN_FRESHNESS = 0.3
        const val SQUASH_SCALE = 5.0
        /** Vote score treated as "top quality" (1.0). Typical good board posts score 20–300. */
        private const val QUALITY_REF = 300.0
        /** Items with no vote data (Reddit RSS, Mastodon) sit at a neutral middle. */
        const val UNKNOWN_QUALITY = 0.35
        private const val SIG_FEATURES = 20

        fun squash(x: Double): Double = tanh(x / SQUASH_SCALE)

        fun quality(item: MediaItem): Double {
            val score = item.extras["score"]?.toDoubleOrNull() ?: return UNKNOWN_QUALITY
            if (score <= 0) return 0.0
            return (ln(1 + score) / ln(1 + QUALITY_REF)).coerceIn(0.0, 1.0)
        }

        /** The features that make an item "the same kind of thing" as another. */
        internal fun signature(item: MediaItem): Set<String> = Features.of(item).take(SIG_FEATURES).toHashSet()

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
