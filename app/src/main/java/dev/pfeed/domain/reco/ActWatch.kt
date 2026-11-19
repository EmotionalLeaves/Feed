package dev.pfeed.domain.reco

/**
 * What you made of each act in a long video whose site names them ("Handjob · 4:15, Missionary · 11:19"):
 * the acts you sat through are ones you like, and the ones you jumped over (once past where you started)
 * are ones you don't. A far finer signal than one verdict on the whole video, which mixes them all.
 */
object ActWatch {
    data class Verdict(val liked: List<String>, val skipped: List<String>)

    /** Kept while a video plays: time on each act, where it started, how far it got. */
    class Tally(val chapters: List<Pair<String, Long>>) {
        val watchedMs = LongArray(chapters.size)
        var startedAtMs = -1L
        var furthestMs = 0L

        fun tick(positionMs: Long, playing: Boolean, stepMs: Long) {
            if (startedAtMs < 0) startedAtMs = positionMs
            furthestMs = maxOf(furthestMs, positionMs)
            if (playing) chapters.indexOfLast { it.second <= positionMs }.takeIf { it >= 0 }?.let { watchedMs[it] += stepMs }
        }

        fun verdict(durationMs: Long): Verdict =
            if (startedAtMs < 0) Verdict(emptyList(), emptyList())
            else judge(chapters, durationMs, watchedMs, startedAtMs, furthestMs)
    }

    /**
     * [chapters] in order (name, start ms); [watchedMs] how long each one played; [startedAtMs] where
     * playback began (acts before it weren't skipped, just never reached); [furthestMs] how far you got.
     */
    fun judge(chapters: List<Pair<String, Long>>, durationMs: Long, watchedMs: LongArray, startedAtMs: Long, furthestMs: Long): Verdict {
        if (chapters.size < 2 || durationMs <= 0 || watchedMs.size != chapters.size) return Verdict(emptyList(), emptyList())
        val liked = mutableSetOf<String>()
        val skipped = mutableSetOf<String>()
        chapters.forEachIndexed { i, (name, start) ->
            val end = chapters.getOrNull(i + 1)?.second ?: durationMs
            val length = end - start
            if (length < MIN_ACT_MS) return@forEachIndexed
            val w = watchedMs[i]
            val key = name.trim().lowercase()
            when {
                w >= minOf(length * SAT_THROUGH, SAT_THROUGH_CAP_MS) -> liked += key
                start >= startedAtMs && furthestMs >= end && w <= minOf(length * PASSED_OVER, PASSED_OVER_CAP_MS) -> skipped += key
            }
        }
        // An act both sat through and jumped over (it comes twice): no verdict on it.
        val both = liked intersect skipped
        return Verdict((liked - both).toList(), (skipped - both).toList())
    }

    private const val MIN_ACT_MS = 30_000L
    private const val SAT_THROUGH = 0.6
    private const val SAT_THROUGH_CAP_MS = 120_000L
    private const val PASSED_OVER = 0.12
    private const val PASSED_OVER_CAP_MS = 12_000L
}
