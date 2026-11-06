package dev.pfeed.domain.reco

import kotlin.math.pow

enum class SignalType { WATCH, COMPLETE, REPLAY, LIKE, UNLIKE, SAVE, FAST_SKIP, NOT_INTERESTED, FOLLOW }

/** [value] is the watch ratio for WATCH, the replay count for REPLAY, and 1.0 otherwise. */
data class Signal(val type: SignalType, val value: Double = 1.0)

object SignalWeights {
    fun delta(signal: Signal): Double = when (signal.type) {
        // Watching more than ~35% of an item counts as mildly positive, less as mildly negative.
        SignalType.WATCH -> signal.value.coerceIn(0.0, 2.0) - 0.35
        SignalType.COMPLETE -> 1.0
        SignalType.REPLAY -> 1.5 * signal.value.coerceIn(0.0, 3.0)
        SignalType.LIKE -> 3.0
        SignalType.UNLIKE -> -3.0
        SignalType.SAVE -> 4.0
        SignalType.FAST_SKIP -> -1.0
        SignalType.NOT_INTERESTED -> -8.0
        SignalType.FOLLOW -> 5.0
    }
}

/**
 * The taste model: a linear score over an item's [Features], learned online with an error-driven
 * (LMS) rule. Each signal sets a target for how much the user likes the item ([SignalWeights.delta]);
 * every feature moves by a share of the *error* between that target and what the model already
 * predicts.
 *
 * Why error-driven: crediting every tag of a watched post equally makes tags that merely co-occur
 * look liked, until everything scores high and the feed can't tell posts apart (it learns, then
 * forgets). With the error rule, once the model predicts the user's taste correctly it stops pushing,
 * and co-occurring tags get pushed back down by the skipped posts that also carry them. The feature
 * that actually predicts engagement keeps the credit.
 */
object TasteModel {
    /** Fraction of the error corrected per signal. */
    const val STEP = 0.5
    /** Output scale for ranking: prediction 1 ≈ 0.46, 3 (a like) ≈ 0.9. */
    private const val SCORE_SCALE = 2.0

    fun predict(features: List<String>, weights: Map<String, Double>): Double {
        if (features.isEmpty()) return 0.0
        return features.sumOf { weights[it] ?: 0.0 } / kotlin.math.sqrt(features.size.toDouble())
    }

    /** New weights for [features] after one signal with [target]; other features are untouched. */
    fun update(features: List<String>, weights: Map<String, Double>, target: Double): Map<String, Double> {
        if (features.isEmpty()) return emptyMap()
        val step = STEP * (target - predict(features, weights)) / kotlin.math.sqrt(features.size.toDouble())
        return features.associateWith { ((weights[it] ?: 0.0) + step).coerceIn(-AffinityModel.MAX_SCORE, AffinityModel.MAX_SCORE) }
    }

    /** Ranking score in (-1, 1). */
    fun score(features: List<String>, weights: Map<String, Double>): Double =
        kotlin.math.tanh(predict(features, weights) / SCORE_SCALE)
}

/** Exponentially decaying affinity scores. Scores halve every [halfLifeDays] without new signals. */
class AffinityModel(halfLifeDays: Double) {
    private val halfLifeMs = halfLifeDays * DAY_MS

    fun decayed(score: Double, updatedAtMs: Long, nowMs: Long): Double {
        if (halfLifeMs <= 0.0) return score
        val dt = (nowMs - updatedAtMs).coerceAtLeast(0L).toDouble()
        return score * 0.5.pow(dt / halfLifeMs)
    }

    fun update(score: Double, updatedAtMs: Long, delta: Double, nowMs: Long): Double =
        (decayed(score, updatedAtMs, nowMs) + delta).coerceIn(-MAX_SCORE, MAX_SCORE)

    companion object {
        const val DAY_MS = 86_400_000.0
        const val MAX_SCORE = 100.0
    }
}
