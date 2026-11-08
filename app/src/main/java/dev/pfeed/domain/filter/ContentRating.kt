package dev.pfeed.domain.filter

import dev.pfeed.domain.model.MediaItem

/**
 * Built-in filter that keeps non-adult content out of the app. It uses what the sources themselves
 * say about each post:
 *
 *  - image boards rate every post (explicit / questionable / safe, or general / sensitive on newer
 *    boards); the parsers store it as `extras["rating"]` = "e", "q" or "s";
 *  - posts scraped from a board's HTML listing carry `rating:…` among their tags;
 *  - Philomena boards (Derpibooru, Furbooru) put the rating in the tags (safe, suggestive, …);
 *  - Mastodon posts are kept only when the poster marked the media sensitive (`extras["sensitive"]`).
 *
 * Explicit and questionable (nudity, lingerie, softcore) stay; only content rated safe is removed.
 * Sources with no rating at all (Reddit NSFW subs, RedGIFs, Coomer) are adult by nature and pass.
 */
object ContentRating {
    const val EXPLICIT = "e"
    const val QUESTIONABLE = "q"
    const val SAFE = "s"

    /** Normalises any board's rating value to "e" / "q" / "s", or null if unknown. */
    fun normalize(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        "e", "explicit", "x", "r18", "nsfw" -> EXPLICIT
        "q", "questionable", "suggestive" -> QUESTIONABLE
        "s", "safe", "g", "general", "sensitive", "sfw" -> SAFE
        else -> null
    }

    fun isAdult(item: MediaItem): Boolean {
        if (normalize(item.extras["rating"]) == SAFE) return false
        if (item.extras["sensitive"] == "false") return false
        for (raw in item.tags) {
            val t = raw.trim().lowercase()
            if (t.startsWith("rating:") && normalize(t.removePrefix("rating:")) == SAFE) return false
            if (t == "sfw") return false
            // Philomena boards tag the rating itself; "safe" there means the post is rated safe.
            if (item.sourceId == "philomena" && t == "safe") return false
        }
        return true
    }
}
