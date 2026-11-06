package dev.pfeed.domain.filter

import dev.pfeed.domain.model.MediaItem

/**
 * Built-in, always-on filter that keeps content tagged as depicting minors out of every feed.
 * It isn't user-configurable and sits underneath the user's own [Blocklist].
 */
object MinorsFilter {
    private val TERMS: Set<String> = setOf(
        "loli", "lolicon", "lolita", "shota", "shotacon", "toddlercon", "cub",
        "child", "children", "kid", "kids", "preteen", "underage", "young", "infant", "toddler",
        "elementary_school", "middle_school", "grade_school", "aged_down",
    )
    private val SPLIT = Regex("[\\s_\\-:/]+")

    /** Booru negative tags appended where the board allows long tag queries, so blocked posts aren't even fetched. */
    val booruNegativeTags: String = "-loli -shota -lolicon -shotacon -toddlercon -child -young -aged_down"

    fun allows(item: MediaItem): Boolean {
        for (raw in item.tags) {
            val tag = raw.trim().lowercase()
            if (tag in TERMS) return false
            if (tag.split(SPLIT).any { it in TERMS && it != "young" && it != "cub" }) return false
        }
        val title = item.title?.lowercase() ?: return true
        return title.split(SPLIT).none { it in TITLE_TERMS }
    }

    private val TITLE_TERMS = setOf("loli", "lolicon", "shota", "shotacon", "underage", "preteen", "toddlercon")
}
