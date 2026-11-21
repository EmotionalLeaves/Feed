package dev.pfeed.domain.reco

import dev.pfeed.domain.model.MediaItem

/**
 * A search you follow ("redhead pov"): kept with your followed tags as "q:redhead pov". Following
 * searches every source for it, and keeps what has every one of its words in the title or tags.
 */
object FollowedSearch {
    const val PREFIX = "q:"

    fun isOne(tag: String): Boolean = tag.startsWith(PREFIX)

    /** The words to search for. */
    fun query(tag: String): String = tag.removePrefix(PREFIX).replace('_', ' ').trim()

    /** "redhead pov" → its words, each to be found. */
    fun words(tag: String): List<String> = Mentions.padded(query(tag)).trim().split(' ').filter { it.length >= 2 }

    /** Does [item] have every word of the followed search [tag]? */
    fun matches(item: MediaItem, tag: String): Boolean {
        val w = words(tag)
        if (w.isEmpty()) return false
        val text = Mentions.padded(item.title) + item.tags.joinToString("") { Mentions.padded(it.replace('_', ' ')) } + Mentions.padded(item.creator)
        return w.all { text.contains(" $it ") }
    }
}
