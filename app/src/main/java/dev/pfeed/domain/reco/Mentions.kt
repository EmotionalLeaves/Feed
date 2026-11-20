package dev.pfeed.domain.reco

import dev.pfeed.domain.model.MediaItem

/**
 * Is a person you follow in this video, even when someone else uploaded it? Tube sites credit the
 * uploader or studio as the "creator", but name the performers in the title ("…, Anna Bell Peaks")
 * or among the tags. Only names of two words or more count, so a follow like "amateur" or a one-word
 * handle can't match half the site.
 */
object Mentions {
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** [text] as lowercase words with single spaces, padded so " name " only matches whole words. */
    fun padded(text: String?): String =
        " " + text.orEmpty().lowercase().split(SPLIT).filter { it.isNotEmpty() }.joinToString(" ") + " "

    /** [name] ready to look for, or null if it's too short or one word (too likely to match by chance). */
    fun personName(name: String): String? {
        val n = padded(name.substringBefore('@')).trim()
        return n.takeIf { it.count { c -> c == ' ' } >= 1 && it.length >= MIN_LENGTH }
    }

    /** Which of [names] (already passed through [personName]) are in [item]'s title or tags. */
    fun named(item: MediaItem, names: Collection<String>): List<String> {
        if (names.isEmpty()) return emptyList()
        val text = padded(item.title) + item.tags.joinToString("") { padded(it.replace('_', ' ')) }
        return names.filter { text.contains(" $it ") }
    }

    private const val MIN_LENGTH = 6
}
