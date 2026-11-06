package dev.pfeed.domain.reco

import dev.pfeed.domain.filter.Blocklist
import dev.pfeed.domain.model.MediaItem
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.tanh
import kotlin.random.Random

@Serializable
data class RankWeights(
    val tag: Double = 1.0,
    val creator: Double = 0.8,
    val freshness: Double = 0.4,
    val diversity: Double = 0.5,
    /** Probability that a slot is filled by a random candidate (ε-greedy). */
    val exploration: Double = 0.10,
)

/**
 * score = w_tag·tagAffinity + w_creator·creatorAffinity + w_fresh·freshness + w_div·sourceDiversity,
 * with an ε-greedy exploration slot. Affinities are squashed with tanh so no single signal dominates.
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
        val tags = item.tags.map(Blocklist::normalize).distinct()
        val tagScore = if (tags.isEmpty()) 0.0 else {
            val values = tags.map { squash(tagAffinity[it] ?: 0.0) }
            0.5 * values.max() + 0.5 * values.average()
        }
        val creatorScore = item.creator
            ?.let { squash(creatorAffinity[Blocklist.normalize(it)] ?: 0.0) } ?: 0.0
        val freshness = item.publishedAt?.let {
            val ageDays = Duration.between(it, now).toMinutes().coerceAtLeast(0L) / 1440.0
            exp(-ageDays / FRESHNESS_TAU_DAYS)
        } ?: UNKNOWN_FRESHNESS
        return weights.tag * tagScore + weights.creator * creatorScore + weights.freshness * freshness
    }

    /**
     * Greedy re-ranking. [recentSources] are the source ids of items already shown, so diversity
     * carries across pages.
     */
    fun rank(
        items: List<MediaItem>,
        tagAffinity: Map<String, Double>,
        creatorAffinity: Map<String, Double>,
        now: Instant,
        recentSources: List<String> = emptyList(),
    ): List<MediaItem> {
        val pool = items.map { it to baseScore(it, tagAffinity, creatorAffinity, now) }.toMutableList()
        val window = ArrayDeque(recentSources.takeLast(DIVERSITY_WINDOW))
        val out = ArrayList<MediaItem>(items.size)
        while (pool.isNotEmpty()) {
            val index = if (random.nextDouble() < weights.exploration) {
                random.nextInt(pool.size)
            } else {
                pool.indices.maxBy { i ->
                    pool[i].second + weights.diversity * diversityBonus(pool[i].first.sourceId, window)
                }
            }
            val (item, _) = pool.removeAt(index)
            out += item
            window.addLast(item.sourceId)
            if (window.size > DIVERSITY_WINDOW) window.removeFirst()
        }
        return out
    }

    private fun diversityBonus(sourceId: String, window: Collection<String>): Double =
        1.0 - window.count { it == sourceId }.toDouble() / DIVERSITY_WINDOW

    companion object {
        const val DIVERSITY_WINDOW = 5
        const val FRESHNESS_TAU_DAYS = 7.0
        const val UNKNOWN_FRESHNESS = 0.3
        const val SQUASH_SCALE = 5.0

        fun squash(x: Double): Double = tanh(x / SQUASH_SCALE)
    }
}
