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
