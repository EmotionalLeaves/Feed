package dev.pfeed.domain.reco

import dev.pfeed.domain.model.MediaItem

/**
 * Fandom niches that a few sources supply in bulk and that would otherwise flood the feed. Derpibooru
 * is a My Little Pony board (every post is MLP), and e621 and the furry boards carry a lot of it too,
 * with high community scores, so they ranked well and filled the feed for everyone.
 *
 * Nothing is filtered: a niche just gets at most [Spread.MAX_PER_NICHE] slot in any window of the
 * feed, unless the user has shown they want it (see [Spread.openNiches]).
 */
object Niche {
    const val MLP = "mlp"

    /** A closed niche gets at most one post in this many (longer than the spacing window). */
    const val CLOSED_WINDOW = 36


    private val MLP_HOSTS = setOf("derpibooru.org", "ponybooru.org", "twibooru.org", "manebooru.art", "ponerpics.org")
    private val MLP_TAGS = setOf(
        "my_little_pony", "my little pony", "mlp", "mlp:fim", "friendship_is_magic", "friendship is magic",
        "my_little_pony:_friendship_is_magic", "equestria_girls", "equestria girls", "pony", "earth_pony",
        "earth pony", "unicorn_pony", "pegasus_pony", "twilight_sparkle", "twilight sparkle", "rainbow_dash",
        "rainbow dash", "fluttershy", "pinkie_pie", "pinkie pie", "applejack", "rarity_(mlp)", "princess_celestia",
        "princess celestia", "princess_luna", "princess luna", "derpy_hooves", "starlight_glimmer", "brony",
        "my_little_pony_(series)", "mlp_fim",
    )

    /**
     * Niches the user has explicitly asked for: liked or saved at least [OPEN_AFTER] of its posts, or
     * follows one of its tags. Watching isn't enough: while pony posts flooded every feed, people
     * watched them simply because they were there, and that alone re-opened the flood.
     */
    fun opened(likedOrSaved: List<MediaItem>, followedTags: Set<String>): Set<String> {
        val explicit = likedOrSaved.count { of(it) == MLP }
        val follows = followedTags.any { isMlpTag(it) }
        return if (explicit >= OPEN_AFTER || follows) setOf(MLP) else emptySet()
    }

    private const val OPEN_AFTER = 3

    /** Exact tags, plus the patterns boards use: "*_(mlp)" characters, "my_little_pony…", "…equestria…". */
    fun isMlpTag(raw: String): Boolean {
        val t = raw.trim().lowercase()
        return t in MLP_TAGS || t.endsWith("_(mlp)") || t.endsWith(" (mlp)") || t.startsWith("mlp:") || t.startsWith("mlp_") ||
            t.contains("my_little_pony") || t.contains("my little pony") || t.contains("friendship_is_magic") || t.contains("equestria")
    }

    fun of(item: MediaItem): String? {
        val host = item.id.substringBefore('/', "").lowercase().removePrefix("www.")
        if (host in MLP_HOSTS) return MLP
        if (item.tags.any(::isMlpTag)) return MLP
        return null
    }
}
