package dev.pfeed.domain.reco

import dev.pfeed.domain.model.MediaItem
import kotlin.math.abs
import kotlin.math.max

/**
 * Is this the same video, uploaded to another site? Tube videos are re-posted everywhere under the
 * same (or nearly the same) title, at the same length to the second. So when one copy won't play,
 * another can.
 */
object SameVideo {
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private val NOISE = setOf(
        "the", "a", "an", "and", "with", "in", "on", "of", "to", "her", "his", "my", "for", "at", "by", "is",
        "hd", "4k", "1080p", "720p", "full", "video", "porn", "xxx", "free", "new", "best", "hot", "sexy",
    )

    fun words(title: String?): Set<String> =
        title.orEmpty().lowercase().split(SPLIT).filter { it.length >= 2 && it !in NOISE }.toSet()

    /** A search for the same video: its most telling title words. */
    fun query(item: MediaItem, max: Int = 8): String? {
        val w = title(item)?.lowercase()?.split(SPLIT)?.filter { it.length >= 2 && it !in NOISE }?.distinct().orEmpty()
        return if (w.size < 2) null else w.take(max).joinToString(" ")
    }

    private fun title(item: MediaItem): String? = item.title?.takeIf { it.isNotBlank() }

    /** 0..1: how sure we are that [b] is [a] uploaded elsewhere. */
    fun score(a: MediaItem, b: MediaItem): Double {
        if (a.key == b.key) return 0.0
        val wa = words(a.title); val wb = words(b.title)
        if (wa.size < 2 || wb.isEmpty()) return 0.0
        val jaccard = (wa intersect wb).size.toDouble() / (wa union wb).size
        val da = a.durationSec; val db = b.durationSec
        val length = when {
            da == null || db == null -> 0.5
            abs(da - db) <= max(3, (da * 0.02).toInt()) -> 1.0
            else -> return 0.0 // a different length is a different cut, whatever the title says
        }
        return jaccard * 0.7 + length * 0.3
    }

    const val MATCH = 0.75

    /** The likeliest copies of [item] among [found], best first. */
    fun copies(item: MediaItem, found: List<MediaItem>): List<MediaItem> =
        found.map { it to score(item, it) }.filter { it.second >= MATCH }.sortedByDescending { it.second }.map { it.first }
}
