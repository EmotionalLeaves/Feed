package dev.pfeed.domain.reco

import kotlin.math.ln

/**
 * Turns one view (how long you stayed, whether a video finished, replays) into a single learning
 * target for [TasteModel].
 *
 * Dwell time is judged against *your own* typical dwell for that kind of media (see
 * [typicalMs]), not a fixed number: someone who flicks through images in two seconds and someone
 * who lingers for ten both get a clear "liked more than usual / less than usual" signal. Staying
 * twice as long as usual is about as positive as half as long is negative.
 */
object Engagement {
    /** Under this, the view is a skip regardless of pace. */
    const val FAST_SKIP_MS = 1_500L

    /** Target for a fast skip. */
    const val SKIP_TARGET = -1.0

    fun target(
        dwellMs: Long,
        isVideo: Boolean,
        durationMs: Long?,
        replays: Int,
        typicalMs: Double,
    ): Double {
        if (dwellMs < FAST_SKIP_MS) return SKIP_TARGET
        val rel = dwellMs / typicalMs.coerceAtLeast(MIN_TYPICAL_MS)
        // log2: half your usual → -1, usual → 0, double → +1.
        var t = (ln(rel) / LN2).coerceIn(-1.2, 1.5) * 0.8
        if (isVideo && durationMs != null && durationMs > 0) {
            val completion = dwellMs.toDouble() / durationMs
            when {
                completion >= 0.9 -> t += 0.5
                // Long videos: sticking with one for half a minute is engagement even if far from the end.
                durationMs > 60_000 && dwellMs > 30_000 -> t += 0.4
            }
        }
        t += 0.6 * replays.coerceIn(0, 3)
        return t.coerceIn(SKIP_TARGET, MAX_TARGET)
    }

    /** New log-domain baseline after observing [dwellMs]; skips don't move it. */
    fun updateBaseline(logTypical: Double, dwellMs: Long): Double {
        if (dwellMs < FAST_SKIP_MS) return logTypical
        val x = ln(dwellMs.coerceIn(500L, 120_000L).toDouble())
        return logTypical + BASELINE_RATE * (x - logTypical)
    }

    const val DEFAULT_IMAGE_MS = 3_500.0
    const val DEFAULT_VIDEO_MS = 9_000.0
    /** Below a like (3.0): a long, engaged view is good, but the explicit heart says more. */
    private const val MAX_TARGET = 2.5
    private const val MIN_TYPICAL_MS = 800.0
    private const val BASELINE_RATE = 0.08
    private val LN2 = ln(2.0)
}
