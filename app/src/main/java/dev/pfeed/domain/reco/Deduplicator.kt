package dev.pfeed.domain.reco

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import kotlin.math.abs

/**
 * Collapses duplicates across sources. Stateful: remembers every item it has let through, so later
 * pages are checked against earlier ones. The first occurrence wins, so feed input in source-priority order.
 *
 * Two items are duplicates when either
 *  - their normalized titles match and their durations agree (within [durationToleranceSec]), or
 *  - their thumbnail perceptual hashes are within [maxHamming] bits and their durations agree.
 */
class Deduplicator(
    private val maxHamming: Int = 6,
    private val durationToleranceSec: Int = 2,
) {
    data class Signature(
        val key: String,
        val title: String,
        val durationSec: Int?,
        val isVideo: Boolean,
        val phash: Long?,
        /** Items from the same post (a gallery) share a page URL and are never duplicates of each other. */
        val pageUrl: String? = null,
    )

    private val keys = HashSet<String>()
    private val byTitle = HashMap<String, MutableList<Signature>>()
    private val hashed = ArrayList<Signature>()

    fun filter(items: List<MediaItem>, hashes: Map<String, Long?> = emptyMap()): List<MediaItem> =
        items.filter { item ->
            val sig = signature(item, hashes[item.key])
            if (isDuplicate(sig)) false else {
                remember(sig)
                true
            }
        }

    fun isDuplicate(sig: Signature): Boolean {
        if (sig.key in keys) return true
        if (sig.title.length >= MIN_TITLE_LENGTH) {
            byTitle[sig.title]?.let { candidates ->
                if (candidates.any { titleDurationMatch(it, sig) }) return true
            }
        }
        val hash = sig.phash ?: return false
        return hashed.any { other ->
            val otherHash = other.phash ?: return@any false
            val distance = PerceptualHash.distance(hash, otherHash)
            when {
                // Two videos with known durations: hash + duration.
                sig.isVideo && other.isVideo && sig.durationSec != null && other.durationSec != null ->
                    distance <= maxHamming && abs(sig.durationSec - other.durationSec) <= durationToleranceSec
                // Missing duration on a video: demand a much closer image match.
                sig.isVideo || other.isVideo -> distance <= STRICT_HAMMING
                else -> distance <= maxHamming
            }
        }
    }

    private fun titleDurationMatch(a: Signature, b: Signature): Boolean {
        if (a.pageUrl != null && a.pageUrl == b.pageUrl) return false
        val da = a.durationSec
        val db = b.durationSec
        return when {
            da != null && db != null -> abs(da - db) <= durationToleranceSec
            da == null && db == null -> a.title.length >= LONG_TITLE_LENGTH
            else -> false
        }
    }

    private fun remember(sig: Signature) {
        keys += sig.key
        if (sig.title.length >= MIN_TITLE_LENGTH) byTitle.getOrPut(sig.title) { mutableListOf() } += sig
        if (sig.phash != null) hashed += sig
    }

    companion object {
        const val MIN_TITLE_LENGTH = 4
        const val LONG_TITLE_LENGTH = 12
        const val STRICT_HAMMING = 3

        private val NOISE = setOf("hd", "fhd", "uhd", "4k", "2160p", "1080p", "720p", "480p", "60fps", "full", "video", "new", "official")

        fun signature(item: MediaItem, phash: Long?) = Signature(
            key = item.key,
            title = normalizeTitle(item.title),
            durationSec = item.durationSec,
            isVideo = item.type == MediaType.VIDEO || item.type == MediaType.EMBED,
            phash = phash,
            pageUrl = item.pageUrl,
        )

        fun normalizeTitle(title: String?): String {
            if (title.isNullOrBlank()) return ""
            return title.lowercase()
                .replace(Regex("[\\[(].*?[\\])]"), " ") // drop [tags] and (notes)
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .split(' ')
                .filter { it.isNotBlank() && it !in NOISE }
                .joinToString(" ")
        }
    }
}
