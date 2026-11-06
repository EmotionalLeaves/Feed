package dev.pfeed.data.settings

import dev.pfeed.domain.reco.RankWeights
import kotlinx.serialization.Serializable

@Serializable
data class SourceConfig(val id: String, val enabled: Boolean)

@Serializable
enum class Disguise { NONE, NOTES, CALCULATOR }

@Serializable
data class MastodonFeed(val host: String, val tag: String? = null) {
    val label: String get() = if (tag.isNullOrBlank()) host else "$host #$tag"
}

@Serializable
data class BooruFeed(val host: String, val tags: String? = null) {
    val label: String get() = if (tags.isNullOrBlank()) host else "$host · $tags"
}

/**
 * User-defined mapping for the Generic JSON adapter. [urlTemplate] may contain {cursor} and {query}.
 * Paths are dotted (see JsonPath), relative to each item after [wrapPath].
 */
@Serializable
data class JsonFeed(
    val name: String,
    val urlTemplate: String,
    val itemsPath: String,
    val mediaPath: String = "url",
    val searchTemplate: String? = null,
    val wrapPath: String = "",
    val idPath: String = "id",
    val titlePath: String = "title",
    val creatorPath: String = "",
    val thumbnailPath: String = "",
    val tagsPath: String = "",
    val pagePath: String = "",
    val typeHint: String = "auto",
)

/** Everything user-configurable except secrets (PIN, DB key, OAuth tokens), which never leave the device. */
@Serializable
data class AppSettings(
    /** Order = priority (first wins when deduplicating). Missing adapters are appended with their defaults. */
    val sources: List<SourceConfig> = emptyList(),
    val rssFeeds: List<String> = emptyList(),
    /** Direct media/page URLs the user pasted. */
    val directUrls: List<String> = emptyList(),
    /** Subreddit names (no "r/" prefix). Pre-seeded with popular NSFW subs. */
    val redditSubs: List<String> = DefaultSources.redditSubs,
    val mastodonFeeds: List<MastodonFeed> = DefaultSources.mastodonFeeds,
    val booruFeeds: List<BooruFeed> = DefaultSources.booruFeeds,
    val jsonFeeds: List<JsonFeed> = emptyList(),
    /** Storage Access Framework tree URIs. Device-specific, so not imported from backups. */
    val localFolders: List<String> = emptyList(),
    val cacheSizeMb: Int = 1024,
    val autoplayOnMobileData: Boolean = false,
    val defaultMute: Boolean = true,
    val historyRetentionDays: Int = 30,
    val lockTimeoutSec: Int = 30,
    val halfLifeDays: Double = 14.0,
    val weights: RankWeights = RankWeights(),
    val disguise: Disguise = Disguise.NONE,
)

/**
 * Out-of-the-box source library so the app has content on first launch with zero setup.
 * All sources are public, unauthenticated APIs or documented JSON endpoints.
 */
object DefaultSources {

    /** Popular NSFW subreddits (read via reddit.com/r/NAME/hot.json — public, no login). */
    val redditSubs: List<String> = listOf(
        // General/mixed
        "nsfw", "RealGirls", "Amateur", "gonewild", "GoneWildCurvy",
        "nsfw_gifs", "NSFW_GIF", "nsfwhardcore", "porninfifteenseconds",
        "holdthemoan", "asstastic", "BigAsses", "burstingout",
        "girlsinyogapants", "leggings", "PetiteGoneWild",
        "slimygirls", "LegalTeens", "collegesluts",
        // Body types
        "BigBoobs", "bigboobsgw", "Boobies", "tits", "titties",
        "ass", "ButtPlug", "PantyPop", "thighs",
        "CurvyWomen", "chubby", "bbw", "SSBBW",
        // Acts
        "blowjobs", "cumsluts", "facials", "GaggedWomen",
        "GirlsFinishingTheJob", "oddlyterrifying",
        "rearpussy", "BDSM", "forcedorgasms",
        // Girls categories
        "altgonewild", "GoneWildAudio", "realgirls",
        "gonewild18", "NSFW_HTML5", "pussyrating",
        "RateMyNSFW", "verify_nude",
        // Video focus
        "nsfw_videos", "90s_porn", "PornStarlets",
        "TribeTwinks", "tgirls", "futanari",
        // Specific acts/niches
        "anal", "creampies", "gayporn", "lesbian",
        "GirlsOnTop", "ReverseGangbang", "WatchItForThePlot",
        "strippers", "OnOff", "Uncensored_Hentai",
        "hentai", "rule34", "ecchi", "manga_art",
        // More subs
        "Amateur_Blowjobs", "GroupSex", "orgasmically",
        "Stacked", "pokies", "bottomless", "nudism", "naturism",
        "Naturism_Nudism", "public_sex", "voyeur",
    )

    /** Mastodon/Fediverse NSFW instances with active public media timelines. */
    val mastodonFeeds: List<MastodonFeed> = listOf(
        // General adult Fediverse
        MastodonFeed("mastodon.social", "nsfw"),
        MastodonFeed("mastodon.social", "porn"),
        MastodonFeed("mastodon.social", "nude"),
        MastodonFeed("mastodon.social", "hentai"),
        MastodonFeed("mastodon.social", "bdsm"),
        // Dedicated NSFW instances
        MastodonFeed("switter.at"),
        MastodonFeed("switter.at", "porn"),
        MastodonFeed("switter.at", "nude"),
        MastodonFeed("onlyfans.com.social"),           // OF companion instance
        MastodonFeed("x.gd"),                          // adult-friendly
        MastodonFeed("pawoo.net"),                     // Japanese art/adult
        MastodonFeed("pawoo.net", "nsfw"),
        MastodonFeed("pawoo.net", "hentai"),
        MastodonFeed("baraag.net"),                    // adult art
        MastodonFeed("baraag.net", "nsfw"),
        MastodonFeed("baraag.net", "lewd"),
        // Art / anime NSFW
        MastodonFeed("kemonodon.space"),               // furry art
        MastodonFeed("yiff.social"),
        MastodonFeed("yiff.social", "nsfw"),
        MastodonFeed("cum.salon"),
        MastodonFeed("lustrous.social"),
        MastodonFeed("h-space.social"),
        MastodonFeed("nightfall.social"),
        // More adult instances
        MastodonFeed("primates.social"),
        MastodonFeed("booped.social"),
        MastodonFeed("bae.social"),
        MastodonFeed("rubber.social"),
        MastodonFeed("latex.social"),
        MastodonFeed("nsfwdone.social"),
        MastodonFeed("nsfwdone.social", "amateur"),
        MastodonFeed("nsfwdone.social", "gonewild"),
        MastodonFeed("ero.social"),
        MastodonFeed("kinkies.com"),
        MastodonFeed("bdsmr.com"),
        MastodonFeed("lewd.town"),
        MastodonFeed("lewd.town", "lewd"),
        MastodonFeed("lewdland.social"),
        MastodonFeed("femdom.social"),
        MastodonFeed("flirtation.social"),
        MastodonFeed("sex.social"),
        MastodonFeed("booty.social"),
        MastodonFeed("hunk.city"),
        MastodonFeed("asianladies.social"),
        MastodonFeed("milffederation.social"),
        MastodonFeed("swingleft.social"),
    )

    /**
     * Booru/image-board API endpoints (Danbooru-compatible JSON) pre-configured with
     * popular NSFW tag searches. Each entry is a separate [BooruFeed].
     */
    val booruFeeds: List<BooruFeed> = listOf(
        // ---- danbooru.donmai.us (Gold required for explicit, so use safe+questionable) ----
        BooruFeed("danbooru.donmai.us", "rating:questionable order:random"),
        BooruFeed("danbooru.donmai.us", "rating:sensitive nude order:random"),
        BooruFeed("danbooru.donmai.us", "rating:questionable breasts order:random"),
        BooruFeed("danbooru.donmai.us", "1girl nude order:random"),
        BooruFeed("danbooru.donmai.us", "bikini order:random"),
        BooruFeed("danbooru.donmai.us", "hentai order:random"),
        BooruFeed("danbooru.donmai.us", "sex order:random"),
        // ---- e621.net (furry, explicit allowed) ----
        BooruFeed("e621.net", "explicit order:random"),
        BooruFeed("e621.net", "explicit solo female order:random"),
        BooruFeed("e621.net", "explicit duo order:random"),
        BooruFeed("e621.net", "explicit animation order:random"),
        BooruFeed("e621.net", "nude order:random"),
        // ---- rule34.xxx (rule34, very explicit) ----
        BooruFeed("rule34.xxx", "nude"),
        BooruFeed("rule34.xxx", "sex"),
        BooruFeed("rule34.xxx", "animated"),
        BooruFeed("rule34.xxx", "1girl nude explicit"),
        BooruFeed("rule34.xxx", "cum"),
        // ---- gelbooru.com ----
        BooruFeed("gelbooru.com", "nude rating:explicit"),
        BooruFeed("gelbooru.com", "sex rating:explicit"),
        BooruFeed("gelbooru.com", "animated rating:explicit"),
        BooruFeed("gelbooru.com", "bikini rating:questionable"),
        // ---- safebooru.donmai.us (ecchi/safe, good for variety) ----
        BooruFeed("safebooru.donmai.us", "bikini 1girl order:random"),
        BooruFeed("safebooru.donmai.us", "1girl rating:sensitive order:random"),
        // ---- tbib.org (The Big ImageBoard) ----
        BooruFeed("tbib.org", "nude"),
        BooruFeed("tbib.org", "sex"),
        BooruFeed("tbib.org", "animated nude"),
        // ---- xbooru.com ----
        BooruFeed("xbooru.com", "nude"),
        BooruFeed("xbooru.com", "sex"),
        // ---- hypnohub.net ----
        BooruFeed("hypnohub.net", "nude"),
        // ---- lolibooru.moe (non-explicit artistic) — may 403, skipped gracefully ----
        // ---- konachan.com (moebooru) ----
        BooruFeed("konachan.com", "nude"),
        BooruFeed("konachan.com", "bikini"),
        // ---- yande.re (moebooru) ----
        BooruFeed("yande.re", "nude"),
        BooruFeed("yande.re", "sex"),
        // ---- behoimi.com ----
        BooruFeed("behoimi.org", "nude"),
        // ---- dollbooru ----
        BooruFeed("dollbooru.org", "nude"),
        // ---- idol complex (cosplay) ----
        BooruFeed("idol.sankakucomplex.com", "nude"),
        // ---- chan.sankakucomplex.com ----
        BooruFeed("chan.sankakucomplex.com", "nude rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "bikini"),
    )
}
