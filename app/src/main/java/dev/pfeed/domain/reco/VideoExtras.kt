package dev.pfeed.domain.reco

/**
 * What a video's own page says about its timeline, found when it's opened for playback:
 * preview frames for scrubbing (sprite sheets), the positions/acts and where they start, and how
 * much each stretch is watched. Kept for the session.
 */
object VideoExtras {
    /** Sprite sheets of [cols]×[rows] frames, one every [everySec] seconds; [pattern] has {n} for the sheet number. */
    data class Timeline(val pattern: String, val everySec: Int, val cols: Int = 5, val rows: Int = 5) {
        /** The sheet URL and the cell (column, row) showing the moment [ms]. */
        fun at(ms: Long): Triple<String, Int, Int> {
            val frame = (ms / 1000 / everySec.coerceAtLeast(1)).toInt()
            val perSheet = cols * rows
            val sheet = frame / perSheet
            val cell = frame % perSheet
            return Triple(pattern.replace("{n}", sheet.toString()), cell % cols, cell / cols)
        }
    }

    data class Info(
        val timeline: Timeline? = null,
        val chapters: List<Pair<String, Long>> = emptyList(),
        val heat: List<Int> = emptyList(),
        /** What people said under it on its site: (who, what). */
        val comments: List<Pair<String, String>> = emptyList(),
    )

    private val map = object : LinkedHashMap<String, Info>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Info>?) = size > 500
    }

    @Synchronized fun put(key: String, info: Info) { map[key] = info }
    @Synchronized fun of(key: String): Info? = map[key]

    /** "Handjob:255,Missionary:679" → [("Handjob", 255000), ("Missionary", 679000)], in order. */
    fun chapters(actionTags: String?): List<Pair<String, Long>> =
        actionTags.orEmpty().split(',').mapNotNull { part ->
            val name = part.substringBeforeLast(':').trim()
            val sec = part.substringAfterLast(':').trim().toLongOrNull()
            if (name.isEmpty() || sec == null) null else name to sec * 1000
        }.sortedBy { it.second }

    /**
     * Of a video's acts, each with how well it suits you, the first time the best-suited one starts,
     * if it clearly stands out from the video's other acts (and suits you at all).
     */
    fun favourite(scored: List<Pair<Pair<String, Long>, Double>>): Pair<String, Long>? {
        val byName = scored.groupBy { it.first.first.lowercase() }.mapValues { (_, v) -> v.maxOf { it.second } }
        if (byName.size < 2) return null
        val (name, score) = byName.maxByOrNull { it.value } ?: return null
        val rest = byName.filterKeys { it != name }.values.average()
        if (score < FAVOURITE_MIN || score - rest < FAVOURITE_MARGIN) return null
        return scored.filter { it.first.first.lowercase() == name }.minByOrNull { it.first.second }?.first
    }

    private const val FAVOURITE_MIN = 0.05
    /** How much more the favourite must suit you than the video's other acts, to be worth jumping to. */
    private const val FAVOURITE_MARGIN = 0.08

    /** The chapter playing at [ms], if any. */
    fun chapterAt(chapters: List<Pair<String, Long>>, ms: Long): String? = chapters.lastOrNull { it.second <= ms }?.first
}
