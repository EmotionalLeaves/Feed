package dev.pfeed.domain.filter

import dev.pfeed.domain.model.MediaItem

/**
 * Pure blocklist applied at ingest (and re-applied to the live feed whenever it changes).
 * Tags and creators are compared after [normalize]; items are compared by [MediaItem.key].
 */
data class Blocklist(
    val tags: Set<String> = emptySet(),
    val creators: Set<String> = emptySet(),
    val items: Set<String> = emptySet(),
) {
    fun allows(item: MediaItem): Boolean {
        if (!MinorsFilter.allows(item)) return false
        if (!ContentRating.isAdult(item)) return false
        if (item.key in items) return false
        val creator = item.creator?.let(::normalize)
        if (creator != null && creator in creators) return false
        if (tags.isEmpty()) return true
        return item.tags.none { normalize(it) in tags }
    }

    fun filter(items: List<MediaItem>): List<MediaItem> = items.filter(::allows)

    companion object {
        val EMPTY = Blocklist()

        fun normalize(value: String): String =
            value.trim().removePrefix("#").trim().lowercase().replace(Regex("\\s+"), " ")
    }
}
