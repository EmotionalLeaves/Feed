package dev.pfeed.domain.reco

/**
 * Where a long video gets good: from the site's own record of which parts people watch and rewatch
 * (Pornhub's "hotspots", one count per stretch of the video). The opening stretch is left out
 * (everyone starts there), the counts are smoothed, and a peak only counts if it stands well above
 * the rest. Found when the video's page is read for playback; kept for the session.
 */
object BestPart {
    private val map = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 1000
    }

    @Synchronized fun put(key: String, atMs: Long) { map[key] = atMs }
    @Synchronized fun of(key: String): Long? = map[key]

    /** The start of the most-watched stretch of a [durationSec]-long video, in ms; null if nothing stands out. */
    fun from(hotspots: List<Int>, durationSec: Int): Long? {
        val n = hotspots.size
        if (n < 10 || durationSec < MIN_SECONDS) return null
        val smooth = DoubleArray(n) { i -> (maxOf(0, i - 1)..minOf(n - 1, i + 1)).map { hotspots[it].toDouble() }.average() }
        val from = (n * SKIP_START).toInt().coerceAtLeast(1)
        val to = (n * (1 - SKIP_END)).toInt().coerceAtMost(n - 1)
        if (to <= from) return null
        val window = (from..to)
        val peak = window.maxByOrNull { smooth[it] } ?: return null
        val median = window.map { smooth[it] }.sorted()[(to - from) / 2]
        if (median <= 0 || smooth[peak] < median * STANDOUT) return null
        // Start a few seconds before the stretch, so it doesn't begin mid-moment.
        val segMs = durationSec * 1000L / n
        return (peak * segMs - LEAD_MS).coerceAtLeast(0L)
    }

    private const val MIN_SECONDS = 4 * 60
    private const val SKIP_START = 0.08
    private const val SKIP_END = 0.03
    private const val STANDOUT = 1.25
    private const val LEAD_MS = 4_000L
}
