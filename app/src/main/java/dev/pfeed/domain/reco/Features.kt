package dev.pfeed.domain.reco

import dev.pfeed.domain.filter.Blocklist
import dev.pfeed.domain.model.MediaItem

/**
 * What the recommender learns from: every tag on an item (not just the first few), normalised so
 * "Rough Sex", "rough_sex" and "#roughsex"-style variants meet, plus keywords from the title for
 * sources whose tags say little (a Reddit post only carries its subreddit name).
 *
 * Title keywords are prefixed `w:` so they never collide with real tags.
 */
object Features {
    const val MAX = 80
    private const val MAX_TITLE_WORDS = 12

    /** Tags that are on nearly everything and say nothing about taste. */
    private val GENERIC = setOf(
        "nsfw", "sfw", "explicit", "questionable", "safe", "rating:explicit", "rating:questionable", "rating:safe",
        "rating:e", "rating:q", "rating:s", "tagme", "highres", "absurdres", "lowres", "video", "animated", "sound",
        "webm", "mp4", "gif", "jpeg", "png", "image", "photo", "pic", "oc", "original", "nsfw_tag",
    )

    private val STOP = setOf(
        "the", "and", "for", "with", "you", "your", "her", "his", "she", "him", "this", "that", "are", "was", "were",
        "have", "has", "had", "not", "but", "all", "any", "can", "get", "got", "just", "like", "out", "off", "our",
        "who", "what", "when", "how", "why", "from", "into", "onto", "over", "she's", "he's", "i'm", "it's", "its",
        "me", "my", "mine", "they", "them", "their", "would", "could", "should", "will", "one", "two", "some", "more",
        "very", "really", "much", "here", "there", "then", "than", "also", "about", "after", "before", "again", "being",
        "been", "did", "does", "doing", "let", "let's", "make", "made", "want", "wanted", "need", "know", "think",
        "today", "tonight", "now", "new", "first", "time", "day", "back", "too", "see", "look", "looking", "love",
        "f", "m", "oc", "pic", "post", "video", "vid", "gif", "full", "link", "comments", "amp",
    )
    private val SPLIT = Regex("[^\\p{L}\\p{N}'+]+")

    /** True for tags that are on nearly everything (ratings, file formats, "highres"…). */
    fun isGeneric(tag: String): Boolean = normalizeTag(tag) in GENERIC

    fun normalizeTag(tag: String): String = Blocklist.normalize(tag).replace(' ', '_')

    fun of(item: MediaItem): List<String> {
        val tags = item.tags.asSequence()
            .map(::normalizeTag)
            .filter { it.isNotBlank() && it !in GENERIC }
            .distinct()
            .toList()
        // Boards already describe the post in detail (and their "titles" are just the first tags).
        val words = if (tags.size >= 6) emptyList() else titleWords(item.title)
        // Plus where it's from and what it is, so For You learns site and format preferences too
        // (someone who only ever watches videos, or always skips one site, gets less of it).
        return (tags + words).distinct().take(MAX) + meta(item)
    }

    /** "o:{origin}", "type:{video|image|gif|comic}", and for videos of known length "len:{short|medium|long}". */
    fun meta(item: MediaItem): List<String> = listOfNotNull(
        ORIGIN + Spread.origin(item),
        TYPE + when {
            item.isGallery -> "comic"
            else -> item.type.name.lowercase().let { if (it == "embed") "video" else it }
        },
        // Clip or full scene: people usually want one or the other.
        item.durationSec?.takeIf { it > 0 }?.let { d -> LENGTH + when { d < 60 -> "short"; d < 8 * 60 -> "medium"; else -> "long" } },
    )

    fun isMeta(feature: String) = feature.startsWith(ORIGIN) || feature.startsWith(TYPE) || feature.startsWith(LENGTH)

    const val ORIGIN = "o:"
    const val TYPE = "type:"
    const val LENGTH = "len:"

    internal fun titleWords(title: String?): List<String> {
        if (title.isNullOrBlank()) return emptyList()
        return title.lowercase().split(SPLIT).asSequence()
            .map { it.trim('\'', '+') }
            .filter { it.length >= 3 && it !in STOP && !it.all(Char::isDigit) }
            .distinct()
            .take(MAX_TITLE_WORDS)
            .map { "w:$it" }
            .toList()
    }

    /** Readable label for a feature in the "what it has learned" screen. */
    /** "redgifs" → "RedGIFs", "r/gonewild" stays, "rule34.xxx" → "rule34.xxx". */
    private fun prettyOrigin(o: String): String = KNOWN_ORIGINS[o.lowercase()] ?: o

    private val KNOWN_ORIGINS = mapOf(
        "redgifs" to "RedGIFs", "reddit" to "Reddit", "nhentai" to "nhentai", "kemono" to "Coomer",
        "pornhub.com" to "Pornhub", "xvideos.com" to "XVideos", "xnxx.com" to "XNXX", "xhamster.com" to "xHamster",
        "youporn.com" to "YouPorn", "redtube.com" to "RedTube", "eporner.com" to "Eporner", "tnaflix.com" to "TNAFlix",
    )

    fun label(feature: String): String = when {
        feature.startsWith("w:") -> "“${feature.removePrefix("w:")}” in titles"
        feature.startsWith(ORIGIN) -> "Posts from " + prettyOrigin(feature.removePrefix(ORIGIN).substringAfter(':'))
        feature.startsWith(TYPE) -> when (feature.removePrefix(TYPE)) {
            "video" -> "Videos"; "image" -> "Pictures"; "gif" -> "GIFs"; "comic" -> "Comics"; else -> feature
        }
        feature.startsWith(LENGTH) -> when (feature.removePrefix(LENGTH)) {
            "short" -> "Short clips (under a minute)"; "medium" -> "Videos of 1–8 minutes"; else -> "Long videos (8 minutes and up)"
        }
        else -> "#${feature.replace('_', ' ')}"
    }
}
