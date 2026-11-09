package dev.pfeed.domain.filter

import dev.pfeed.domain.model.MediaItem

/**
 * Built-in, always-on filter that keeps content tagged or titled as depicting minors out of every
 * feed. It isn't user-configurable and sits underneath the user's own [Blocklist].
 */
object MinorsFilter {
    private val TERMS: Set<String> = setOf(
        "loli", "lolicon", "lolita", "shota", "shotacon", "toddlercon", "cub", "jailbait",
        "child", "children", "kid", "kids", "preteen", "pre-teen", "underage", "under_age", "infant", "toddler",
        "minor", "minors", "pedo", "pedophile", "pedophilia", "cp",
        "elementary_school", "middle_school", "grade_school", "junior_high", "junior_high_school",
        "high_school", "highschool", "high_school_student", "schoolgirl_(young)", "young", "aged_down",
    )
    private val SPLIT = Regex("[\\s_\\-:/]+")

    /** Single words inside a compound tag that only ever mean "a minor" (not "young", "cub", "minor"). */
    private val COMPOUND_WORDS = setOf(
        "loli", "lolicon", "lolita", "shota", "shotacon", "toddlercon", "jailbait", "child", "children",
        "kid", "kids", "preteen", "underage", "infant", "toddler", "pedo", "pedophile",
    )

    /** "16yo", "15 y/o", "14-year-old", "13 years old"… — any stated age under 18. */
    private val UNDER_18_AGE = Regex(
        """(?<![0-9])(1[0-7]|[1-9])\s*(?:yo\b|y/o|y\.o\.?|yrs?\s*old\b|years?[\s-]*old\b)""",
        RegexOption.IGNORE_CASE,
    )

    /** Booru negative tags appended where the board allows long tag queries, so blocked posts aren't even fetched. */
    val booruNegativeTags: String = "-loli -shota -lolicon -shotacon -toddlercon -child -young -aged_down"

    fun allows(item: MediaItem): Boolean {
        for (raw in item.tags) {
            val tag = raw.trim().lowercase()
            if (tag in TERMS) return false
            if (tag.split(SPLIT).any { it in COMPOUND_WORDS }) return false
            if (UNDER_18_AGE.containsMatchIn(tag)) return false
        }
        val title = item.title?.lowercase() ?: return true
        if (UNDER_18_AGE.containsMatchIn(title)) return false
        return title.split(SPLIT).none { it in TITLE_TERMS }
    }

    private val TITLE_TERMS = setOf(
        "loli", "lolicon", "shota", "shotacon", "underage", "preteen", "toddlercon", "jailbait", "pedo",
        "highschool", "middleschool",
    )
}
