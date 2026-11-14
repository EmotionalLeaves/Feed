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
     * Niches the user has clearly asked for: liked, saved, followed or watched through enough of it
     * that For You's learned affinity for its tags is strongly positive.
     */
    fun opened(tagAffinity: Map<String, Double>): Set<String> {
        val best = MLP_TAGS.maxOfOrNull { tagAffinity[Features.normalizeTag(it)] ?: 0.0 } ?: 0.0
        return if (Ranker.squash(best) >= OPEN_AT) setOf(MLP) else emptySet()
    }

    private const val OPEN_AT = 0.5

    fun of(item: MediaItem): String? {
        val host = item.id.substringBefore('/', "").lowercase().removePrefix("www.")
        if (host in MLP_HOSTS) return MLP
        if (item.tags.any { it.trim().lowercase() in MLP_TAGS }) return MLP
        return null
    }
}
