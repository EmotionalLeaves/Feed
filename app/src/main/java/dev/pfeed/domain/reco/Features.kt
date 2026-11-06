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

    fun normalizeTag(tag: String): String = Blocklist.normalize(tag).replace(' ', '_')

    fun of(item: MediaItem): List<String> {
        val tags = item.tags.asSequence()
            .map(::normalizeTag)
            .filter { it.isNotBlank() && it !in GENERIC }
            .distinct()
            .toList()
        // Boards already describe the post in detail (and their "titles" are just the first tags).
        val words = if (tags.size >= 6) emptyList() else titleWords(item.title)
        return (tags + words).distinct().take(MAX)
    }

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
    fun label(feature: String): String =
        if (feature.startsWith("w:")) "“${feature.removePrefix("w:")}” in titles" else "#${feature.replace('_', ' ')}"
}
