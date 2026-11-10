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
    /** coomer.st / kemono hosts (OnlyFans/Fansly/Patreon aggregators, shared API). */
    val kemonoHosts: List<String> = DefaultSources.kemonoHosts,
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
    /** Version of the built-in content cleanup applied to saved sources; see SettingsRepository. */
    val contentFilterVersion: Int = 0,
)

/** Fediverse instances known for hosting sexualized depictions of minors: never seeded, never fetched. */
object MastodonExclusions {
    private val HOSTS = setOf("pawoo.net", "baraag.net")

    fun isExcluded(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
        return HOSTS.any { h == it || h.endsWith(".$it") }
    }
}

/**
 * Out-of-the-box source library so the app has content on first launch with zero setup.
 * All sources are public, unauthenticated APIs or documented JSON endpoints.
 */
object DefaultSources {

    /** Popular NSFW subreddits (read via reddit.com/r/NAME/hot.json — public, no login). */
    private val curatedRedditSubs: List<String> = listOf(
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
        "Nude_Selfie",
        "adorableporn",
        "LegalTeens",
        "palegirls",
        "BiggerThanYouThought",
        "thick",
        "curvy",
        "hugeboobs",
        "TittyDrop",
        "2busty2hide",
        "bottomless_vixens",
        "workgonewild",
        "PublicFlashing",
        "realpublicnudity",
        "flashinggirls",
        "Nudes",
        "NSFW_Snapchat",
        "dirtysmall",
        "TinyTits",
        "SmallBoobs",
        "AsiansGoneWild",
        "realasians",
        "IndianBabes",
        "cumfetish",
        "OralCreampie",
        "throated",
        "SwallowMyChildren",
        "wifesharing",
        "impregnation",
        "groupofnudegirls",
        "orgies",
        "nsfwcosplay",
        "CosplayLewd",
        "yogapants",
        "leggingsgonewild",
        "AssholeBehindThong",
        "thongs",
        "lingerie",
        "stockings",
        "nylonfeet",
        "Feetishh",
        "tattedbabes",
        "suicidegirls",
        "piercednsfw",
        "AgeGap",
        "maturemilf",
        "AmateurMilfs",
        "60fpsporn",
        "porn_gifs",
        "60fps",
        "GirlsHumpingThings",
        "dmodels",
        "O_faces",
        "Ahegao",
        "roughroom",
        "degradingholes",
        // ── Added batch: amateur / international ───────────────────────────────────
        "homemadexxx", "RealHomePorn", "amateurporn", "GoneWildHairy", "HairyPussy",
        "wifesgonewild", "hotwives", "wifeporn", "sharemywife", "wouldyoufuckmywife",
        "amateurcumsluts", "onmww", "MySexyWife", "verifiedamateurs", "NSFWverifiedamateurs",
        "nsfwcollege", "campusgonewild", "dormgirls", "snapchatsluts", "snapchatgw",
        "BritishGW", "britishporn", "aussiegonewild", "germangonewild", "frenchgonewild",
        "italiangonewild", "spanishgonewild", "russiangonewild", "brazilianporn", "mexicanagw",
        "colombianasgw", "indiangw", "desigonewild", "japanesegonewild", "koreangonewild",
        "pinaygonewild", "filipinagonewild", "turkishgonewild", "greekgonewild",
        // ── Added batch: body ─────────────────────────────────────────────────────
        "thickwhitegirls", "PAWG", "phatasses", "bigbuttsonly", "twerking",
        "twerkitnude", "juicybooty", "perfecttits", "perkytits", "smalltitsbigass",
        "nerdynudes", "girlswithglasses", "beautifulnudes", "nakedgirls", "sexygirls",
        "shavedpussy", "gapingpussy", "inniepussy", "godpussy", "labia",
        "closeups", "spreadeagle", "buttsfordays", "oiledass", "bootyshorts",
        "bustypetite", "naturaltitties", "hangers", "sideboobs", "underboob",
        "cleavage", "braless", "nipples", "puffies", "bignaturals",
        // ── Added batch: acts ─────────────────────────────────────────────────────
        "cumonher", "cumontits", "cumonass", "facialcumshots", "messyfacials",
        "cumswap", "snowballing", "edging", "handjobs", "titfuck",
        "tittyfuck", "deepthroatattempts", "sloppytoppy", "rimming", "asseating",
        "pussyeating", "sixtynine", "breedingmaterial", "gloryhole", "bukkake",
        "airtight", "allthreeholes", "chokemeplease", "hairpulling", "throatgoat",
        "overstimulated", "multipleorgasms", "ridingcock", "reversecowgirl", "standinganal",
        "analtraining", "buttstuff", "sloppyseconds", "cumdrip", "swallowed",
        // ── Added batch: fetish / niche (adult) ───────────────────────────────────
        "pantyhose", "nylons", "fishnets", "bodystockings", "highheelsnsfw",
        "feetpics", "cumonfeet", "footjobs", "wetandmessy", "oiledup",
        "spandexnsfw", "sportybabes", "gymbunnies", "collaredandcuffed", "petplaynsfw",
        "bimbofetish", "bimbofication", "freeuse", "bratsub", "goodgirls",
        "mindbreak", "leatherfetish", "corsets", "pvc", "maidfetish",
        "wartenberg", "wax_play", "electrostim", "predicament", "suspension",
        // ── Added batch: trans / gay / lesbian ────────────────────────────────────
        "transporn", "transgirls", "GoneWildTrans", "trans_nsfw", "traps",
        "trapgonewild", "ladyboy", "shemales", "futaonfemale", "gaypornhd",
        "menofgonewild", "massivecock", "monstercock", "thickdick", "gaygloryhole",
        "twinksgonewild", "bigcocklovers", "lesbianporn", "girlswhoeatgirls", "scissoring",
        "straponlesbians", "transensual", "femboys", "sissyhypno", "girldick",
        // ── Added batch: hentai / anime / game ────────────────────────────────────
        "nsfwanime", "animepussy", "animethighs", "animeass", "waifusgonewild",
        "monstergirls", "2dhentai", "hanime", "lewdneko", "thighhighshentai",
        "overwatchporn", "genshinimpactnsfw", "pokeporn", "zeldansfw", "cartoonpornpics",
        "westernhentai", "rule34cartoons", "splatoonnsfw", "fategrandorder_nsfw", "hentaimlem",
        "doujin", "uncensoredhentai", "animfeet", "ahegaogirls", "lewdgames",
        // ── Added batch: interracial / categories ─────────────────────────────────
        "interracialporn", "bbcsluts", "jav", "asianporn", "javstreams",
        "milfsgonewild", "maturensfw", "cougarsgonewild", "pornstars", "ebonygonewild",
        "latinahotties", "asianamateurs", "indianporn", "arabsgonewild", "blackchyna",
    )

    /** Mastodon/Fediverse NSFW instances with active public media timelines. */
    private val curatedMastodonFeeds: List<MastodonFeed> = listOf(
        // ── mstdn.jp — very large Japanese instance, lots of adult art ────────────
        MastodonFeed("mstdn.jp", "nsfw"),
        MastodonFeed("mstdn.jp", "hentai"),
        MastodonFeed("mstdn.jp", "エロ"),
        MastodonFeed("mstdn.jp", "bondage"),
        // ── abdl.link / kinktroet.social — kink-focused instances ────────────────
        MastodonFeed("kinktroet.social", "bdsm"),
        MastodonFeed("kinktroet.social", "bondage"),
        MastodonFeed("kinktroet.social", "nsfw"),
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
        MastodonFeed("rubber.social"),
        MastodonFeed("latex.social"),
        MastodonFeed("lewdland.social"),
        MastodonFeed("sex.social"),
        MastodonFeed("ero.social"),
        MastodonFeed("nsfwdone.social"),
        MastodonFeed("nsfwdone.social", "amateur"),
        MastodonFeed("nsfwdone.social", "gonewild"),
        MastodonFeed("nsfwdone.social", "bdsm"),
        MastodonFeed("kemonodon.space"),
        // ── Added batch: more tag sweeps on the big instances ─────────────────────
        MastodonFeed("mastodon.social", "milf"),
        MastodonFeed("mastodon.social", "feet"),
        MastodonFeed("mastodon.social", "stockings"),
        MastodonFeed("mastodon.social", "latex"),
        MastodonFeed("mastodon.social", "cosplay"),
        MastodonFeed("mastodon.social", "ahegao"),
        MastodonFeed("mastodon.social", "futanari"),
        MastodonFeed("mastodon.social", "yuri"),
        MastodonFeed("mastodon.social", "threesome"),
        MastodonFeed("mastodon.social", "cumslut"),
        MastodonFeed("mastodon.social", "titfuck"),
        MastodonFeed("mastodon.social", "handjob"),
        MastodonFeed("mastodon.social", "pawg"),
        MastodonFeed("mastodon.social", "bbw"),
        MastodonFeed("mastodon.social", "ebony"),
        MastodonFeed("mastodon.social", "asian"),
        MastodonFeed("mastodon.social", "latina"),
        MastodonFeed("mastodon.social", "trans"),
        MastodonFeed("mastodon.social", "creampie"),
        MastodonFeed("mastodon.social", "deepthroat"),
        MastodonFeed("mstdn.jp", "エッチ"),
        MastodonFeed("mstdn.jp", "おっぱい"),
        MastodonFeed("mstdn.jp", "お尻"),
        MastodonFeed("mstdn.jp", "裸"),
        MastodonFeed("mstdn.jp", "淫乱"),
        MastodonFeed("mstdn.jp", "巨乳"),
        MastodonFeed("mstdn.jp", "フェラ"),
        MastodonFeed("lewd.town", "hentai"),
        MastodonFeed("lewd.town", "futanari"),
        MastodonFeed("lewd.town", "feet"),
        MastodonFeed("lewd.town", "milf"),
        MastodonFeed("yiff.social", "yiff"),
        MastodonFeed("yiff.social", "furry"),
        MastodonFeed("yiff.social", "rough"),
        MastodonFeed("femdom.social", "chastity"),
        MastodonFeed("femdom.social", "pegging"),
        MastodonFeed("kinktroet.social", "rope"),
        MastodonFeed("kinktroet.social", "spanking"),
        MastodonFeed("sex.social", "amateur"),
        MastodonFeed("ero.social", "nude"),
        MastodonFeed("rubber.social", "latex"),
    )

    /**
     * Booru/image-board API endpoints — Danbooru-compatible JSON.
     * BooruAdapter auto-detects the correct API path per board family.
     */
    private val curatedBooruFeeds: List<BooruFeed> = listOf(

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
        // ══ idol.sankakucomplex.com — cosplay/idol ════════════════════════════════

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
        // ── e621.net — furry/general, reliable without a key ─────────────────────
        BooruFeed("e621.net", "rating:explicit order:rank"),
        BooruFeed("e621.net", "bondage rating:explicit"),
        BooruFeed("e621.net", "rough_sex rating:explicit"),
        BooruFeed("e621.net", "bdsm rating:explicit"),
        BooruFeed("e621.net", "double_penetration rating:explicit"),
        BooruFeed("e621.net", "deepthroat rating:explicit"),
        // ── danbooru.donmai.us — anonymous search allows 2 tags ──────────────────
        BooruFeed("danbooru.donmai.us", "rating:explicit bondage"),
        BooruFeed("danbooru.donmai.us", "rating:explicit bdsm"),
        BooruFeed("danbooru.donmai.us", "rating:explicit sex"),
        BooruFeed("danbooru.donmai.us", "rating:explicit fellatio"),
        BooruFeed("danbooru.donmai.us", "rating:explicit gangbang"),
        BooruFeed("danbooru.donmai.us", "rating:explicit anal"),
        // ── yande.re — high-quality scans ────────────────────────────────────────
        BooruFeed("yande.re", "rating:explicit"),
        BooruFeed("yande.re", "bondage rating:explicit"),
        BooruFeed("yande.re", "nipples rating:explicit"),
        // ── konachan.com ─────────────────────────────────────────────────────────
        BooruFeed("konachan.com", "rating:explicit"),
        BooruFeed("konachan.com", "bondage"),
        // ── tbib.org / xbooru.com — large gelbooru mirrors, no key ───────────────
        BooruFeed("tbib.org", "rough_sex"),
        BooruFeed("tbib.org", "double_penetration"),
        BooruFeed("tbib.org", "deepthroat"),
        BooruFeed("xbooru.com", "rough_sex"),
        BooruFeed("xbooru.com", "gangbang"),
        BooruFeed("xbooru.com", "facefuck"),
        // ── realbooru.com — real-life (non-drawn) photos & video, gelbooru engine ─
        BooruFeed("realbooru.com", "sex"),
        BooruFeed("realbooru.com", "blowjob"),
        BooruFeed("realbooru.com", "anal"),
        BooruFeed("realbooru.com", "bondage"),
        BooruFeed("realbooru.com", "bdsm"),
        BooruFeed("realbooru.com", "deepthroat"),
        BooruFeed("realbooru.com", "gangbang"),
        BooruFeed("realbooru.com", "double_penetration"),
        BooruFeed("realbooru.com", "cumshot"),
        BooruFeed("realbooru.com", "milf"),
        BooruFeed("realbooru.com", "amateur"),
        BooruFeed("realbooru.com", "rough"),
        // ── aibooru.online — AI-generated art, danbooru engine, no key ───────────
        BooruFeed("aibooru.online", "rating:explicit"),
        BooruFeed("aibooru.online", "rating:explicit sex"),
        BooruFeed("aibooru.online", "rating:explicit bondage"),
        BooruFeed("aibooru.online", "rating:explicit bdsm"),
        // ── e6ai.net — AI furry art, e621/danbooru engine, no key ────────────────
        BooruFeed("e6ai.net", "order:score"),
        BooruFeed("e6ai.net", "bondage"),
        BooruFeed("e6ai.net", "sex"),
        // ── Added batch: rule34.xxx extra tags ────────────────────────────────────
        BooruFeed("rule34.xxx", "creampie"),
        BooruFeed("rule34.xxx", "double_penetration"),
        BooruFeed("rule34.xxx", "facial"),
        BooruFeed("rule34.xxx", "cumshot"),
        BooruFeed("rule34.xxx", "titfuck"),
        BooruFeed("rule34.xxx", "footjob"),
        BooruFeed("rule34.xxx", "handjob"),
        BooruFeed("rule34.xxx", "pov"),
        BooruFeed("rule34.xxx", "cowgirl_position"),
        BooruFeed("rule34.xxx", "doggy_style"),
        BooruFeed("rule34.xxx", "threesome"),
        BooruFeed("rule34.xxx", "bukkake"),
        BooruFeed("rule34.xxx", "ahegao"),
        BooruFeed("rule34.xxx", "tentacles"),
        BooruFeed("rule34.xxx", "futanari"),
        BooruFeed("rule34.xxx", "yuri"),
        BooruFeed("rule34.xxx", "feet"),
        BooruFeed("rule34.xxx", "stockings"),
        BooruFeed("rule34.xxx", "latex"),
        BooruFeed("rule34.xxx", "interracial"),
        BooruFeed("rule34.xxx", "public"),
        BooruFeed("rule34.xxx", "group_sex"),
        BooruFeed("rule34.xxx", "mind_control"),
        BooruFeed("rule34.xxx", "hypnosis"),
        // ── Added batch: gelbooru.com extra tags ──────────────────────────────────
        BooruFeed("gelbooru.com", "creampie rating:explicit"),
        BooruFeed("gelbooru.com", "facial rating:explicit"),
        BooruFeed("gelbooru.com", "cumshot rating:explicit"),
        BooruFeed("gelbooru.com", "double_penetration rating:explicit"),
        BooruFeed("gelbooru.com", "threesome rating:explicit"),
        BooruFeed("gelbooru.com", "footjob rating:explicit"),
        BooruFeed("gelbooru.com", "paizuri rating:explicit"),
        BooruFeed("gelbooru.com", "ahegao rating:explicit"),
        BooruFeed("gelbooru.com", "tentacles rating:explicit"),
        BooruFeed("gelbooru.com", "futanari rating:explicit"),
        BooruFeed("gelbooru.com", "yuri rating:explicit"),
        BooruFeed("gelbooru.com", "feet rating:explicit"),
        BooruFeed("gelbooru.com", "stockings rating:explicit"),
        BooruFeed("gelbooru.com", "interracial rating:explicit"),
        BooruFeed("gelbooru.com", "pov rating:explicit"),
        BooruFeed("gelbooru.com", "doggystyle rating:explicit"),
        // ── Added batch: e621.net extra tags ──────────────────────────────────────
        BooruFeed("e621.net", "knot explicit order:random"),
        BooruFeed("e621.net", "oral explicit order:random"),
        BooruFeed("e621.net", "double_penetration explicit order:random"),
        BooruFeed("e621.net", "feet explicit order:random"),
        BooruFeed("e621.net", "threesome explicit order:random"),
        BooruFeed("e621.net", "orgy explicit order:random"),
        BooruFeed("e621.net", "creampie explicit order:random"),
        BooruFeed("e621.net", "pov explicit order:random"),
        // ── Added batch: danbooru.donmai.us extra tags (2-tag anon limit) ─────────
        BooruFeed("danbooru.donmai.us", "rating:explicit creampie"),
        BooruFeed("danbooru.donmai.us", "rating:explicit paizuri"),
        BooruFeed("danbooru.donmai.us", "rating:explicit cum_in_pussy"),
        BooruFeed("danbooru.donmai.us", "rating:explicit double_penetration"),
        BooruFeed("danbooru.donmai.us", "rating:explicit threesome"),
        BooruFeed("danbooru.donmai.us", "rating:explicit ahegao"),
        BooruFeed("danbooru.donmai.us", "rating:explicit stockings"),
        BooruFeed("danbooru.donmai.us", "rating:explicit nipples"),
        BooruFeed("danbooru.donmai.us", "rating:explicit yuri"),
        BooruFeed("danbooru.donmai.us", "rating:explicit footjob"),
        // ── Added batch: realbooru.com (real-photo, gelbooru engine) extra tags ───
        BooruFeed("realbooru.com", "creampie"),
        BooruFeed("realbooru.com", "threesome"),
        BooruFeed("realbooru.com", "interracial"),
        BooruFeed("realbooru.com", "pov"),
        BooruFeed("realbooru.com", "cowgirl"),
        BooruFeed("realbooru.com", "doggystyle"),
        BooruFeed("realbooru.com", "facial"),
        BooruFeed("realbooru.com", "pornstar"),
        BooruFeed("realbooru.com", "asian"),
        BooruFeed("realbooru.com", "latina"),
        BooruFeed("realbooru.com", "ebony"),
        BooruFeed("realbooru.com", "redhead"),
        BooruFeed("realbooru.com", "blonde"),
        BooruFeed("realbooru.com", "lingerie"),
        // ── Added batch: yande.re / konachan extra tags (moebooru) ────────────────
        BooruFeed("yande.re", "rating:explicit nipples"),
        BooruFeed("yande.re", "rating:explicit ass"),
        BooruFeed("yande.re", "rating:explicit paizuri"),
        BooruFeed("yande.re", "rating:explicit cum"),
        BooruFeed("konachan.com", "sex"),
        BooruFeed("konachan.com", "ass"),
        BooruFeed("konachan.com", "cum"),
        BooruFeed("konachan.com", "nipples"),
        // ── Added batch: tbib.org / xbooru.com extra tags (gelbooru mirrors) ──────
        BooruFeed("tbib.org", "creampie"),
        BooruFeed("tbib.org", "threesome"),
        BooruFeed("tbib.org", "footjob"),
        BooruFeed("tbib.org", "titfuck"),
        BooruFeed("tbib.org", "interracial"),
        BooruFeed("xbooru.com", "creampie"),
        BooruFeed("xbooru.com", "threesome"),
        BooruFeed("xbooru.com", "titfuck"),
        BooruFeed("xbooru.com", "interracial"),
        BooruFeed("xbooru.com", "pov"),
        // ── Added batch: rule34.us (gelbooru engine, no key needed) — new host ────
        BooruFeed("rule34.us", "sex"),
        BooruFeed("rule34.us", "nude"),
        BooruFeed("rule34.us", "blowjob"),
        BooruFeed("rule34.us", "anal"),
        BooruFeed("rule34.us", "cum"),
        BooruFeed("rule34.us", "creampie"),
        BooruFeed("rule34.us", "bondage"),
        BooruFeed("rule34.us", "futanari"),
        BooruFeed("rule34.us", "animated"),
        BooruFeed("rule34.us", "bdsm"),
        // ── Added batch: aibooru.online / e6ai.net extra tags (AI art) ────────────
        BooruFeed("aibooru.online", "rating:explicit creampie"),
        BooruFeed("aibooru.online", "rating:explicit threesome"),
        BooruFeed("aibooru.online", "rating:explicit stockings"),
        BooruFeed("e6ai.net", "cum"),
        BooruFeed("e6ai.net", "oral"),
        BooruFeed("e6ai.net", "anal"),
    )

    /** Hand-picked feeds first, then the generated [SourceCatalog]; de-duplicated. */
    val redditSubs: List<String> = (curatedRedditSubs + SourceCatalog.redditSubs).distinctBy { it.trim().lowercase() }
    val mastodonFeeds: List<MastodonFeed> = (curatedMastodonFeeds + SourceCatalog.mastodonFeeds)
        .filterNot { MastodonExclusions.isExcluded(it.host) || isRetiredHost(it.host) }
        .distinctBy { it.host.trim().lowercase() + "|" + (it.tag?.trim()?.lowercase() ?: "") }
    val booruFeeds: List<BooruFeed> = (curatedBooruFeeds + SourceCatalog.booruFeeds)
        .filterNot { isRetiredHost(it.host) }
        .distinctBy { it.host.trim().lowercase() + "|" + (it.tags?.trim()?.lowercase() ?: "") }

    /**
     * coomer.st (OnlyFans/Fansly; moved from coomer.su in 2025). Its sibling kemono shares the API but
     * is mostly non-adult Patreon/Fanbox posts with no rating to filter on, so it isn't a default.
     */
    val kemonoHosts: List<String> = listOf("coomer.st")

    /**
     * Sites whose content is non-adult by design (SFW mirrors and photo/idol boards). Never fetched,
     * even if added by hand: this app is for adult content and these only dilute the feed.
     */
    private val SFW_ONLY_HOSTS = setOf(
        "safebooru.donmai.us", "safebooru.org", "e926.net", "konachan.net", "idol.sankakucomplex.com",
        "sakugabooru.com", "kemono.su", "kemono.party", "kemono.cr",
    )

    /**
     * Hosts that were in earlier libraries and were checked dead or unusable (domain gone, server
     * shut down, needs an account, or not an engine the app reads). Dropped from defaults and from
     * saved settings, and never fetched.
     */
    private val RETIRED_HOSTS = setOf(
        "rule34.us", "dollbooru.org",
        "lewd.town", "kinky.business", "bdsmr.com", "sex.social", "kinkies.com", "femdom.social",
        "kemonodon.space", "latex.social", "lewdland.social", "nsfwdone.social", "mstdn.social",
        "cum.salon", "sinblr.com",
    )

    fun isRetiredHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.")
        return h in RETIRED_HOSTS
    }

    fun isSfwOnlyHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.")
        return SFW_ONLY_HOSTS.any { h == it || h.endsWith(".$it") }
    }
}
