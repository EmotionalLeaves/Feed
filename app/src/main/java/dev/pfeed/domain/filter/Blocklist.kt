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
    /** Hidden sources: "r/name", "booru:host", "mastodon:host", "kemono:host", "redgifs"… */
    val origins: Set<String> = emptySet(),
    /** Settings → Hide AI-generated art. */
    val hideAi: Boolean = false,
) {
    fun allows(item: MediaItem): Boolean {
        if (!MinorsFilter.allows(item)) return false
        if (!ContentRating.isAdult(item)) return false
        if (hideAi && AiArt.isAi(item)) return false
        // Sites the app has dropped: nothing from them, even posts fetched before they went.
        if (item.sourceId == "tube" && item.id.substringBefore('/').removePrefix("www.").lowercase() in dev.pfeed.source.tube.TubeSite.RETIRED) return false
        if (item.key in items) return false
        if (origins.isNotEmpty() && normalize(dev.pfeed.domain.reco.Spread.origin(item)) in origins) return false
        val creator = item.creator?.let(::normalize)
        if (creator != null && creator in creators) return false
        if (tags.isEmpty()) return true
        if (item.tags.any { dev.pfeed.domain.reco.Features.normalizeTag(it) in canonicalTags }) return false
        // Tags read later from the video's own page count too.
        if (dev.pfeed.domain.reco.PageTags.of(item.key).any { dev.pfeed.domain.reco.Features.normalizeTag(it) in canonicalTags }) return false
        // And the words of the title: tube listings rarely carry tags, but say what it is in the title.
        val title = item.title ?: return true
        val padded = dev.pfeed.domain.reco.Mentions.padded(title)
        return titlePhrases.none { padded.contains(it) }
    }

    /** Hidden tags as whole words or phrases of a title ("big_tits" → " big tits "). */
    private val titlePhrases: List<String> by lazy {
        tags.map { dev.pfeed.domain.reco.Mentions.padded(normalize(it).replace('_', ' ')) }.filter { it.trim().length >= 2 }
    }

    /**
     * Hidden tags in the form posts' tags are compared in: "big tits", "big_tits" and "large breasts"
     * are one tag (see Features.normalizeTag), so hiding one hides them all.
     */
    private val canonicalTags: Set<String> by lazy { tags.mapTo(HashSet()) { dev.pfeed.domain.reco.Features.normalizeTag(it) } }

    fun filter(items: List<MediaItem>): List<MediaItem> = items.filter(::allows)

    companion object {
        val EMPTY = Blocklist()

        fun normalize(value: String): String =
            value.trim().removePrefix("#").trim().lowercase().replace(Regex("\\s+"), " ")
    }
}
