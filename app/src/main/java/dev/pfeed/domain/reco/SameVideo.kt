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

    /**
     * A longer cut of the same scene among [found]: many sites keep a 10–15 minute cut of a studio
     * scene that runs 30–40 minutes in full elsewhere. Same title (word for word, give or take the
     * site's own additions), and clearly longer; of those, the longest (the fullest cut).
     */
    fun longer(item: MediaItem, found: List<MediaItem>): MediaItem? {
        val wa = words(item.title).takeIf { it.size >= 3 } ?: return null
        val da = item.durationSec ?: return null
        return found.asSequence()
            .filter { it.key != item.key && it.type == item.type }
            .mapNotNull { b ->
                val db = b.durationSec ?: return@mapNotNull null
                if (db < da * LONGER_RATIO + LONGER_MIN_GAIN_SEC || db > LONGER_MAX_SEC) return@mapNotNull null
                val wb = words(b.title).takeIf { it.size >= 3 } ?: return@mapNotNull null
                val shared = (wa intersect wb).size.toDouble()
                val overlap = shared / minOf(wa.size, wb.size)
                val jaccard = shared / (wa union wb).size
                if (overlap < LONGER_OVERLAP || jaccard < LONGER_JACCARD) null else Triple(b, overlap + jaccard, db)
            }
            .maxWithOrNull(compareBy<Triple<MediaItem, Double, Int>> { it.third }.thenBy { it.second })?.first
    }

    private const val LONGER_RATIO = 1.25
    private const val LONGER_MIN_GAIN_SEC = 90
    private const val LONGER_MAX_SEC = 3 * 3600
    private const val LONGER_OVERLAP = 0.8
    private const val LONGER_JACCARD = 0.5

    /**
     * A sharper copy of the same upload among [found]: same scene at the same length on another
     * site, at 720p or better and clearly sharper than the [heightNow] playing (1080p preferred
     * over 4K, which is heavy for a phone; ties go to the closest match).
     */
    fun sharper(item: MediaItem, found: List<MediaItem>, heightNow: Int): MediaItem? {
        val want = max(SHARPER_MIN, heightNow + SHARPER_GAIN)
        val site = item.id.substringBefore('/')
        return copies(item, found).asSequence()
            .filter { it.type == item.type && it.id.substringBefore('/') != site }
            .mapNotNull { b -> b.extras["height"]?.toIntOrNull()?.takeIf { it >= want }?.let { b to minOf(it, 1080) } }
            .maxByOrNull { it.second }?.first
    }

    private const val SHARPER_MIN = 720
    private const val SHARPER_GAIN = 240

    /** The likeliest copies of [item] among [found], best first. */
    fun copies(item: MediaItem, found: List<MediaItem>): List<MediaItem> =
        found.map { it to score(item, it) }.filter { it.second >= MATCH }.sortedByDescending { it.second }.map { it.first }
}
