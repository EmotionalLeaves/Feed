package dev.pfeed.domain.reco

/**
 * Where a video cuts to a new scene, worked out from its site's preview frames (the sprite sheets
 * the scrub bar shows): a frame that looks very unlike the one before is a cut. Lets "Next scene"
 * skip a whole position or setup in one tap, in videos whose site names no acts. Kept per video.
 */
object Scenes {
    /** Per frame: average colour of each cell of a [GRID]×[GRID] grid, RGB, 0..255. */
    const val GRID = 3

    /**
     * Cut times (ms) from [frames] taken [everyMs] apart: frames far more unlike their neighbour than
     * this video's usual frame-to-frame change, at least [minGapMs] apart (the stronger cut wins).
     */
    fun cuts(frames: List<FloatArray>, everyMs: Long, minGapMs: Long = MIN_GAP_MS): List<Long> {
        if (frames.size < 6 || everyMs <= 0) return emptyList()
        val d = DoubleArray(frames.size)
        for (i in 1 until frames.size) d[i] = distance(frames[i - 1], frames[i])
        val usual = d.drop(1).sorted().let { it[it.size / 2] }
        val bar = maxOf(usual * RELATIVE, ABSOLUTE)
        val peaks = (1 until frames.size).filter { i ->
            d[i] >= bar && d[i] >= d[i - 1] && (i == frames.size - 1 || d[i] >= d[i + 1])
        }
        // Strongest first; drop any too close to one already kept.
        val kept = ArrayList<Int>()
        for (i in peaks.sortedByDescending { d[it] }) {
            if (kept.none { kotlin.math.abs(it - i) * everyMs < minGapMs }) kept += i
        }
        return kept.sorted().map { it * everyMs }
    }

    private fun distance(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += kotlin.math.abs(a[i] - b[i])
        return s / a.size
    }

    /** The first cut after [positionMs] (a moment past it, so tapping twice moves on twice). */
    fun next(cuts: List<Long>, positionMs: Long): Long? = cuts.firstOrNull { it > positionMs + 1_500 }

    private val map = object : LinkedHashMap<String, List<Long>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Long>>?) = size > 300
    }
    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Bumps when a video's scenes are found. */
    val changes: kotlinx.coroutines.flow.StateFlow<Int> = _changes

    @Synchronized fun put(key: String, cuts: List<Long>) { map[key] = cuts; _changes.value++ }
    @Synchronized fun of(key: String): List<Long>? = map[key]

    private const val MIN_GAP_MS = 25_000L
    private const val RELATIVE = 3.0
    private const val ABSOLUTE = 22.0
}
