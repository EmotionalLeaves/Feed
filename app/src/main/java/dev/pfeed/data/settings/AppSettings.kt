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
    /** Version of the taste model the stored affinities were learned with; see SignalRepository. */
    val tasteModelVersion: Int = 0,
)

/**
 * Out-of-the-box source library so the app has content on first launch with zero setup.
 * All sources are public, unauthenticated APIs or documented JSON endpoints.
 */
object DefaultSources {

    /** Popular NSFW subreddits (read via reddit.com/r/NAME/hot.json — public, no login). */
    val redditSubs: List<String> = listOf(
        // ── Rough / hardcore / BDSM (prioritised per user preference) ──────────────
        "BDSM", "BDSMcommunity", "BDSMpics", "BDSMvideos",
        "Bondage", "bondagegirls", "tiedandbound", "tiedup",
        "Roughsex", "rough_porn", "roughsexvideos", "RoughInterracial",
        "CNC", "consensualnonconsent", "RapePlay",
        "Choking", "FaceFuck", "DeepThroat", "throatfuck",
        "GaggedWomen", "gagging", "PulledHair",
        "Spanking", "spankingwomen", "OTKSpanking",
        "Degraded", "slapping", "slut_training",
        "Domination", "maledom", "femdom",
        "forcedorgasms", "overstimulation",
        "objectification", "humiliation",
        "breeding", "usedasacumdump", "CumSlut",
        "Rape_Hentai", "ForceHentai", "tentacle_hentai",
        // ── General/mixed ─────────────────────────────────────────────────────────
        "nsfw", "RealGirls", "Amateur", "gonewild", "GoneWildCurvy",
        "nsfw_gifs", "NSFW_GIF", "nsfwhardcore", "porninfifteenseconds",
        "holdthemoan", "asstastic", "BigAsses", "burstingout",
        "girlsinyogapants", "leggings", "PetiteGoneWild",
        "slimygirls", "collegesluts", "Amateur18",
        "nsfw411", "nsfwoutfits", "sexybutnotporn",
        "normalnudes", "OnOff", "clothedunclothed",
        "boltedontits", "bigdickproblems", "dirtypenpals",
        // ── Body types ────────────────────────────────────────────────────────────
        "BigBoobs", "bigboobsgw", "Boobies", "tits", "titties",
        "ass", "ButtPlug", "PantyPop", "thighs",
        "CurvyWomen", "chubby", "bbw", "SSBBW",
        "Stacked", "pokies", "bottomless",
        "juicyasians", "AsianNSFW", "AsianHotties",
        "latinasgw", "latinaass", "ebony",
        "blackgirls", "MixedRaceGirls", "darkskin",
        "redheads", "gingersgonewild", "blonde",
        "MILFs", "MILF", "maturewomen", "olderwoman",
        "cougars", "GILF",
        // ── Acts ──────────────────────────────────────────────────────────────────
        "blowjobs", "cumsluts", "facials",
        "rearpussy", "doggystyle",
        "Amateur_Blowjobs", "GroupSex", "orgasmically",
        "anal", "analonly", "analbeads",
        "creampies", "internalcumshots", "creampiegifs",
        "GirlsFinishingTheJob", "swallowing",
        "Squirting", "wetpussy", "girlswhoride",
        "GirlsOnTop", "CowgirlPosition",
        "ReverseGangbang", "gangbang", "spitroast",
        "doublePenetration", "DP",
        "public_sex", "PornInPublic", "flashing",
        "voyeur", "spy", "caught",
        "strippers", "lapdance",
        "massage", "naughtymasseuse",
        // ── Videos ────────────────────────────────────────────────────────────────
        "nsfw_videos", "NSFW_HTML5", "porninfifteenseconds",
        "nsfwhardcore", "90s_porn",
        // ── Gay / bi ──────────────────────────────────────────────────────────────
        "gayporn", "gay", "gaynsfw", "GayBros",
        "TribeTwinks", "GayTwinks", "GayBears", "gaydaddies",
        "GayCumSluts", "GayBondage", "GayBDSM",
        "gaychubs", "gaymuscle", "gaydick",
        // ── Trans / NB ────────────────────────────────────────────────────────────
        "tgirls", "futanari", "tgonewild",
        "asiancd", "feminineboys",
        // ── Lesbian ───────────────────────────────────────────────────────────────
        "lesbian", "lesbians", "lesbiansgw",
        "girlkissing", "girlsgroping",
        // ── Hentai / anime ────────────────────────────────────────────────────────
        "hentai", "rule34", "ecchi", "manga_art",
        "Uncensored_Hentai", "HentaiGif", "hentai_gif",
        "yuri", "yaoi", "futanari",
        "doujinshi", "NaughtyAnime",
        // ── Fetish / niche ────────────────────────────────────────────────────────
        "feet", "FootFetish", "Shibari",
        "latex", "rubber", "leather_kink",
        "Pegging", "sissification",
        "cumplay", "cum_swallow",
        "PussyRating", "RateMyNSFW",
        "gonewild18", "altgonewild",
        "nudism", "naturism", "Naturism_Nudism",
        "PornStarlets", "WatchItForThePlot",
        // ── Misc ──────────────────────────────────────────────────────────────────
        "GoneWildAudio", "verify_nude",
        "BDSMAdvice", "CuckoldCommunity",
        "cuckold", "hotwife", "slutwife",
        "swingers", "orgy", "polyamory_nsfw",
    )

    /** Mastodon/Fediverse NSFW instances with active public media timelines. */
    val mastodonFeeds: List<MastodonFeed> = listOf(
        // ── mastodon.social — broad tag sweeps ───────────────────────────────────
        MastodonFeed("mastodon.social", "nsfw"),
        MastodonFeed("mastodon.social", "porn"),
        MastodonFeed("mastodon.social", "nude"),
        MastodonFeed("mastodon.social", "hentai"),
        MastodonFeed("mastodon.social", "bdsm"),
        MastodonFeed("mastodon.social", "bondage"),
        MastodonFeed("mastodon.social", "roughsex"),
        MastodonFeed("mastodon.social", "blowjob"),
        MastodonFeed("mastodon.social", "anal"),
        MastodonFeed("mastodon.social", "amateur"),
        MastodonFeed("mastodon.social", "gonewild"),
        MastodonFeed("mastodon.social", "lewd"),
        MastodonFeed("mastodon.social", "creampie"),
        MastodonFeed("mastodon.social", "gangbang"),
        MastodonFeed("mastodon.social", "cumshot"),
        MastodonFeed("mastodon.social", "facefuck"),
        MastodonFeed("mastodon.social", "domination"),
        MastodonFeed("mastodon.social", "femdom"),
        MastodonFeed("mastodon.social", "maledom"),
        MastodonFeed("mastodon.social", "spanking"),
        MastodonFeed("mastodon.social", "choking"),
        // ── switter.at — sex worker instance ─────────────────────────────────────
        MastodonFeed("switter.at"),
        MastodonFeed("switter.at", "porn"),
        MastodonFeed("switter.at", "nude"),
        MastodonFeed("switter.at", "bdsm"),
        MastodonFeed("switter.at", "roughsex"),
        MastodonFeed("switter.at", "bondage"),
        MastodonFeed("switter.at", "blowjob"),
        MastodonFeed("switter.at", "anal"),
        MastodonFeed("switter.at", "creampie"),
        // ── yiff.social — furry adult ─────────────────────────────────────────────
        MastodonFeed("yiff.social"),
        MastodonFeed("yiff.social", "nsfw"),
        MastodonFeed("yiff.social", "bdsm"),
        MastodonFeed("yiff.social", "bondage"),
        // ── lewd.town ─────────────────────────────────────────────────────────────
        MastodonFeed("lewd.town"),
        MastodonFeed("lewd.town", "lewd"),
        MastodonFeed("lewd.town", "bdsm"),
        MastodonFeed("lewd.town", "bondage"),
        MastodonFeed("lewd.town", "roughsex"),
        MastodonFeed("lewd.town", "nsfw"),
        // ── kinkies.com / bdsmr.com ───────────────────────────────────────────────
        MastodonFeed("kinkies.com"),
        MastodonFeed("kinkies.com", "bdsm"),
        MastodonFeed("kinkies.com", "bondage"),
        MastodonFeed("kinkies.com", "roughsex"),
        MastodonFeed("bdsmr.com"),
        MastodonFeed("bdsmr.com", "bondage"),
        MastodonFeed("bdsmr.com", "bdsm"),
        // ── femdom / maledom focused ──────────────────────────────────────────────
        MastodonFeed("femdom.social"),
        MastodonFeed("femdom.social", "femdom"),
        MastodonFeed("femdom.social", "bdsm"),
        MastodonFeed("femdom.social", "bondage"),
        // ── other adult instances ─────────────────────────────────────────────────
        MastodonFeed("cum.salon"),
        MastodonFeed("lustrous.social"),
        MastodonFeed("h-space.social"),
        MastodonFeed("nightfall.social"),
        MastodonFeed("rubber.social"),
        MastodonFeed("latex.social"),
        MastodonFeed("lewdland.social"),
        MastodonFeed("flirtation.social"),
        MastodonFeed("sex.social"),
        MastodonFeed("booty.social"),
        MastodonFeed("ero.social"),
        MastodonFeed("nsfwdone.social"),
        MastodonFeed("nsfwdone.social", "amateur"),
        MastodonFeed("nsfwdone.social", "gonewild"),
        MastodonFeed("nsfwdone.social", "bdsm"),
        MastodonFeed("kemonodon.space"),
        MastodonFeed("hunk.city"),
        MastodonFeed("asianladies.social"),
        MastodonFeed("milffederation.social"),
        MastodonFeed("booped.social"),
        MastodonFeed("bae.social"),
        MastodonFeed("primates.social"),
        MastodonFeed("onlyfans.com.social"),
    )

    /**
     * Booru/image-board API endpoints — Danbooru-compatible JSON.
     * BooruAdapter auto-detects the correct API path per board family.
     */
    val booruFeeds: List<BooruFeed> = listOf(

        // ── rule34.xxx — works via HTML fallback when no API key is set ──────────
        BooruFeed("rule34.xxx", "bondage"),
        BooruFeed("rule34.xxx", "bdsm"),
        BooruFeed("rule34.xxx", "rough_sex"),
        BooruFeed("rule34.xxx", "gangbang"),
        BooruFeed("rule34.xxx", "deepthroat"),
        BooruFeed("rule34.xxx", "anal"),
        BooruFeed("rule34.xxx", "oral"),
        BooruFeed("rule34.xxx", "cum"),
        BooruFeed("rule34.xxx", "animated"),
        BooruFeed("rule34.xxx", "sex"),
        BooruFeed("rule34.xxx", "nude"),
        BooruFeed("rule34.xxx", "femdom"),
        BooruFeed("rule34.xxx", "maledom"),
        BooruFeed("rule34.xxx", "rape"),
        BooruFeed("rule34.xxx", "forced"),
        BooruFeed("rule34.xxx", "choking"),
        BooruFeed("rule34.xxx", "tied_up"),
        BooruFeed("rule34.xxx", "gagged"),
        BooruFeed("rule34.xxx", "spanking"),
        // ── gelbooru.com — works via HTML fallback when no API key is set ─────────
        BooruFeed("gelbooru.com", "bondage rating:explicit"),
        BooruFeed("gelbooru.com", "bdsm rating:explicit"),
        BooruFeed("gelbooru.com", "rough_sex rating:explicit"),
        BooruFeed("gelbooru.com", "gangbang rating:explicit"),
        BooruFeed("gelbooru.com", "deepthroat rating:explicit"),
        BooruFeed("gelbooru.com", "anal rating:explicit"),
        BooruFeed("gelbooru.com", "oral rating:explicit"),
        BooruFeed("gelbooru.com", "cum rating:explicit"),
        BooruFeed("gelbooru.com", "animated rating:explicit"),
        BooruFeed("gelbooru.com", "sex rating:explicit"),
        BooruFeed("gelbooru.com", "nude rating:explicit"),
        BooruFeed("gelbooru.com", "femdom rating:explicit"),
        BooruFeed("gelbooru.com", "maledom rating:explicit"),
        BooruFeed("gelbooru.com", "rape rating:explicit"),
        BooruFeed("gelbooru.com", "choking rating:explicit"),
        BooruFeed("gelbooru.com", "gagged rating:explicit"),
        // ══ e621.net — furry explicit ════════════════════════════════════════════
        BooruFeed("e621.net", "explicit order:random"),
        BooruFeed("e621.net", "explicit solo female order:random"),
        BooruFeed("e621.net", "explicit duo order:random"),
        BooruFeed("e621.net", "explicit animation order:random"),
        BooruFeed("e621.net", "bondage explicit order:random"),
        BooruFeed("e621.net", "rape explicit order:random"),
        BooruFeed("e621.net", "bdsm explicit order:random"),
        BooruFeed("e621.net", "rough_sex explicit order:random"),
        BooruFeed("e621.net", "blowjob explicit order:random"),
        BooruFeed("e621.net", "deepthroat explicit order:random"),
        BooruFeed("e621.net", "gangbang explicit order:random"),
        BooruFeed("e621.net", "cum explicit order:random"),
        // ══ danbooru.donmai.us — high quality anime art ══════════════════════════
        BooruFeed("danbooru.donmai.us", "rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "rating:questionable order:random"),
        BooruFeed("danbooru.donmai.us", "1girl nude rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "sex rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "blowjob rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "anal rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "bondage rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "bdsm rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "rape rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "gangbang rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "deepthroat rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "cum rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "femdom rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "maledom rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "tentacle rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "forced rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "rough_sex rating:explicit order:random"),
        BooruFeed("danbooru.donmai.us", "choking rating:explicit order:random"),
        // ══ tbib.org — The Big ImageBoard ════════════════════════════════════════
        BooruFeed("tbib.org", "nude"),
        BooruFeed("tbib.org", "sex"),
        BooruFeed("tbib.org", "animated"),
        BooruFeed("tbib.org", "blowjob"),
        BooruFeed("tbib.org", "anal"),
        BooruFeed("tbib.org", "bondage"),
        BooruFeed("tbib.org", "bdsm"),
        BooruFeed("tbib.org", "rape"),
        BooruFeed("tbib.org", "gangbang"),
        BooruFeed("tbib.org", "roughsex"),
        BooruFeed("tbib.org", "deepthroat"),
        BooruFeed("tbib.org", "creampie"),
        BooruFeed("tbib.org", "femdom"),
        BooruFeed("tbib.org", "maledom"),
        // ══ xbooru.com ═══════════════════════════════════════════════════════════
        BooruFeed("xbooru.com", "nude"),
        BooruFeed("xbooru.com", "sex"),
        BooruFeed("xbooru.com", "blowjob"),
        BooruFeed("xbooru.com", "anal"),
        BooruFeed("xbooru.com", "bondage"),
        BooruFeed("xbooru.com", "rape"),
        BooruFeed("xbooru.com", "gangbang"),
        BooruFeed("xbooru.com", "roughsex"),
        BooruFeed("xbooru.com", "deepthroat"),
        BooruFeed("xbooru.com", "creampie"),
        BooruFeed("xbooru.com", "femdom"),
        // ══ konachan.com (moebooru) ═══════════════════════════════════════════════
        BooruFeed("konachan.com", "nude"),
        BooruFeed("konachan.com", "sex"),
        BooruFeed("konachan.com", "bondage"),
        BooruFeed("konachan.com", "bdsm"),
        BooruFeed("konachan.com", "rape"),
        BooruFeed("konachan.com", "blowjob"),
        // ══ yande.re (moebooru) ═══════════════════════════════════════════════════
        BooruFeed("yande.re", "nude"),
        BooruFeed("yande.re", "sex"),
        BooruFeed("yande.re", "bondage"),
        BooruFeed("yande.re", "bdsm"),
        BooruFeed("yande.re", "rape"),
        BooruFeed("yande.re", "blowjob"),
        BooruFeed("yande.re", "anal"),
        BooruFeed("yande.re", "gangbang"),
        BooruFeed("yande.re", "deepthroat"),
        BooruFeed("yande.re", "roughsex"),
        BooruFeed("yande.re", "femdom"),
        BooruFeed("yande.re", "maledom"),
        // ══ chan.sankakucomplex.com ═══════════════════════════════════════════════
        BooruFeed("chan.sankakucomplex.com", "nude rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "sex rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "bondage rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "rape rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "bdsm rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "blowjob rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "anal rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "gangbang rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "roughsex rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "deepthroat rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "femdom rating:explicit"),
        // ══ hypnohub.net ══════════════════════════════════════════════════════════
        BooruFeed("hypnohub.net", "nude"),
        BooruFeed("hypnohub.net", "sex"),
        BooruFeed("hypnohub.net", "bdsm"),
        BooruFeed("hypnohub.net", "bondage"),
        BooruFeed("hypnohub.net", "rape"),
        // ══ dollbooru.org ══════════════════════════════════════════════════════════
        BooruFeed("dollbooru.org", "nude"),
        BooruFeed("dollbooru.org", "sex"),
        BooruFeed("dollbooru.org", "bondage"),
        // ══ safebooru.donmai.us — ecchi/questionable ══════════════════════════════
        BooruFeed("safebooru.donmai.us", "1girl rating:sensitive order:random"),
        BooruFeed("safebooru.donmai.us", "bikini 1girl order:random"),
        // ══ idol.sankakucomplex.com — cosplay/idol ════════════════════════════════
        BooruFeed("idol.sankakucomplex.com", "nude"),
        BooruFeed("idol.sankakucomplex.com", "sex"),

        // ── tbib.org — large gelbooru-engine mirror, no key needed ────────────────
        BooruFeed("tbib.org", "nude"),
        BooruFeed("tbib.org", "sex"),
        BooruFeed("tbib.org", "animated"),
        BooruFeed("tbib.org", "cum"),
        BooruFeed("tbib.org", "oral"),
        BooruFeed("tbib.org", "anal"),
        BooruFeed("tbib.org", "gangbang"),
        BooruFeed("tbib.org", "bdsm"),
        BooruFeed("tbib.org", "1girl solo"),
        // ── xbooru.com — no key needed ───────────────────────────────────────────
        BooruFeed("xbooru.com", "nude"),
        BooruFeed("xbooru.com", "sex"),
        BooruFeed("xbooru.com", "animated"),
        BooruFeed("xbooru.com", "cum"),
        BooruFeed("xbooru.com", "oral"),
        BooruFeed("xbooru.com", "anal"),
        BooruFeed("xbooru.com", "gangbang"),
        BooruFeed("xbooru.com", "bdsm"),
        BooruFeed("xbooru.com", "bondage"),
        BooruFeed("xbooru.com", "blowjob"),
        // ── hypnohub.net ─────────────────────────────────────────────────────────
        BooruFeed("hypnohub.net", "bondage"),
        BooruFeed("hypnohub.net", "nude"),
        BooruFeed("hypnohub.net", "sex"),
        BooruFeed("hypnohub.net", "restrained"),
        BooruFeed("hypnohub.net", "mind_control"),
        // ── e621.net — extra tags ────────────────────────────────────────────────
        BooruFeed("e621.net", "animated order:score rating:e"),
        BooruFeed("e621.net", "cum order:score rating:e"),
        BooruFeed("e621.net", "oral order:score rating:e"),
        BooruFeed("e621.net", "anal order:score rating:e"),
        BooruFeed("e621.net", "gangbang order:score rating:e"),
        BooruFeed("e621.net", "bdsm order:score rating:e"),

        // ── chan.sankakucomplex.com — via capi-v2 ────────────────────────────────
        BooruFeed("chan.sankakucomplex.com", "nude rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "sex rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "bondage rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "bdsm rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "blowjob rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "anal rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "gangbang rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "roughsex rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "deepthroat rating:explicit"),
        BooruFeed("chan.sankakucomplex.com", "femdom rating:explicit"),
    )
}
