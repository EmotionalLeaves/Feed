package dev.pfeed.domain.reco

/**
 * Where commenters point: times written in a video's comments ("12:31 omg", "best part at 1:02:15")
 * are people's own bookmarks. The most-pointed-to time is a crowd "best part" for videos whose site
 * keeps no heatmap.
 */
object CommentMoments {
    private val TIME = Regex("""(?<![\d:])(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?![\d:])""")
    /** Times this close together point to the same bit. */
    private const val SAME_MS = 15_000L

    /** The times pointed to, most-mentioned first (ties: earliest), within the video's length if known. */
    fun of(comments: List<Pair<String, String>>, durationSec: Int?): List<Long> {
        val limit = durationSec?.takeIf { it > 0 }?.let { it * 1000L } ?: Long.MAX_VALUE
        val times = comments.flatMap { (_, text) ->
            TIME.findAll(text).mapNotNull { m ->
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val min = m.groupValues[2].toInt()
                val sec = m.groupValues[3].toInt()
                if (sec >= 60 || (h > 0 && min >= 60)) return@mapNotNull null
                ((h * 3600L + min * 60L + sec) * 1000L).takeIf { it in 1_000 until limit }
            }.distinct().toList() // one vote per comment for each time
        }
        if (times.isEmpty()) return emptyList()
        // Group nearby times; a group's place is its earliest mention.
        val groups = ArrayList<MutableList<Long>>()
        times.sorted().forEach { t -> if (groups.isNotEmpty() && t - groups.last().first() <= SAME_MS) groups.last() += t else groups += mutableListOf(t) }
        return groups.sortedWith(compareByDescending<List<Long>> { it.size }.thenBy { it.first() }).map { it.first() }
    }
}
